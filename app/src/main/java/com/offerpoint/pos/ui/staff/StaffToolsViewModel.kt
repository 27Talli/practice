package com.offerpoint.pos.ui.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.*
import com.offerpoint.pos.domain.usecase.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class StaffToolsState(
    val open: Boolean = false,
    val query: String = "",
    val products: List<Product> = emptyList(),
    val loading: Boolean = false,
    val productError: String? = null,
    val lookupLoading: Boolean = false,
    val selected: Product? = null,
    val scanning: Boolean = false,
    val error: String? = null,
    val hasSale: Boolean = false,
    val savedSale: Boolean = false,
    val profit: SaleProfit? = null,
    val profitLoading: Boolean = false,
    val profitError: String? = null,
)

/** Read-only staff tools. This class has no sale, stock or product write path. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class StaffToolsViewModel(private val products: ProductRepository, private val labels: BarcodeLabelRepository, private val shopId: Long) : ViewModel() {
    private val mutable = MutableStateFlow(StaffToolsState())
    val state = mutable.asStateFlow()
    private val query = MutableStateFlow<Pair<Boolean, String>>(false to "")
    private var lookup: Job? = null
    private var profitJob: Job? = null
    private var listJob: Job? = null

    fun open(cart: List<CheckoutLine>? = null, orderDiscount: String = "0", sale: SaleDetails? = null) {
        if (state.value.open) return
        mutable.value = StaffToolsState(open = true, hasSale = sale != null || !cart.isNullOrEmpty(), savedSale = sale != null,
            profitLoading = sale != null || !cart.isNullOrEmpty())
        listJob = viewModelScope.launch {
            query.debounce(150).flatMapLatest { (_, text) ->
                products.observeProducts(shopId, text).map { rows -> rows to null as String? }
                    .onStart { mutable.update { it.copy(loading = true) } }
                    .catch { e -> if (e is CancellationException) throw e; emit(emptyList<Product>() to message(e)) }
            }.collect { (rows, error) -> mutable.update { it.copy(products = rows, loading = false, productError = error) } }
        }
        query.value = true to ""
        val lines = cart?.toList()
        profitJob = viewModelScope.launch {
            try {
                val result = when {
                    sale != null -> { require(sale.summary.shopId == shopId) { "This sale belongs to another shop." }; CalculateSaleProfit.saved(sale) }
                    !lines.isNullOrEmpty() -> CalculateSaleProfit.estimate(products, shopId, lines, Money.parse(orderDiscount.ifBlank { "0" }))
                    else -> null
                }
                mutable.update { it.copy(profit = result, profitLoading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(profit = null, profitLoading = false, profitError = message(e)) }
            }
        }
    }

    fun close() {
        lookup?.cancel(); profitJob?.cancel(); listJob?.cancel()
        query.value = false to ""
        mutable.value = StaffToolsState()
    }

    fun search(value: String) {
        if (!state.value.open) return
        lookup?.cancel()
        mutable.update { it.copy(query = value, selected = null, lookupLoading = false, error = null) }
        query.value = true to value
    }

    fun scan(open: Boolean) {
        if (!state.value.open) return
        lookup?.cancel()
        mutable.update { it.copy(scanning = open, lookupLoading = false, error = null) }
    }

    fun scanned(value: String, format: String) {
        if (!state.value.open || !state.value.scanning) return
        mutable.update { it.copy(scanning = false, selected = null, lookupLoading = true, error = null) }
        lookup?.cancel()
        lookup = viewModelScope.launch {
            try {
                val match = LookupScannedProduct(products, labels)(shopId, value, format, requireStock = false)
                mutable.update { it.copy(selected = match.product, lookupLoading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(error = message(e), lookupLoading = false) }
            }
        }
    }

    fun select(product: Product) {
        if (!state.value.open) return
        lookup?.cancel()
        mutable.update { it.copy(selected = null, lookupLoading = true, error = null) }
        lookup = viewModelScope.launch {
            try {
                require(product.shopId == shopId) { "Choose a product from this shop." }
                val current = requireNotNull(products.getProduct(shopId, product.id)) { "This product is no longer active in this shop." }
                require(current.shopId == shopId)
                mutable.update { it.copy(selected = current, lookupLoading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(error = message(e), lookupLoading = false) }
            }
        }
    }

    private fun message(e: Throwable) = if (e is ArithmeticException) "These costs or quantities exceed the supported money range." else e.message ?: "Staff details could not be loaded."
}
