package com.offerpoint.pos

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.data.backup.BackupCipher
import com.offerpoint.pos.data.backup.DatabaseSnapshot
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.repository.*
import com.offerpoint.pos.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupDatabaseTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val password="a private test passphrase".toCharArray()
    private val name="backup-db-${UUID.randomUUID()}.db"
    private lateinit var db:PosDatabase
    private lateinit var backups:RoomBackupRepository
    private lateinit var products:RoomProductRepository
    private lateinit var inventory:RoomInventoryRepository
    private lateinit var labels:RoomBarcodeLabelRepository
    private lateinit var sales:RoomSalesRepository
    private var productId=0L
    private lateinit var saved:SaleDetails

    @Before fun before()=runBlocking {
        db=Room.databaseBuilder(context,PosDatabase::class.java,name).build()
        RoomShopRepository(db,false).initialize()
        products=RoomProductRepository(db);inventory=RoomInventoryRepository(db);labels=RoomBarcodeLabelRepository(db);sales=RoomSalesRepository(db)
        backups=RoomBackupRepository(context,db,false)
        productId=products.createProduct(1,ProductDraft("Backup test lipstick","Cosmetics",purchaseCostPaise=12550,sellingPricePaise=20000,openingQuantity=8))
        products.associateProduct(2,productId,10000,18000,4)
        labels.updateCostCodeAlphabet(1,"BLACKHORSE");labels.createLabel(1,productId)
        saved=sales.completeSale(CheckoutRequest("backup-sale",1,listOf(CheckoutLine(productId,2,20000,500)),300,50000))
        RoomReceiptSettingsRepository(db).saveSettings(ReceiptSettings(1,ReceiptPaper.ROLL_58,"Thank you"))
    }
    @After fun after(){ db.close();context.deleteDatabase(name) }
    private fun uri(query:String=""):String="content://com.offerpoint.pos.backup.testprovider/${UUID.randomUUID()}$query"
    private fun read(uri:String)=requireNotNull(context.contentResolver.openInputStream(Uri.parse(uri))).use{it.readBytes()}
    private fun write(uri:String,bytes:ByteArray){ requireNotNull(context.contentResolver.openOutputStream(Uri.parse(uri),"wt")).use{it.write(bytes)} }
    private fun fingerprint()=DatabaseSnapshot.fingerprint(db.openHelper.readableDatabase)
    private suspend fun failure(block:suspend()->Unit):Exception {try{block()}catch(e:Exception){return e};throw AssertionError("Expected rejected operation")}
    private fun mutate(source:String,change:(JSONObject)->Unit):String {
        val plain=ByteArrayOutputStream();BackupCipher.decrypt(read(source).inputStream(),plain,password)
        val json=JSONObject(plain.toString("UTF-8"));change(json)
        val encrypted=ByteArrayOutputStream();BackupCipher.encrypt(json.toString().toByteArray().inputStream(),encrypted,password)
        return uri().also{write(it,encrypted.toByteArray())}
    }

    @Test fun exportIsEncryptedVerifiedConsistentAndHasNoDatabaseSideEffects()=runBlocking {
        val before=fingerprint();val destination=uri();val result=backups.export(destination,password)
        assertEquals(before,fingerprint());assertEquals(read(destination).size.toLong(),result.bytes);assertEquals(64,result.sha256.length)
        assertFalse(String(read(destination),Charsets.ISO_8859_1).contains("BLACKHORSE"))
        val preview=backups.inspect(destination,password)
        assertEquals(result.backupId,preview.backupId);assertEquals(2L,preview.counts["shops"]);assertEquals(1L,preview.counts["sales"])
        assertEquals(1L,preview.counts["product_labels"]);assertFalse(preview.demo)
        backups.discard(preview.token);assertEquals(before,fingerprint())
    }

    @Test fun restoringAndReopeningPreservesSalesStockKeysLabelsCountersAndPreferences()=runBlocking {
        val destination=uri();backups.export(destination,password);val preview=backups.inspect(destination,password)
        val label=labels.createLabel(1,productId);val auditCount=db.posDao().getAudits().size
        inventory.adjustStock(1,productId,3,"New delivery after backup")
        labels.updateCostCodeAlphabet(1,"ABCDEFGHIJ")
        val recovery=uri();backups.restore(preview.token,recovery,password)
        assertEquals(6L,products.getProduct(1,productId)?.quantity);assertEquals(4L,products.getProduct(2,productId)?.quantity)
        assertEquals(saved,sales.getSale(1,saved.summary.id));assertEquals(1,db.posDao().countSales())
        assertEquals("BLACKHORSE",labels.getOrCreateCostCodeSettings(1).alphabet);assertEquals(label,labels.getLabel(1,label.barcodeId))
        assertEquals(ReceiptSettings(1,ReceiptPaper.ROLL_58,"Thank you"),RoomReceiptSettingsRepository(db).getSettings(1))
        assertEquals(auditCount+1,db.posDao().getAudits().size);assertEquals("RESTORE_BACKUP",db.posDao().getAudits().last().action)
        val generation=backups.observeGeneration().first();assertTrue(generation.isNotEmpty())
        val originalRequest=CheckoutRequest("backup-sale",1,listOf(CheckoutLine(productId,2,20000,500)),300,50000)
        assertEquals(saved,sales.completeSale(originalRequest));assertEquals(6L,products.getProduct(1,productId)?.quantity)
        db.close();db=Room.databaseBuilder(context,PosDatabase::class.java,name).build()
        assertEquals(generation,db.posDao().observeDataGeneration().first());assertEquals(saved,RoomSalesRepository(db).getSale(1,saved.summary.id))
    }

    @Test fun mandatoryRecoveryFileCanRestoreTheStateFromBeforeReplacement()=runBlocking {
        val destination=uri();backups.export(destination,password);val preview=backups.inspect(destination,password)
        inventory.adjustStock(1,productId,5,"Delivery")
        val recovery=uri();backups.restore(preview.token,recovery,password)
        assertEquals(6L,products.getProduct(1,productId)?.quantity)
        val recoveryPreview=backups.inspect(recovery,password);backups.restore(recoveryPreview.token,uri(),password)
        assertEquals(11L,products.getProduct(1,productId)?.quantity)
    }

    @Test fun wrongPasswordCorruptionTruncationAndExtraBytesLeaveLiveDataUnchanged()=runBlocking {
        val destination=uri();backups.export(destination,password);val before=fingerprint()
        failure{backups.inspect(destination,"wrong password".toCharArray())}
        val bytes=read(destination)
        val damaged=bytes.copyOf().also{it[50]=(it[50].toInt() xor 1).toByte()}
        for(candidate in listOf(damaged,bytes.copyOf(bytes.size-1),bytes+byteArrayOf(1))) {val file=uri();write(file,candidate);failure{backups.inspect(file,password)}}
        assertEquals(before,fingerprint())
    }

    @Test fun futureSchemaUnknownTablesAndInconsistentInventoryAreRejected()=runBlocking {
        val destination=uri();backups.export(destination,password);val before=fingerprint()
        val future=mutate(destination){it.put("databaseVersion",999)}
        val unknown=mutate(destination){it.getJSONArray("tables").getJSONObject(0).put("name","untrusted_table")}
        val stock=mutate(destination){json->
            val tables=json.getJSONArray("tables")
            for(i in 0 until tables.length()) {val table=tables.getJSONObject(i);if(table.getString("name")=="inventory")table.getJSONArray("rows").getJSONArray(0).put(2,999)}
        }
        for(file in listOf(future,unknown,stock))failure{backups.inspect(file,password)}
        assertEquals(before,fingerprint())
    }

    @Test fun brokenForeignKeysAndGlobalBarcodeCollisionsAreRejected()=runBlocking {
        val destination=uri();backups.export(destination,password);val before=fingerprint()
        for(broken in listOf(true,false)) {
            val invalid=mutate(destination){json->
                val tables=json.getJSONArray("tables")
                for(i in 0 until tables.length()) {val table=tables.getJSONObject(i);if(table.getString("name")=="product_barcodes") {
                    val rows=table.getJSONArray("rows");if(broken) rows.getJSONArray(0).put(1,999999) else rows.getJSONArray(1).put(2,rows.getJSONArray(0).getString(2))
                }}
            }
            failure{backups.inspect(invalid,password)}
        }
        assertEquals(before,fingerprint())
    }

    @Test fun liveAndDemoBackupsCannotBeMixedEvenWithTheCorrectPassphrase()=runBlocking {
        val demoDb=Room.inMemoryDatabaseBuilder(context,PosDatabase::class.java).build()
        try {
            RoomShopRepository(demoDb,true).initialize();val demoRepo=RoomBackupRepository(context,demoDb,true)
            val demoFile=uri();demoRepo.export(demoFile,password);val before=fingerprint();failure{backups.inspect(demoFile,password)};assertEquals(before,fingerprint())
            val liveFile=uri();backups.export(liveFile,password);failure{demoRepo.inspect(liveFile,password)}
        }finally{demoDb.close()}
    }

    @Test fun unwritableOrUnreadableRecoveryFilesAndReusingTheSourcePreventRestore()=runBlocking {
        val destination=uri();backups.export(destination,password);val preview=backups.inspect(destination,password)
        inventory.adjustStock(1,productId,1,"Adjustment after backup");val before=fingerprint()
        for(recovery in listOf(uri("?failWrite=1"),uri("?badRead=1"),destination))failure{backups.restore(preview.token,recovery,password)}
        assertEquals(before,fingerprint());assertNotNull(backups.inspect(destination,password))
    }

    @Test fun forcedInsertFailureRollsBackEveryLiveTableAndKeepsRecoveryReadable()=runBlocking {
        val destination=uri();backups.export(destination,password);val preview=backups.inspect(destination,password)
        inventory.adjustStock(1,productId,2,"Delivery");val before=fingerprint();val recovery=uri()
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_restore BEFORE INSERT ON products BEGIN SELECT RAISE(ABORT,'forced restore failure'); END")
        failure{backups.restore(preview.token,recovery,password)};assertEquals(before,fingerprint())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_restore")
        assertEquals(1L,backups.inspect(recovery,password).counts["products"])
    }

    @Test fun concurrentShopChangesDuringExternalRecoverySaveAbortReplacement()=runBlocking {
        val destination=uri();backups.export(destination,password);val preview=backups.inspect(destination,password)
        val recovery=uri("?delayWrite=1")
        val deferred=async(Dispatchers.IO){failure{backups.restore(preview.token,recovery,password)}}
        withTimeout(10_000){while(context.contentResolver.call(Uri.parse(recovery),"status",Uri.parse(recovery).lastPathSegment,null)?.getBoolean("written")!=true)delay(20)}
        inventory.adjustStock(1,productId,1,"Concurrent delivery")
        assertTrue(deferred.await().message!!.contains("Shop data changed"));assertEquals(7L,products.getProduct(1,productId)?.quantity)
    }

    @Test fun hostRescueToolBackupRestoresLegacySalesKeysAndExactStockOnAndroid()=runBlocking {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val encoded=instrumentation.context.assets.open("backup-rescue-fixture.b64").bufferedReader().use{it.readText().trim()}
        val destination=uri();write(destination,java.util.Base64.getDecoder().decode(encoded))
        val secret="fixture-passphrase".toCharArray()
        val preview=backups.inspect(destination,secret)
        backups.restore(preview.token,uri(),secret)
        assertEquals("RESCUE TEST ITEM",products.getProduct(1,1)?.name)
        assertEquals(2L,products.getProduct(1,1)?.quantity)
        assertEquals("BLACKHORSE",labels.getOrCreateCostCodeSettings(1).alphabet)
        assertEquals(100L,labels.findLabelByValue("OP-000001~LBB")?.purchaseCostPaise)
        val request=CheckoutRequest("rescue-fixture",1,listOf(CheckoutLine(1,1,200)),amountPaidPaise=500)
        assertEquals("OP-S-000001",sales.completeSale(request).summary.receiptNumber)
        assertEquals(1,db.posDao().countSales());assertEquals(2L,products.getProduct(1,1)?.quantity)
    }

    @Test fun maximumLongMoneyAndQuantityRemainExactAcrossBackupAndRestore()=runBlocking {
        val id=products.createProduct(1,ProductDraft("Maximum integer fixture","Test",purchaseCostPaise=Long.MAX_VALUE,sellingPricePaise=Long.MAX_VALUE,openingQuantity=Long.MAX_VALUE))
        val destination=uri();backups.export(destination,password);val preview=backups.inspect(destination,password)
        products.updateProduct(1,id,ProductDraft("Changed fixture","Test",purchaseCostPaise=1,sellingPricePaise=2))
        backups.restore(preview.token,uri(),password)
        val restored=requireNotNull(products.getProduct(1,id))
        assertEquals(Long.MAX_VALUE,restored.purchaseCostPaise);assertEquals(Long.MAX_VALUE,restored.sellingPricePaise);assertEquals(Long.MAX_VALUE,restored.quantity)
    }

    @Test fun changedStagingFileIsRejectedBeforeCurrentDataCanBeReplaced()=runBlocking {
        val destination=uri();backups.export(destination,password);val preview=backups.inspect(destination,password)
        val before=fingerprint()
        val staged=context.cacheDir.walkTopDown().first { it.name=="${preview.token}.db" }
        java.io.RandomAccessFile(staged,"rw").use { it.seek(100);it.write(0) }
        failure{backups.restore(preview.token,uri(),password)};assertEquals(before,fingerprint());backups.discard(preview.token)
    }

    @Test fun discardedPreviewCannotReplaceData()=runBlocking {
        val destination=uri();backups.export(destination,password);val preview=backups.inspect(destination,password);backups.discard(preview.token)
        val before=fingerprint();failure{backups.restore(preview.token,uri(),password)};assertEquals(before,fingerprint())
    }
}
