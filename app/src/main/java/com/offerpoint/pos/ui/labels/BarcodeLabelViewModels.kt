package com.offerpoint.pos.ui.labels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.CostCodeSettings
import com.offerpoint.pos.domain.model.InternalBarcodeLabel
import com.offerpoint.pos.domain.repository.BarcodeLabelRepository
import com.offerpoint.pos.domain.usecase.CostCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ProductLabelState(val loading: Boolean = true, val label: InternalBarcodeLabel? = null,
                             val exporting: Boolean = false, val error: String? = null)

class ProductLabelViewModel(private val repository: BarcodeLabelRepository, private val shopId: Long,
                           private val productId: Long) : ViewModel() {
    private val mutableState = MutableStateFlow(ProductLabelState())
    val state = mutableState.asStateFlow()
    private val exports = Channel<File>(Channel.BUFFERED)
    val pdfExports = exports.receiveAsFlow()
    private var loading = false
    init { load() }

    fun load() {
        if (loading || mutableState.value.exporting) return
        loading = true
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val label = repository.createLabel(shopId, productId)
                mutableState.update { it.copy(loading = false, label = label) }
            }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, error = error.message ?: "The barcode label could not be created.") }
            } finally { loading = false }
        }
    }

    fun export(context: Context, shopName: String, quantityText: String) {
        val current = mutableState.value
        if (current.exporting || current.loading) return
        val label = current.label ?: return
        val count = quantityText.trim().toIntOrNull()
        if (count == null || count !in 1..100) { reportError("Choose a whole number of labels from 1 to 100."); return }
        mutableState.update { it.copy(exporting = true, error = null) }
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { LabelPdfExporter.write(context.applicationContext, shopName, label, count) }
                exports.send(file)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportError(error.message ?: "The label PDF could not be created.")
            } finally { mutableState.update { it.copy(exporting = false) } }
        }
    }

    fun reportError(message: String) { mutableState.update { it.copy(error = message) } }
}

data class CostCodeState(val loading: Boolean = true, val settings: CostCodeSettings? = null,
                         val saving: Boolean = false, val error: String? = null, val notice: String? = null)

class CostCodeViewModel(private val repository: BarcodeLabelRepository, private val shopId: Long) : ViewModel() {
    private val mutableState = MutableStateFlow(CostCodeState())
    val state = mutableState.asStateFlow()
    private var loading = false
    init { load() }

    fun load() {
        if (loading || mutableState.value.saving) return
        loading = true
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val settings = repository.getOrCreateCostCodeSettings(shopId)
                mutableState.update { it.copy(loading = false, settings = settings) }
            }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, error = error.message ?: "The private cost-code key could not be loaded.") }
            } finally { loading = false }
        }
    }

    fun save(value: String) {
        if (mutableState.value.saving || mutableState.value.loading) return
        val alphabet = try { CostCode.validateAlphabet(value) } catch (error: IllegalArgumentException) {
            mutableState.update { it.copy(error = error.message) }; return
        }
        mutableState.update { it.copy(saving = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                repository.updateCostCodeAlphabet(shopId, alphabet)
                val settings = repository.getOrCreateCostCodeSettings(shopId)
                mutableState.update { it.copy(settings = settings, notice = "Key saved. New labels use this key; older labels remain valid.") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error.message ?: "The cost-code key could not be saved.") }
            } finally { mutableState.update { it.copy(saving = false) } }
        }
    }
}
