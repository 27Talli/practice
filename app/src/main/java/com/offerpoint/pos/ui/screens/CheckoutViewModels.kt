package com.offerpoint.pos.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.CheckoutLine
import com.offerpoint.pos.domain.model.CheckoutRequest
import com.offerpoint.pos.domain.model.CheckoutTotals
import com.offerpoint.pos.domain.model.Product
import com.offerpoint.pos.domain.model.SaleDetails
import com.offerpoint.pos.domain.model.SaleSummary
import com.offerpoint.pos.domain.model.TodaySales
import com.offerpoint.pos.domain.repository.BarcodeLabelRepository
import com.offerpoint.pos.domain.repository.ProductRepository
import com.offerpoint.pos.domain.repository.SalesRepository
import com.offerpoint.pos.domain.usecase.LookupScannedProduct
import com.offerpoint.pos.domain.usecase.CheckoutMath
import com.offerpoint.pos.domain.usecase.Money
import com.offerpoint.pos.domain.usecase.Quantity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class CartItem(
    val productId: Long,
    val sku: String,
    val name: String,
    val variant: String,
    val quantity: Long,
    val unitPricePaise: Long,
    val availableQuantity: Long,
    val discountPaise: Long = 0,
) {
    fun requestLine() = CheckoutLine(productId, quantity, unitPricePaise, discountPaise)
}

data class CheckoutState(
    val productsLoading: Boolean = true,
    val products: List<Product> = emptyList(),
    val productError: String? = null,
    val query: String = "",
    val cart: List<CartItem> = emptyList(),
    val orderDiscount: String = "0.00",
    val cashReceived: String = "",
    val totals: CheckoutTotals? = null,
    val calculationError: String? = null,
    val changePaise: Long? = null,
    val cashError: String? = null,
    val saving: Boolean = false,
    val recovering: Boolean = false,
    val recoveryRequired: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val completedSaleId: Long? = null,
    val scanProcessing: Boolean = false,
    val scanPaused: Boolean = false,
    val scanResetKey: Long = 0,
    val scanMessage: String? = null,
    val scannedCostCode: String? = null,
    val scannedPurchaseCostPaise: Long? = null,
) {
    val paymentLocked: Boolean get() = saving || recovering || refreshing || recoveryRequired || completedSaleId != null
    val locked: Boolean get() = paymentLocked || scanProcessing
}

