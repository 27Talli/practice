package com.offerpoint.pos

import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.BackupRepository
import com.offerpoint.pos.ui.backup.BackupViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val repo=FakeBackups()
    private lateinit var model: BackupViewModel
    @Before fun before(){Dispatchers.setMain(dispatcher);model=BackupViewModel(repo)}
    @After fun after(){model.viewModelScope.cancel();Dispatchers.resetMain()}
    @Test fun exportWipesTheSuppliedPassphraseAndOnlyReportsVerifiedSuccess() = runTest(dispatcher) {
        val secret="backup-password".toCharArray();model.export("destination",secret);runCurrent()
        assertTrue(secret.all{it=='\u0000'});assertNotNull(model.state.value.notice);assertFalse(model.state.value.busy)
    }
    @Test fun failedExportDoesNotReportSuccessAndCanBeRetried() = runTest(dispatcher) {
        repo.fail=true;model.export("destination","backup-password".toCharArray());runCurrent()
        assertNotNull(model.state.value.error);assertNull(model.state.value.notice)
        repo.fail=false;model.export("destination","backup-password".toCharArray());runCurrent();assertNotNull(model.state.value.notice)
    }
    @Test fun restoreRequiresInspectionAndExactExplicitConfirmation() = runTest(dispatcher) {
        model.restore("recovery","RESTORE");runCurrent();assertEquals(0,repo.restores)
        model.inspect("source","backup-password".toCharArray());runCurrent()
        model.restore("recovery","restore");runCurrent();assertEquals(0,repo.restores)
        model.restore("recovery","RESTORE");runCurrent();assertEquals(1,repo.restores);assertNull(model.state.value.preview)
    }
    @Test fun failedInspectionAndDiscardNeverRestoreOrLeaveAPreview() = runTest(dispatcher) {
        repo.fail=true;model.inspect("source","backup-password".toCharArray());runCurrent();assertNull(model.state.value.preview)
        repo.fail=false;model.inspect("source","backup-password".toCharArray());runCurrent();model.discard()
        assertEquals(listOf("token"),repo.discarded);model.restore("recovery","RESTORE");runCurrent();assertEquals(0,repo.restores)
    }
    @Test fun failedRecoverySaveLeavesThePreviewAvailableForRetry() = runTest(dispatcher) {
        model.inspect("source","backup-password".toCharArray());runCurrent();repo.fail=true
        model.restore("recovery","RESTORE");runCurrent();assertNotNull(model.state.value.error);assertNotNull(model.state.value.preview)
        repo.fail=false;model.restore("another-recovery","RESTORE");runCurrent();assertEquals(1,repo.restores)
    }
    private class FakeBackups:BackupRepository {
        var fail=false;var restores=0;val discarded=mutableListOf<String>()
        override fun observeGeneration()=flowOf("")
        override suspend fun export(destination:String,password:CharArray):BackupResult {if(fail)error("Save failed");return BackupResult("id",100,"sha")}
        override suspend fun inspect(source:String,password:CharArray):BackupPreview {if(fail)error("Invalid backup");return BackupPreview("token","id",1,false,mapOf("products" to 1L),listOf("Offer Point","Fancy Footwears"))}
        override suspend fun restore(token:String,recoveryDestination:String,password:CharArray){if(fail)error("Recovery failed");restores++}
        override fun discard(token:String){discarded.add(token)}
    }
}
