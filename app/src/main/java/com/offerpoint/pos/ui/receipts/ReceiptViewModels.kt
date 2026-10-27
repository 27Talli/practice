package com.offerpoint.pos.ui.receipts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.ReceiptSettingsRepository
import com.offerpoint.pos.domain.repository.SalesRepository
import com.offerpoint.pos.domain.usecase.BuildReceipt
import com.offerpoint.pos.domain.usecase.ReceiptValidation
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

data class ReceiptState(val loading: Boolean = true, val receipt: ReceiptDocument? = null, val paper: ReceiptPaper = ReceiptPaper.A4,
                        val exporting: Boolean = false, val error: String? = null)

open class ReceiptDocumentViewModel(private val loader:suspend()->Pair<ReceiptDocument,ReceiptPaper>) : ViewModel() {
    private val mutableState = MutableStateFlow(ReceiptState())
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
                val (receipt,paper) = loader()
                mutableState.value = ReceiptState(loading = false, receipt = receipt, paper = paper)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = ReceiptState(loading = false, error = error.message ?: "The receipt could not be opened.")
            } finally { loading = false }
        }
    }

    fun paper(paper: ReceiptPaper) { if (!mutableState.value.exporting) mutableState.update { it.copy(paper = paper, error = null) } }
    fun reportError(message: String) { mutableState.update { it.copy(error = message) } }

    fun export(context: Context) {
        val current = mutableState.value
        val receipt = current.receipt ?: return
        if (current.exporting || current.loading) return
        mutableState.update { it.copy(exporting = true, error = null) }
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { ReceiptPdfExporter.write(context.applicationContext, receipt, current.paper) }
                exports.send(file)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportError(error.message ?: "The receipt PDF could not be created.")
            } finally { mutableState.update { it.copy(exporting = false) } }
        }
    }
}

class ReceiptViewModel(sales: SalesRepository, settings: ReceiptSettingsRepository, shop: Shop, saleId: Long, demo: Boolean) : ReceiptDocumentViewModel({
    val sale=sales.getSale(shop.id,saleId) ?: error("This saved sale was not found in the selected shop.")
    val preferences=settings.getSettings(shop.id)
    BuildReceipt.create(shop,sale,preferences,demo) to preferences.paper
})

class RefundReceiptViewModel(refunds: com.offerpoint.pos.domain.repository.RefundRepository, settings: ReceiptSettingsRepository, shop: Shop, returnId: Long, demo: Boolean) : ReceiptDocumentViewModel({
    val refund=refunds.get(shop.id,returnId) ?: error("This return was not found in the selected shop.")
    val preferences=settings.getSettings(shop.id)
    com.offerpoint.pos.domain.usecase.BuildRefundReceipt.create(shop,refund,preferences,demo) to preferences.paper
})

data class ReceiptPreferencesState(val loading: Boolean = true, val settings: ReceiptSettings? = null,
                                   val saving: Boolean = false, val error: String? = null, val notice: String? = null)

class ReceiptSettingsViewModel(private val repository: ReceiptSettingsRepository, private val shopId: Long) : ViewModel() {
    private val mutableState = MutableStateFlow(ReceiptPreferencesState())
    val state = mutableState.asStateFlow()
    private var loading = false
    init { load() }
    fun load() {
        if (loading || mutableState.value.saving) return
        loading = true
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try { mutableState.value = ReceiptPreferencesState(loading = false, settings = repository.getSettings(shopId)) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, error = error.message ?: "Receipt settings could not be loaded.") }
            } finally { loading = false }
        }
    }
    fun save(paper: ReceiptPaper, footer: String) {
        if (loading || mutableState.value.saving) return
        val value = try { ReceiptValidation.settings((mutableState.value.settings ?: ReceiptSettings(shopId)).copy(paper=paper,footer=footer)) }
            catch (error: IllegalArgumentException) { mutableState.update { it.copy(error = error.message) }; return }
        mutableState.update { it.copy(saving = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val merged = ReceiptValidation.settings(repository.getSettings(shopId).copy(paper=value.paper,footer=value.footer))
                repository.saveSettings(merged)
                mutableState.update { it.copy(settings = merged, notice = "Receipt settings saved for this shop.") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error.message ?: "Receipt settings could not be saved.") }
            } finally { mutableState.update { it.copy(saving = false) } }
        }
    }
}
