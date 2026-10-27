package com.offerpoint.pos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.BarcodeLabelRepository
import com.offerpoint.pos.domain.repository.ProductRepository
import com.offerpoint.pos.domain.repository.SalesRepository
import com.offerpoint.pos.domain.usecase.CheckoutMath
import com.offerpoint.pos.ui.screens.CheckoutViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BarcodeCheckoutViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ViewModel>()
    private val products = FakeProducts()
    private val labels = FakeLabels()
    private val sales = FakeSales()
    private val product = Product(7, 1, "OP-000007", "Lipstick", "Cosmetics", "Rose", "0012345", 300, 500, 10)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        products.register("0012345", product)
    }

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun model(handle: SavedStateHandle = SavedStateHandle()) =
        CheckoutViewModel(products, sales, 1, handle, labels) { "barcode-checkout" }.also(models::add)

    @Test fun closingAStaffLookupCannotAutomaticallyAddItsStillVisibleBarcodeToTheCart() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val model = model(handle)
        model.pauseScanningForStaff()
        assertTrue(model.state.value.scanPaused)
        assertEquals(true, handle.get<Boolean>("scanPaused"))
        model.scanBarcode("0012345", "CODE_128")
        runCurrent()
        assertTrue(model.state.value.cart.isEmpty()); assertEquals(0, products.lookups)
        model.scanNextItem()
        model.scanBarcode("0012345", "CODE_128")
        runCurrent()
        assertEquals(1L,model.state.value.cart.single().quantity)
        assertTrue(sales.requests.isEmpty())
    }

    @Test fun aScannedProductIsAddedOnceAndNeedsExplicitRearming() = runTest(dispatcher) {
        val model = model()
        model.scanBarcode("0012345", "CODE_128")
        model.scanBarcode("0012345", "CODE_128")
        assertTrue(model.state.value.scanProcessing)
        assertFalse(model.canLeaveCheckout())
        runCurrent()
        assertEquals(1L, model.state.value.cart.single().quantity)
        assertTrue(model.state.value.scanPaused)
        assertFalse(model.state.value.scanProcessing)
        assertEquals(1, products.lookups)
        model.scanBarcode("OP-000007", "CODE_128")
        runCurrent()
        assertEquals(1, products.lookups)
        model.scanNextItem()
        assertEquals(1L, model.state.value.scanResetKey)
        model.scanBarcode("0012345", "CODE_128")
        runCurrent()
        assertEquals(2L, model.state.value.cart.single().quantity)
        assertEquals(2, products.lookups)
    }

    @Test fun unknownCodesPauseWithoutCreatingProductsOrChangingTheCart() = runTest(dispatcher) {
        val model = model()
        model.add(product)
        model.scanBarcode("not-registered", "CODE_128")
        runCurrent()
        assertEquals(1L, model.state.value.cart.single().quantity)
        assertTrue(model.state.value.error!!.contains("not registered"))
        assertTrue(model.state.value.scanPaused)
        assertTrue(sales.requests.isEmpty())
        assertEquals(0, labels.creations)
    }

    @Test fun outOfStockBarcodeCannotEnterTheCart() = runTest(dispatcher) {
        products.register("0012345", product.copy(quantity = 0))
        val model = model()
        model.scanBarcode("0012345", "CODE_128")
        runCurrent()
        assertTrue(model.state.value.cart.isEmpty())
        assertTrue(model.state.value.error!!.contains("out of stock"))
    }

    @Test fun anInternalLabelFromAnotherShopCannotBeSoldHere() = runTest(dispatcher) {
        products.register("OP-000007~LBBBB", product)
        labels.byValue["OP-000007~LBBBB"] = label(shopId = 2)
        val model = model()
        model.scanBarcode("OP-000007~LBBBB", "CODE_128")
        runCurrent()
        assertTrue(model.state.value.cart.isEmpty())
        assertTrue(model.state.value.error!!.contains("another shop"))
    }

    @Test fun aLateCameraResultCannotChangeAnInFlightPayment() = runTest(dispatcher) {
        sales.gate = CompletableDeferred()
        val model = model()
        model.add(product)
        model.exactCash()
        model.pay()
        model.scanBarcode("0012345", "CODE_128")
        runCurrent()
        assertEquals(0, products.lookups)
        assertEquals(1L, model.state.value.cart.single().quantity)
        assertEquals(1, sales.requests.size)
        sales.gate!!.complete(Unit)
        runCurrent()
        model.scanBarcode("0012345", "CODE_128")
        runCurrent()
        assertEquals(0, products.lookups)
        assertEquals(81L, model.state.value.completedSaleId)
    }

    @Test fun lookupBlocksPaymentAndManualAddsUntilItsSingleResultFinishes() = runTest(dispatcher) {
        products.gate = CompletableDeferred()
        val model = model()
        model.add(product)
        model.exactCash()
        model.scanBarcode("0012345", "CODE_128")
        model.pay()
        model.add(product)
        model.scanBarcode("0012345", "CODE_128")
        runCurrent()
        assertTrue(model.state.value.scanProcessing)
        assertTrue(sales.requests.isEmpty())
        assertEquals(1L, model.state.value.cart.single().quantity)
        products.gate!!.complete(Unit)
        runCurrent()
        assertEquals(2L, model.state.value.cart.single().quantity)
        assertEquals(1, products.lookups)
        assertFalse(model.state.value.locked)
    }

    @Test fun secretLabelCostIsAStaffSnapshotAndNeverOverridesTheSellingPrice() = runTest(dispatcher) {
        products.register("OP-000007~LBBBB", product)
        labels.byValue["OP-000007~LBBBB"] = label()
        val model = model()
        model.scanBarcode("OP-000007~LBBBB", "CODE_128")
        runCurrent()
        assertEquals("LBBBB", model.state.value.scannedCostCode)
        assertEquals(10000L, model.state.value.scannedPurchaseCostPaise)
        assertEquals(500L, model.state.value.cart.single().unitPricePaise)
        model.exactCash()
        model.pay()
        runCurrent()
        assertEquals(500L, sales.requests.single().lines.single().unitPricePaise)
        assertEquals(0, labels.creations)
    }

    @Test fun restoringAScannedCartDoesNotRearmACodeStillInTheCameraView() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val first = model(handle)
        first.scanBarcode("0012345", "CODE_128")
        runCurrent()
        val snapshot = handle.keys().associateWith { handle.get<Any?>(it) }
        val restored = model(SavedStateHandle(snapshot))
        runCurrent()
        assertTrue(restored.state.value.scanPaused)
        restored.scanBarcode("0012345", "CODE_128")
        runCurrent()
        assertEquals(1L, restored.state.value.cart.single().quantity)
        assertEquals(1, products.lookups)
        restored.scanNextItem()
        restored.scanBarcode("0012345", "CODE_128")
        runCurrent()
        assertEquals(2L, restored.state.value.cart.single().quantity)
    }

    private fun label(shopId: Long = 1) = InternalBarcodeLabel(
        2, shopId, 7, "OP-000007", "Lipstick", "Rose", "OP-000007~LBBBB", "LBBBB", 10000, "BLACKHORSE", 1,
    )

    private class FakeProducts : ProductRepository {
        private val registry = mutableMapOf<String, Product>()
        var lookups = 0
        var gate: CompletableDeferred<Unit>? = null
        fun register(code: String, product: Product) { registry[code] = product; registry[product.sku] = product }
        override fun observeProducts(shopId: Long, query: String): Flow<List<Product>> = flowOf(registry.values.distinctBy { it.id }.filter { it.shopId == shopId })
        override suspend fun getProduct(shopId: Long, productId: Long): Product? = registry.values.firstOrNull { it.shopId == shopId && it.id == productId }
        override suspend fun findByBarcode(barcode: String): RegisteredProduct? {
            lookups++
            gate?.await()
            return registry[barcode]?.let { RegisteredProduct(it.id, it.sku, it.name, it.category, it.variant) }
        }
        override suspend fun findProductForBarcode(shopId: Long, barcode: String): Product? = registry[barcode]?.takeIf { it.shopId == shopId }
        override suspend fun createProduct(shopId: Long, draft: ProductDraft): Long = error("Scanner must not create products")
        override suspend fun updateProduct(shopId: Long, productId: Long, draft: ProductDraft) = error("Not used")
        override suspend fun associateProduct(shopId: Long, productId: Long, purchaseCostPaise: Long, sellingPricePaise: Long, openingQuantity: Long, reorderLevel: Long) = error("Scanner must not associate shops")
    }

    private class FakeLabels : BarcodeLabelRepository {
        val byValue = mutableMapOf<String, InternalBarcodeLabel>()
        var creations = 0
        override suspend fun getOrCreateCostCodeSettings(shopId: Long) = CostCodeSettings(shopId, "BLACKHORSE")
        override suspend fun updateCostCodeAlphabet(shopId: Long, alphabet: String) = error("Not used")
        override suspend fun createLabel(shopId: Long, productId: Long): InternalBarcodeLabel { creations++; error("Scanner must not create labels") }
        override suspend fun getLabel(shopId: Long, barcodeId: Long): InternalBarcodeLabel? = byValue.values.firstOrNull { it.shopId == shopId && it.barcodeId == barcodeId }
        override suspend fun findLabelByValue(value: String): InternalBarcodeLabel? = byValue[value]
    }

    private class FakeSales : SalesRepository {
        val requests = mutableListOf<CheckoutRequest>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun completeSale(request: CheckoutRequest): SaleDetails {
            requests += request
            gate?.await()
            val totals = CheckoutMath.validate(request)
            return SaleDetails(SaleSummary(81, request.shopId, "OP-S-000001", totals.totalPaise, 1), totals.subtotalPaise,
                totals.discountPaise, totals.orderDiscountPaise, request.amountPaidPaise,
                CheckoutMath.change(totals.totalPaise, request.amountPaidPaise), "CASH", emptyList())
        }
        override suspend fun getSale(shopId: Long, saleId: Long): SaleDetails? = null
        override suspend fun findCompletedRequest(shopId: Long, requestId: String): SaleDetails? = null
        override fun observeSales(shopId: Long): Flow<List<SaleSummary>> = flowOf(emptyList())
        override fun observeTodaySales(shopId: Long): Flow<TodaySales> = flowOf(TodaySales(0, 0))
    }
}
