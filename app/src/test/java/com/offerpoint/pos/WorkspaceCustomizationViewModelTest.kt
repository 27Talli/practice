package com.offerpoint.pos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.WorkspacePreferencesRepository
import com.offerpoint.pos.ui.customize.WorkspaceCustomizationViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceCustomizationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<WorkspaceCustomizationViewModel>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private fun model(repository: FakePreferences, saved: SavedStateHandle = SavedStateHandle()) = WorkspaceCustomizationViewModel(repository, saved).also { models.add(it) }

    @Test fun previewChangesDoNotApplyUntilSaved() = runTest(dispatcher) {
        val repo = FakePreferences(); val vm = model(repo); runCurrent()
        vm.edit(vm.state.value.draft.copy(layout = DashboardLayout.GRID, color = AppColor.BLUE))
        vm.move(DashboardTile.CHECKOUT, -1); vm.tile(DashboardTile.REPORTS, false)
        assertEquals(0, repo.writes); assertTrue(vm.state.value.dirty); assertEquals(WorkspacePreferences(), repo.current.value)
        vm.save(); runCurrent()
        assertEquals(1, repo.writes); assertEquals(vm.state.value.draft, repo.current.value); assertFalse(vm.state.value.dirty)
    }

    @Test fun anUnappliedDraftSurvivesRecreationAndACompletedSaveDoesNotResurrectIt() = runTest(dispatcher) {
        val repo = FakePreferences(); val saved = SavedStateHandle(); val first = model(repo, saved); runCurrent()
        first.edit(first.state.value.draft.copy(heading = "My counter", textSize = AppTextSize.LARGEST, checkoutStart = CheckoutStart.CART))
        first.tile(DashboardTile.CHECKOUT, label = "Sell")
        val restored = model(repo, saved); runCurrent(); assertEquals(first.state.value.draft, restored.state.value.draft)
        restored.save(); runCurrent(); val reopened = model(repo, saved); runCurrent()
        assertFalse(reopened.state.value.dirty); assertEquals(repo.current.value, reopened.state.value.draft)
    }

    @Test fun failedSaveKeepsTheDraftAndCanBeRetried() = runTest(dispatcher) {
        val repo = FakePreferences(); val vm = model(repo); runCurrent(); vm.edit(vm.state.value.draft.copy(appearance = AppAppearance.DARK))
        repo.failSave = true; vm.save(); runCurrent()
        assertTrue(vm.state.value.dirty); assertFalse(vm.state.value.saving); assertNotNull(vm.state.value.error)
        repo.failSave = false; vm.save(); runCurrent(); assertFalse(vm.state.value.dirty); assertNull(vm.state.value.error)
    }

    @Test fun repeatedApplyTapsSaveOnceAndEditsAreBlockedDuringTheSave() = runTest(dispatcher) {
        val repo = FakePreferences(); val vm = model(repo); runCurrent()
        vm.edit(vm.state.value.draft.copy(color = AppColor.PURPLE)); vm.save(); vm.save(); vm.edit(WorkspacePreferences())
        runCurrent(); assertEquals(1, repo.writes); assertEquals(AppColor.PURPLE, repo.current.value.color)
    }

    @Test fun resettingOrImportingOnlyChangesThePreview() = runTest(dispatcher) {
        val repo = FakePreferences(WorkspacePreferences(layout = DashboardLayout.COMPACT)); val vm = model(repo); runCurrent()
        vm.reset(); assertEquals(WorkspacePreferences(), vm.state.value.draft); assertEquals(DashboardLayout.COMPACT, repo.current.value.layout)
        vm.imported(WorkspacePreferences(color = AppColor.AMBER)); assertEquals(0, repo.writes); assertEquals(AppColor.AMBER, vm.state.value.draft.color)
        vm.save(); runCurrent(); assertEquals(AppColor.AMBER, repo.current.value.color)
    }

    @Test fun invalidHeadingsAreRejectedBeforeAnyPersistentWrite() = runTest(dispatcher) {
        val repo = FakePreferences(); val vm = model(repo); runCurrent(); vm.edit(vm.state.value.draft.copy(heading = " ")); vm.save(); runCurrent()
        assertEquals(0, repo.writes); assertNotNull(vm.state.value.error)
    }

    @Test fun unreadableSavedPreferencesOfferAnEditableDefaultForRecovery() = runTest(dispatcher) {
        val repo = FakePreferences().apply { current = WorkspacePreferencesSnapshot(error = "Unreadable saved layout") }
        val vm = model(repo); runCurrent(); assertFalse(vm.state.value.loading); assertNotNull(vm.state.value.error)
        vm.reset(); vm.save(); runCurrent(); assertEquals(WorkspacePreferences(), repo.current.value); assertNull(vm.state.value.error)
    }

    private class FakePreferences(initial: WorkspacePreferences = WorkspacePreferences()) : WorkspacePreferencesRepository {
        var current = WorkspacePreferencesSnapshot(initial)
        var writes = 0
        var failSave = false
        override fun observe() = flowOf(current)
        override suspend fun get() = current
        override suspend fun save(preferences: WorkspacePreferences) { if (failSave) error("Storage full"); writes++; current = WorkspacePreferencesSnapshot(preferences) }
    }
}
