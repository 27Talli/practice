package com.offerpoint.pos.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.Product
import com.offerpoint.pos.domain.model.ProductDraft
import com.offerpoint.pos.domain.model.RegisteredProduct
import com.offerpoint.pos.domain.model.Shop
import com.offerpoint.pos.domain.repository.InventoryRepository
import com.offerpoint.pos.domain.repository.ProductRepository
import com.offerpoint.pos.domain.repository.ShopRepository
import com.offerpoint.pos.domain.usecase.Money
import com.offerpoint.pos.domain.usecase.ProductValidation
import com.offerpoint.pos.domain.usecase.Quantity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** ViewModels depend on domain interfaces; Room and Android context stay outside the UI. */
class PosViewModelFactory<T : ViewModel>(private val create: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}

internal fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank)
    ?: "The operation could not be completed. Please try again."

data class AppState(
    val loading: Boolean = true,
    val shops: List<Shop> = emptyList(),
    val selectedShop: Shop? = null,
    val error: String? = null,
    val selectionError: String? = null,
    val selecting: Boolean = false,
    val databaseStatus: String = "Checking local database…",
)

class AppViewModel(private val repository: ShopRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private val selectedEvents = Channel<Unit>(Channel.BUFFERED)
    val shopSelected = selectedEvents.receiveAsFlow()
    private var startup: Job? = null

    init { initialize() }

    fun initialize() {
        if (startup?.isActive == true) return
        startup = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                repository.initialize()
                val databaseStatus = repository.databaseStatus()
                combine(repository.observeShops(), repository.observeSelectedShop()) { shops, selected ->
                    shops to selected
                }.collect { (shops, selected) ->
                    mutableState.update {
                        it.copy(loading = false, error = null, shops = shops, selectedShop = selected, databaseStatus = databaseStatus)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    fun selectShop(id: Long) {
        if (mutableState.value.selecting) return
        mutableState.update { it.copy(selecting = true, selectionError = null) }
        viewModelScope.launch {
            try {
                repository.selectShop(id)
                // Read our known shop synchronously to avoid navigation racing a Room Flow emission.
                mutableState.update { it.copy(selectedShop = it.shops.firstOrNull { shop -> shop.id == id }) }
                selectedEvents.send(Unit)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(selectionError = error.userMessage()) }
            } finally {
                mutableState.update { it.copy(selecting = false) }
            }
        }
    }
}

data class ProductListState(val loading: Boolean = true, val products: List<Product> = emptyList(), val error: String? = null)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ProductsViewModel(private val repository: ProductRepository, private val shopId: Long) : ViewModel() {
    private val query = MutableStateFlow("")
    private val retry = MutableStateFlow(0)
    private val mutableState = MutableStateFlow(ProductListState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(query.debounce(180), retry) { search, _ -> search }
                .flatMapLatest { search ->
                    repository.observeProducts(shopId, search)
                        .map { ProductListState(loading = false, products = it) }
                        .onStart { emit(ProductListState()) }
                        .catch { error ->
                            if (error is CancellationException) throw error
                            emit(ProductListState(loading = false, error = error.userMessage()))
                        }
                }.collect { mutableState.value = it }
        }
    }

    fun search(value: String) { query.value = value }
    fun retry() { retry.value += 1 }
}

data class ProductFormInput(
    val name: String,
    val category: String,
    val variant: String,
    val barcode: String,
    val cost: String,
    val price: String,
    val opening: String,
)

data class ProductEditorState(
    val loading: Boolean = false,
    val original: Product? = null,
    val saving: Boolean = false,
    val finding: Boolean = false,
    val matched: RegisteredProduct? = null,
    val notice: String? = null,
    val error: String? = null,
    val loadError: String? = null,
    val saved: Boolean = false,
)

class ProductEditorViewModel(
    private val repository: ProductRepository,
    private val shopId: Long,
    private val productId: Long?,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProductEditorState(loading = productId != null))
    val state = mutableState.asStateFlow()

    init { if (productId != null) load() }

    fun load() {
        val id = productId ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, loadError = null) }
            try {
                val product = repository.getProduct(shopId, id)
                mutableState.update {
                    it.copy(loading = false, original = product, loadError = if (product == null) "This product is not associated with the selected shop." else null)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, loadError = error.userMessage()) }
            }
        }
    }

    fun findExisting(barcode: String) {
        if (mutableState.value.finding || mutableState.value.saving) return
        if (barcode.isBlank()) {
            mutableState.update { it.copy(error = "Enter an existing barcode or SKU first.") }
            return
        }
        mutableState.update { it.copy(finding = true, error = null, notice = null, matched = null) }
        viewModelScope.launch {
            try {
                val match = repository.findByBarcode(barcode.trim())
                when {
                    match == null -> mutableState.update { it.copy(notice = "No matching product. You can create a new product with this barcode.") }
                    repository.getProduct(shopId, match.id) != null -> mutableState.update {
                        it.copy(error = "${match.name} (${match.sku}) already belongs to this shop. Open it from Products to edit it.")
                    }
                    else -> mutableState.update { it.copy(matched = match, notice = "Existing product found. Set this shop’s prices and opening stock to associate it.") }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error.userMessage()) }
            } finally {
                mutableState.update { it.copy(finding = false) }
            }
        }
    }

    fun clearMatch() { mutableState.update { it.copy(matched = null, notice = null, error = null) } }

    fun save(input: ProductFormInput, associatedProductId: Long?) {
        if (mutableState.value.saving || mutableState.value.saved || mutableState.value.loading) return
        // Guard synchronously: two taps may arrive before a launched coroutine starts.
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val draft = ProductValidation.validate(
                    ProductDraft(
                        name = input.name,
                        category = input.category,
                        variant = input.variant,
                        manufacturerBarcode = input.barcode,
                        purchaseCostPaise = Money.parse(input.cost),
                        sellingPricePaise = Money.parse(input.price),
                        openingQuantity = if (productId == null) Quantity.parse(input.opening) else 0,
                    ),
                )
                when {
                    productId != null -> repository.updateProduct(shopId, productId, draft)
                    associatedProductId != null -> repository.associateProduct(shopId, associatedProductId, draft.purchaseCostPaise, draft.sellingPricePaise, draft.openingQuantity)
                    else -> repository.createProduct(shopId, draft)
                }
                mutableState.update { it.copy(saved = true) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error.userMessage()) }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }
}

data class StockAdjustmentState(
    val saving: Boolean = false,
    val error: String? = null,
    val completion: Int = 0,
    /** Distinguishes a retained ViewModel from one newly created after process death. */
    val sessionId: String = UUID.randomUUID().toString(),
)

class StockAdjustmentViewModel(private val repository: InventoryRepository, private val shopId: Long) : ViewModel() {
    private val mutableState = MutableStateFlow(StockAdjustmentState())
    val state: StateFlow<StockAdjustmentState> = mutableState.asStateFlow()

    fun clearError() { mutableState.update { it.copy(error = null) } }

    fun adjust(productId: Long, quantity: String, decrease: Boolean, reason: String) {
        if (mutableState.value.saving) return
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val amount = Quantity.parse(quantity)
                require(amount > 0) { "Adjustment quantity must be greater than zero." }
                require(reason.isNotBlank()) { "Enter a reason for this stock adjustment." }
                repository.adjustStock(shopId, productId, if (decrease) -amount else amount, reason.trim())
                mutableState.update { it.copy(completion = it.completion + 1) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error.userMessage()) }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }
}