/** A restored pending checkout is reconciled before the operator may edit or post it again. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CheckoutViewModel(
    private val products: ProductRepository,
    private val sales: SalesRepository,
    private val shopId: Long,
    private val savedState: SavedStateHandle,
    private val barcodeLabels: BarcodeLabelRepository? = null,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val restoringRequest = savedState.get<String>("requestId") != null
    private var requestId: String = savedState["requestId"] ?: newRequestId()
    private var pending: Boolean = savedState["pending"] ?: false
    private var recoveryRunning = false
    private val query = MutableStateFlow(savedState.get<String>("query").orEmpty())
    private val retry = MutableStateFlow(0)
    private val mutableState = MutableStateFlow(restore())
    val state = mutableState.asStateFlow()

    init {
        recalculate()
        persist()
        viewModelScope.launch {
            combine(query.debounce(180), retry) { value, _ -> value }
                .flatMapLatest { value ->
                    products.observeProducts(shopId, value)
                        .map { ProductListState(loading = false, products = it) }
                        .onStart { emit(ProductListState()) }
                        .catch { error ->
                            if (error is CancellationException) throw error
                            emit(ProductListState(loading = false, error = error.userMessage()))
                        }
                }.collect { list ->
                    mutableState.update { it.copy(productsLoading = list.loading, products = list.products, productError = list.error) }
                }
        }
        // A lifecycle snapshot may predate the last tap even if the sale already committed.
        if (restoringRequest && mutableState.value.completedSaleId == null) recoverPending()
    }

    private fun restore(): CheckoutState {
        val previousShop = savedState.get<Long>("shopId")
        if (previousShop != null && previousShop != shopId) {
            pending = false
            requestId = newRequestId()
            return CheckoutState(error = "A cart from a different shop was not restored.")
        }
        val ids = savedState.get<LongArray>("ids") ?: longArrayOf()
        val quantities = savedState.get<LongArray>("quantities") ?: longArrayOf()
        val prices = savedState.get<LongArray>("prices") ?: longArrayOf()
        val available = savedState.get<LongArray>("available") ?: longArrayOf()
        val discounts = savedState.get<LongArray>("discounts") ?: longArrayOf()
        val names = savedState.get<ArrayList<String>>("names") ?: arrayListOf()
        val skus = savedState.get<ArrayList<String>>("skus") ?: arrayListOf()
        val variants = savedState.get<ArrayList<String>>("variants") ?: arrayListOf()
        if (listOf(quantities.size, prices.size, available.size, discounts.size, names.size, skus.size, variants.size).any { it != ids.size }) {
            // Never unlock an uncertain posted request just because a malformed draft cannot display.
            return CheckoutState(recoveryRequired = pending, error = "The saved cart could not be displayed. Check the previous checkout before starting again.")
        }
        return CheckoutState(
            query = query.value,
            cart = ids.indices.map { index -> CartItem(ids[index], skus[index], names[index], variants[index], quantities[index], prices[index], available[index], discounts[index]) },
            orderDiscount = savedState["orderDiscount"] ?: "0.00",
            cashReceived = savedState["cashReceived"] ?: "",
            completedSaleId = savedState["completedSaleId"],
            recovering = restoringRequest,
            scanPaused = savedState["scanPaused"] ?: restoringRequest,
        )
    }

    fun search(value: String) {
        query.value = value
        mutableState.update { it.copy(query = value) }
        savedState["query"] = value
    }

    fun retryProducts() { retry.value += 1 }

    /** Navigation callbacks check live state, including the frame before Compose recomposes. */
    fun canLeaveCheckout(): Boolean = !mutableState.value.locked

    fun add(product: Product) {
        if (!mutableState.value.locked) addProduct(product)
    }

    private fun addProduct(product: Product): Boolean {
        if (product.shopId != shopId) { showError("This product belongs to a different shop."); return false }
        return try {
            require(product.quantity > 0) { "This product is out of stock." }
            val current = mutableState.value.cart
            val existing = current.firstOrNull { it.productId == product.id }
            val quantity = if (existing == null) 1 else Math.addExact(existing.quantity, 1)
            require(quantity <= product.quantity) { "Only ${product.quantity} units are currently in stock." }
            val item = existing?.copy(quantity = quantity, availableQuantity = product.quantity)
                ?: CartItem(product.id, product.sku, product.name, product.variant, 1, product.sellingPricePaise, product.quantity)
            editDraft { it.copy(cart = if (existing == null) current + item else current.map { row -> if (row.productId == product.id) item else row }) }
            true
        } catch (error: Exception) { showError(error.userMessage()); false }
    }

    /** A point-and-scan result adds one unit, then requires an explicit next-item action. */
    fun scanBarcode(value: String, format: String) {
        val current = mutableState.value
        if (current.locked || current.scanPaused) return
        val labels = barcodeLabels
        if (labels == null) { showError("Barcode scanning is not available in this workspace."); return }
        mutableState.update { it.copy(scanProcessing = true, scanPaused = true, scanMessage = null,
            scannedCostCode = null, scannedPurchaseCostPaise = null, error = null) }
        persist()
        viewModelScope.launch {
            try {
                val match = LookupScannedProduct(products, labels)(shopId, value, format)
                // The camera may deliver a result after payment recovery has begun.
                if (!mutableState.value.paymentLocked) {
                    if (addProduct(match.product)) {
                        mutableState.update { it.copy(
                            scanMessage = "${match.product.name} added · ${Money.format(match.product.sellingPricePaise)}",
                            scannedCostCode = match.label?.costCode,
                            scannedPurchaseCostPaise = match.label?.purchaseCostPaise,
                        ) }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError(error.userMessage())
            } finally {
                mutableState.update { it.copy(scanProcessing = false) }
                persist()
            }
        }
    }

    fun scanNextItem() {
        if (mutableState.value.locked) return
        mutableState.update { it.copy(scanPaused = false,
            scanResetKey = if (it.scanResetKey == Long.MAX_VALUE) 0 else it.scanResetKey + 1,
            scanMessage = null, scannedCostCode = null, scannedPurchaseCostPaise = null, error = null) }
        persist()
    }

    fun editLine(productId: Long, quantityText: String, discountText: String): Boolean {
        if (mutableState.value.locked) return false
        return try {
            val item = mutableState.value.cart.firstOrNull { it.productId == productId } ?: return false
            val quantity = Quantity.parse(quantityText)
            require(quantity > 0) { "Quantity must be greater than zero. Use Remove to take an item out of the cart." }
            require(quantity <= item.availableQuantity) { "Only ${item.availableQuantity} units were available. Refresh the cart to check current stock." }
            val discount = Money.parse(discountText.ifBlank { "0" })
            val changed = item.copy(quantity = quantity, discountPaise = discount)
            CheckoutMath.totals(listOf(changed.requestLine()))
            editDraft { state -> state.copy(cart = state.cart.map { if (it.productId == productId) changed else it }) }
            true
        } catch (error: Exception) { showError(error.userMessage()); false }
    }

    fun remove(productId: Long) {
        if (!mutableState.value.locked) editDraft { state -> state.copy(cart = state.cart.filterNot { it.productId == productId }) }
    }

    fun setOrderDiscount(value: String) { if (!mutableState.value.locked) editDraft { it.copy(orderDiscount = value) } }
    fun setCashReceived(value: String) { if (!mutableState.value.locked) editDraft { it.copy(cashReceived = value) } }
    fun exactCash() { mutableState.value.totals?.let { setCashReceived(Money.input(it.totalPaise)) } }

    fun refreshCart() {
        if (mutableState.value.locked || mutableState.value.cart.isEmpty()) return
        mutableState.update { it.copy(refreshing = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val refreshed = mutableState.value.cart.map { item ->
                    val product = products.getProduct(shopId, item.productId)
                        ?: throw IllegalStateException("${item.name} is no longer available in this shop. Remove it from the cart.")
                    item.copy(name = product.name, sku = product.sku, variant = product.variant,
                        unitPricePaise = product.sellingPricePaise, availableQuantity = product.quantity)
                }
                editDraft { it.copy(cart = refreshed, notice = "Cart prices and stock refreshed. Review the total before taking payment.") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError(error.userMessage())
            } finally { mutableState.update { it.copy(refreshing = false) } }
        }
    }

    fun pay() {
        val current = mutableState.value
        if (current.locked) return
        val request = try {
            CheckoutRequest(requestId, shopId, current.cart.map { it.requestLine() },
                Money.parse(current.orderDiscount.ifBlank { "0" }), Money.parse(current.cashReceived)).also(CheckoutMath::validate)
        } catch (error: Exception) { showError(error.userMessage()); return }
        // Persist the exact idempotency key and locked request before launching any database work.
        pending = true
        mutableState.update { it.copy(saving = true, error = null, notice = null) }
        persist()
        viewModelScope.launch {
            try {
                complete(sales.completeSale(request))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reconcile(error.userMessage())
            } finally { mutableState.update { it.copy(saving = false) } }
        }
    }

    fun recoverPending() {
        if (mutableState.value.saving || mutableState.value.scanProcessing || recoveryRunning) return
        recoveryRunning = true
        mutableState.update { it.copy(recovering = true, recoveryRequired = true, error = null) }
        viewModelScope.launch {
            try { reconcile(null) }
            finally { recoveryRunning = false }
        }
    }

    private suspend fun reconcile(failureMessage: String?) {
        try {
            val saved = sales.findCompletedRequest(shopId, requestId)
            if (saved != null) complete(saved)
            else {
                pending = false
                mutableState.update { it.copy(saving = false, recovering = false, recoveryRequired = false, error = failureMessage,
                    notice = if (failureMessage == null) "Cart restored. No sale has been posted for this checkout." else null) }
                persist()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(saving = false, recovering = false, recoveryRequired = true,
                error = "The checkout result could not be checked. Keep this cart unchanged and tap Check previous checkout. ${error.userMessage()}") }
            persist()
        }
    }

    private fun complete(details: SaleDetails) {
        check(details.summary.shopId == shopId) { "The saved sale belongs to a different shop." }
        pending = false
        mutableState.update { it.copy(completedSaleId = details.summary.id, saving = false, recovering = false, recoveryRequired = false, error = null) }
        persist()
    }

    private fun editDraft(transform: (CheckoutState) -> CheckoutState) {
        mutableState.update { transform(it).copy(error = null) }
        recalculate()
        persist()
    }

    private fun recalculate() {
        val current = mutableState.value
        if (current.cart.isEmpty()) {
            mutableState.update { it.copy(totals = null, calculationError = null, changePaise = null, cashError = null) }
            return
        }
        val shortage = current.cart.firstOrNull { it.quantity > it.availableQuantity }
        if (shortage != null) {
            mutableState.update { it.copy(totals = null, calculationError = "${shortage.name} has only ${shortage.availableQuantity} units available. Adjust its quantity or remove it.", changePaise = null, cashError = null) }
            return
        }
        val totals = try {
            CheckoutMath.totals(current.cart.map { it.requestLine() }, Money.parse(current.orderDiscount.ifBlank { "0" }))
        } catch (error: Exception) {
            mutableState.update { it.copy(totals = null, calculationError = error.userMessage(), changePaise = null, cashError = null) }
            return
        }
        var cashError: String? = null
        val change = if (current.cashReceived.isBlank()) null else try {
            CheckoutMath.change(totals.totalPaise, Money.parse(current.cashReceived))
        } catch (error: Exception) { cashError = error.userMessage(); null }
        mutableState.update { it.copy(totals = totals, calculationError = null, changePaise = change, cashError = cashError) }
    }

    private fun showError(message: String) { mutableState.update { it.copy(error = message) } }

    private fun persist() {
        val current = mutableState.value
        savedState["shopId"] = shopId
        savedState["requestId"] = requestId
        savedState["pending"] = pending
        savedState["scanPaused"] = current.scanPaused
        savedState["completedSaleId"] = current.completedSaleId
        savedState["orderDiscount"] = current.orderDiscount
        savedState["cashReceived"] = current.cashReceived
        savedState["ids"] = current.cart.map { it.productId }.toLongArray()
        savedState["quantities"] = current.cart.map { it.quantity }.toLongArray()
        savedState["prices"] = current.cart.map { it.unitPricePaise }.toLongArray()
        savedState["available"] = current.cart.map { it.availableQuantity }.toLongArray()
        savedState["discounts"] = current.cart.map { it.discountPaise }.toLongArray()
        savedState["names"] = ArrayList(current.cart.map { it.name })
        savedState["skus"] = ArrayList(current.cart.map { it.sku })
        savedState["variants"] = ArrayList(current.cart.map { it.variant })
    }
}

data class TodaySalesState(val loading: Boolean = true, val sales: TodaySales? = null, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class TodaySalesViewModel(repository: SalesRepository, shopId: Long) : ViewModel() {
    private val retry = MutableStateFlow(0)
    private val mutableState = MutableStateFlow(TodaySalesState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            retry.flatMapLatest {
                repository.observeTodaySales(shopId).map { TodaySalesState(loading = false, sales = it) }
                    .onStart { emit(TodaySalesState()) }
                    .catch { error -> if (error is CancellationException) throw error; emit(TodaySalesState(loading = false, error = error.userMessage())) }
            }.collect { mutableState.value = it }
        }
    }
    fun retry() { retry.value += 1 }
}

data class SalesHistoryState(val loading: Boolean = true, val sales: List<SaleSummary> = emptyList(), val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class SalesHistoryViewModel(repository: SalesRepository, shopId: Long) : ViewModel() {
    private val retry = MutableStateFlow(0)
    private val mutableState = MutableStateFlow(SalesHistoryState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            retry.flatMapLatest {
                repository.observeSales(shopId).map { SalesHistoryState(loading = false, sales = it) }
                    .onStart { emit(SalesHistoryState()) }
                    .catch { error -> if (error is CancellationException) throw error; emit(SalesHistoryState(loading = false, error = error.userMessage())) }
            }.collect { mutableState.value = it }
        }
    }
    fun retry() { retry.value += 1 }
}

data class SaleDetailsState(val loading: Boolean = true, val sale: SaleDetails? = null, val error: String? = null)

class SaleDetailsViewModel(private val repository: SalesRepository, private val shopId: Long, private val saleId: Long) : ViewModel() {
    private val mutableState = MutableStateFlow(SaleDetailsState())
    val state = mutableState.asStateFlow()
    init { load() }
    fun load() {
        if (!mutableState.value.loading) mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val sale = repository.getSale(shopId, saleId)
                mutableState.value = SaleDetailsState(loading = false, sale = sale,
                    error = if (sale == null) "This sale was not found in the selected shop." else null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = SaleDetailsState(loading = false, error = error.userMessage())
            }
        }
    }
}
