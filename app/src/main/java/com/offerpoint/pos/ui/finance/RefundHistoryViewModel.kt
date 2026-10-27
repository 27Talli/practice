package com.offerpoint.pos.ui.finance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.RefundDetails
import com.offerpoint.pos.domain.model.SaleDetails
import com.offerpoint.pos.domain.model.Shop
import com.offerpoint.pos.domain.repository.RefundRepository
import com.offerpoint.pos.domain.repository.SalesRepository
import com.offerpoint.pos.domain.usecase.ReceiptReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RefundHistoryState(val loading: Boolean = true, val rows: List<RefundDetails> = emptyList(), val historyError: String? = null,
    val reference: String = "", val lookingUp: Boolean = false, val sale: SaleDetails? = null, val lookupError: String? = null)

/** A receipt lookup is read-only. Refund posting stays in the dedicated confirmation screen. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RefundHistoryViewModel(private val refunds: RefundRepository, private val sales: SalesRepository, private val shop: Shop, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(RefundHistoryState(reference = saved["receiptReference"] ?: ""))
    val state = mutable.asStateFlow()
    private val retry = MutableStateFlow(0)
    private var lookupJob: Job? = null
    init {
        viewModelScope.launch {
            retry.flatMapLatest { refunds.observe(shop.id).map { Triple(false, it, null as String?) }
                .onStart { mutable.update { it.copy(loading = true, historyError = null) } }
                .catch { e -> if (e is CancellationException) throw e; emit(Triple(false, emptyList(), e.message ?: "Saved returns could not be read.")) }
            }.collect { (loading, rows, error) -> mutable.update { it.copy(loading = loading, rows = rows, historyError = error) } }
        }
    }
    fun retry() { retry.value += 1 }
    fun reference(value: String) {
        lookupJob?.cancel()
        saved["receiptReference"] = value
        mutable.update { it.copy(reference = value, lookingUp = false, sale = null, lookupError = null) }
    }
    fun scanned(value: String) { reference(value); find() }
    fun find() {
        lookupJob?.cancel()
        val reference = try { ReceiptReference.originalSale(state.value.reference, shop.code) }
        catch (e: IllegalArgumentException) { mutable.update { it.copy(lookingUp = false, sale = null, lookupError = e.message) }; return }
        mutable.update { it.copy(lookingUp = true, sale = null, lookupError = null) }
        lookupJob = viewModelScope.launch {
            try {
                val sale = sales.findSaleByReceipt(shop.id, reference)
                require(sale == null || (sale.summary.shopId == shop.id && sale.summary.receiptNumber == reference)) { "The receipt lookup did not match this shop and number." }
                mutable.update { it.copy(lookingUp = false, sale = sale, lookupError = if (sale == null) "No saved sale matches $reference in ${shop.name}. Check the receipt number and shop." else null) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(lookingUp = false, sale = null, lookupError = e.message ?: "The receipt could not be found.") }
            }
        }
    }
}
