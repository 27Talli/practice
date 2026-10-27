package com.offerpoint.pos.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.BackupPreview
import com.offerpoint.pos.domain.repository.BackupRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupState(val busy: Boolean = false, val preview: BackupPreview? = null, val error: String? = null, val notice: String? = null)

class BackupViewModel(private val repository: BackupRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupState())
    val state = mutableState.asStateFlow()
    private var restorePassword: CharArray? = null

    fun export(uri: String, password: CharArray) {
        if (mutableState.value.busy) { password.fill('\u0000'); return }
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val result = repository.export(uri, password)
                mutableState.update { it.copy(notice = "Encrypted backup saved and read back successfully. Keep the file and passphrase safe. SHA-256: ${result.sha256}") }
            } catch (e: Exception) { if (e is CancellationException) throw e; error(e) }
            finally { password.fill('\u0000'); mutableState.update { it.copy(busy = false) } }
        }
    }

    fun inspect(uri: String, password: CharArray) {
        if (mutableState.value.busy) { password.fill('\u0000'); return }
        discard()
        mutableState.value = BackupState(busy = true)
        viewModelScope.launch {
            try {
                val preview = repository.inspect(uri,password)
                restorePassword = password.copyOf()
                mutableState.value = BackupState(preview = preview)
            } catch (e: Exception) { if(e is CancellationException) throw e; error(e) }
            finally { password.fill('\u0000'); mutableState.update { it.copy(busy = false) } }
        }
    }

    fun restore(recoveryUri: String, confirmation: String) {
        if(mutableState.value.busy) return
        val preview = mutableState.value.preview ?: return
        if(confirmation != "RESTORE") { mutableState.update { it.copy(error = "Type RESTORE to confirm replacing all shop data.") }; return }
        val password = restorePassword ?: run { mutableState.update { it.copy(error = "Inspect the backup again before restoring.") }; return }
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                repository.restore(preview.token,recoveryUri,password)
                restorePassword?.fill('\u0000'); restorePassword=null
                mutableState.value = BackupState(notice = "Restore completed. Reopening the restored workspace…")
            } catch(e: Exception) { if(e is CancellationException) throw e; error(e) }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun discard() {
        mutableState.value.preview?.let { repository.discard(it.token) }
        restorePassword?.fill('\u0000'); restorePassword=null
        mutableState.update { it.copy(preview=null) }
    }
    private fun error(e: Exception) { mutableState.update { it.copy(error=e.message ?: "The backup operation failed. Current data was not replaced.") } }
    override fun onCleared() {
        // During restore, the new dataset namespace disposes this UI after the commit.
        // Let the repository close its staging database before deleting its files.
        if(!mutableState.value.busy) discard()
        restorePassword?.fill('\u0000'); restorePassword=null
    }
}
