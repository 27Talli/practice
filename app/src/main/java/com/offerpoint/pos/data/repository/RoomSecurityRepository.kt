package com.offerpoint.pos.data.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.local.database.withWriteTransaction
import com.offerpoint.pos.data.local.entity.OwnerSecurityEntity
import com.offerpoint.pos.data.local.entity.AuditEventEntity
import com.offerpoint.pos.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.*
import javax.crypto.spec.PBEKeySpec

/** Device-bound HMAC key prevents a copied PIN hash being checked without this app's keystore. */
class RoomSecurityRepository(private val db:PosDatabase,private val alias:String="offerpoint.owner-pin.v1",private val now:()->Long=System::currentTimeMillis):SecurityRepository {
    private val dao=db.posDao()
    override fun observe()=dao.observeSecurity().map{it?.let{row->SecurityStatus(row.pinHash!=null,row.failedAttempts,row.lockedUntil,row.autoLockMinutes)} ?: SecurityStatus()}
    private fun digest(pin:CharArray,salt:ByteArray):ByteArray {
        val spec=PBEKeySpec(pin,salt,600_000,256)
        val derived=try{SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}finally{spec.clearPassword()}
        try {
            val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            val key=store.getKey(alias,null) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore").apply{
                init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKey()
            return Mac.getInstance("HmacSHA256").apply{init(key)}.doFinal(derived)
        }finally{derived.fill(0)}
    }
    override suspend fun verify(pin:CharArray):Boolean=withContext(Dispatchers.IO){db.withWriteTransaction{
        val row=dao.getSecurity() ?: return@withWriteTransaction true
        if(row.pinHash==null)return@withWriteTransaction true
        require(now()>=row.lockedUntil){"Too many attempts. Wait before trying again."}
        val checked=digest(pin,Base64.getDecoder().decode(row.salt))
        val valid=try{MessageDigest.isEqual(checked,Base64.getDecoder().decode(row.pinHash))}finally{checked.fill(0)}
        val attempts=if(valid)0 else (row.failedAttempts+1).coerceAtMost(1000)
        val delay=if(valid||attempts%5!=0)0L else when{attempts>=15->30*60_000L;attempts>=10->5*60_000L;else->60_000L}
        dao.upsertSecurity(row.copy(failedAttempts=attempts,lockedUntil=if(delay==0L)0 else now()+delay,updatedAt=now()))
        valid
    }}
    override suspend fun configure(current:CharArray,newPin:CharArray?,minutes:Int):Unit=withContext(Dispatchers.IO){
        require(minutes in 1..30){"Choose an auto-lock time of 1–30 minutes."}
        newPin?.let{require(it.size in 6..12 && it.all{ch->ch in '0'..'9'}){"Use 6–12 digits for the local PIN."}}
        require(verify(current)){"The current PIN was not correct."}
        db.withWriteTransaction{
            if(newPin==null)dao.upsertSecurity(OwnerSecurityEntity(salt=null,pinHash=null,failedAttempts=0,lockedUntil=0,autoLockMinutes=minutes,updatedAt=now()))
            else{
                val salt=ByteArray(16).also{SecureRandom().nextBytes(it)};val hash=digest(newPin,salt)
                try{dao.upsertSecurity(OwnerSecurityEntity(salt=Base64.getEncoder().encodeToString(salt),pinHash=Base64.getEncoder().encodeToString(hash),failedAttempts=0,lockedUntil=0,autoLockMinutes=minutes,updatedAt=now()))}finally{hash.fill(0)}
            }
            dao.insertAudit(AuditEventEntity(entityType="SECURITY",entityId=1,shopId=null,action=if(newPin==null)"DISABLE_LOCAL_PIN" else "SET_LOCAL_PIN",beforeValue=null,afterValue=null,createdAt=now()))
            Unit
        }
    }
    override suspend fun resetAfterDeviceCredential(){db.withWriteTransaction{
        dao.upsertSecurity(OwnerSecurityEntity(salt=null,pinHash=null,failedAttempts=0,lockedUntil=0,autoLockMinutes=2,updatedAt=now()))
        dao.insertAudit(AuditEventEntity(entityType="SECURITY",entityId=1,shopId=null,action="RESET_PIN_AFTER_DEVICE_CREDENTIAL",beforeValue=null,afterValue=null,createdAt=now()))
    }}
}
