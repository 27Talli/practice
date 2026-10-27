package com.offerpoint.pos.domain.repository

import kotlinx.coroutines.flow.Flow

data class SecurityStatus(val enabled:Boolean=false,val failedAttempts:Int=0,val lockedUntil:Long=0,val autoLockMinutes:Int=2)
interface SecurityRepository {
    fun observe():Flow<SecurityStatus>
    suspend fun verify(pin:CharArray):Boolean
    suspend fun configure(current:CharArray,newPin:CharArray?,minutes:Int)
    /** Called only after a successful Android device-credential challenge. */
    suspend fun resetAfterDeviceCredential()
}
