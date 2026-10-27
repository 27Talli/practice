package com.offerpoint.pos.ui.customize

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.WorkspacePreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WorkspaceCustomizationState(
    val loading: Boolean = true,
    val draft: WorkspacePreferences = WorkspacePreferences(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class WorkspaceCustomizationViewModel(
    private val repository: WorkspacePreferencesRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable = MutableStateFlow(WorkspaceCustomizationState())
    val state = mutable.asStateFlow()

    init { load() }

    fun load() {
        if (state.value.saving) return
        mutable.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val stored = repository.get()
                val restored = restoreDraft()
                mutable.value = WorkspaceCustomizationState(loading = false, draft = restored ?: stored.value,
                    dirty = restored != null, error = stored.error)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = WorkspaceCustomizationState(loading = false, error = "Your customization could not be opened. Retry, or reset the layout below.")
            }
        }
    }

    fun edit(value: WorkspacePreferences) {
        if (state.value.loading || state.value.saving) return
        mutable.value = state.value.copy(draft = value.copy(tiles = value.tiles.toList()), dirty = true, error = null, notice = null)
        persist()
    }

    fun move(tile: DashboardTile, direction: Int) = edit(state.value.draft.move(tile, direction))
    fun tile(tile: DashboardTile, visible: Boolean? = null, label: String? = null) = edit(state.value.draft.editTile(tile, visible, label))
    fun reset() = edit(WorkspacePreferences())
    fun imported(value: WorkspacePreferences) {
        edit(WorkspaceCustomization.validate(value))
        if (!state.value.loading && !state.value.saving) mutable.value = state.value.copy(notice = "Layout loaded into the preview. Choose Apply changes to use it.")
    }
    fun message(value: String) { mutable.value = state.value.copy(notice = value) }
    fun error(value: String) { mutable.value = state.value.copy(error = value) }

    fun save() {
        if (state.value.loading || state.value.saving) return
        val value = try { WorkspaceCustomization.validate(state.value.draft) }
        catch (error: IllegalArgumentException) { mutable.value = state.value.copy(error = error.message); return }
        mutable.value = state.value.copy(saving = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                repository.save(value)
                mutable.value = state.value.copy(draft = value, dirty = false, notice = "Your app layout and appearance are saved on this device.")
                saved["customization_dirty"] = false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = state.value.copy(error = error.message ?: "Your layout could not be saved. Try again.")
            } finally { mutable.value = state.value.copy(saving = false) }
        }
    }

    private fun persist() {
        val draft = state.value.draft
        saved["customization_dirty"] = true
        saved["heading"] = draft.heading
        saved["layout"] = draft.layout.name
        saved["color"] = draft.color.name
        saved["appearance"] = draft.appearance.name
        saved["textSize"] = draft.textSize.name
        saved["checkoutStart"] = draft.checkoutStart.name
        saved["order"] = ArrayList(draft.tiles.map { it.tile.name })
        saved["visible"] = draft.tiles.map { it.visible }.toBooleanArray()
        saved["labels"] = ArrayList(draft.tiles.map { it.label })
    }

    private fun restoreDraft(): WorkspacePreferences? {
        if (saved.get<Boolean>("customization_dirty") != true) return null
        return runCatching {
            val order = requireNotNull(saved.get<ArrayList<String>>("order"))
            val visible = requireNotNull(saved.get<BooleanArray>("visible"))
            val labels = requireNotNull(saved.get<ArrayList<String>>("labels"))
            require(order.size == visible.size && order.size == labels.size)
            WorkspaceCustomization.validate(WorkspacePreferences(
                heading = requireNotNull(saved["heading"]), layout = DashboardLayout.valueOf(requireNotNull(saved["layout"])),
                color = AppColor.valueOf(requireNotNull(saved["color"])), appearance = AppAppearance.valueOf(requireNotNull(saved["appearance"])),
                textSize = AppTextSize.valueOf(requireNotNull(saved["textSize"])), checkoutStart = CheckoutStart.valueOf(requireNotNull(saved["checkoutStart"])),
                tiles = order.indices.map { DashboardTilePreference(DashboardTile.valueOf(order[it]), visible[it], labels[it]) },
            ))
        }.getOrNull()
    }
}
