package com.offerpoint.pos.ui.security

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.repository.SecurityRepository
import com.offerpoint.pos.domain.repository.BackupRepository
import com.offerpoint.pos.domain.repository.SecurityStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LockState(val loading:Boolean=true,val locked:Boolean=true,val status:SecurityStatus=SecurityStatus(),val busy:Boolean=false,val error:String?=null)
class SecurityViewModel(private val repo:SecurityRepository,backups:BackupRepository):ViewModel(){
    private val mutable=MutableStateFlow(LockState());val state=mutable.asStateFlow()
    private var activity=SystemClock.elapsedRealtime();private var generation:String?=null
    init{
        viewModelScope.launch{combine(repo.observe(),backups.observeGeneration()){status,epoch->status to epoch}.catch{e->if(e is CancellationException)throw e;mutable.update{it.copy(loading=false,locked=true,error="Local security could not be checked. Restart the app or use device unlock.")}}.collect{(status,epoch)->
            val changed=generation!=epoch;generation=epoch
            mutable.update{it.copy(loading=false,status=status,locked=status.enabled&&(it.locked||changed))}
        }}
        viewModelScope.launch{while(isActive){delay(1000);if(state.value.status.enabled&&SystemClock.elapsedRealtime()-activity>=state.value.status.autoLockMinutes*60_000L)lock()}}
    }
    fun touch(){activity=SystemClock.elapsedRealtime()}
    fun foreground(){if(state.value.status.enabled&&SystemClock.elapsedRealtime()-activity>=state.value.status.autoLockMinutes*60_000L)lock()}
    fun lock(){if(state.value.status.enabled)mutable.update{it.copy(locked=true,error=null)}}
    fun unlock(pin:CharArray){if(state.value.busy){pin.fill('\u0000');return};mutable.update{it.copy(busy=true,error=null)};viewModelScope.launch{try{
        if(repo.verify(pin)){touch();mutable.update{it.copy(locked=false,error=null)}}else mutable.update{it.copy(error="Incorrect PIN.")}
    }catch(e:Exception){if(e is CancellationException)throw e;mutable.update{it.copy(error=e.message)}}finally{pin.fill('\u0000');mutable.update{it.copy(busy=false)}}}}
    fun deviceReset(){viewModelScope.launch{try{repo.resetAfterDeviceCredential();touch();mutable.update{it.copy(locked=false,error=null)}}catch(e:Exception){if(e is CancellationException)throw e;mutable.update{it.copy(error="The local PIN could not be reset.")}}}}
}
