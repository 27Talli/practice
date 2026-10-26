package com.offerpoint.pos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.CheckoutRequest
import com.offerpoint.pos.domain.model.Product
import com.offerpoint.pos.domain.model.ProductDraft
import com.offerpoint.pos.domain.model.RegisteredProduct
import com.offerpoint.pos.domain.model.SaleDetails
import com.offerpoint.pos.domain.model.SaleSummary
import com.offerpoint.pos.domain.model.SoldItem
import com.offerpoint.pos.domain.model.TodaySales
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ViewModel>()
    private val catalog = FakeProducts()
    private val sales = FakeSales()
    private val product = Product(7, 1, "OP-000007", "Lipstick", "Cosmetics", "Rose", null, 300, 500, 10)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        catalog.rows += product
    }

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun model(shop: Long = 1, handle: SavedStateHandle = SavedStateHandle()) =
        CheckoutViewModel(catalog, sales, shop, handle) { "stable-request" }.also { models += it }

    @Test fun repeatedPayTapsPostOneSaleWhileTheFirstSaveIsPending() = runTest(dispatcher) {
        sales.gate = CompletableDeferred()
        val model = model()
        model.add(product)
        model.exactCash()
        model.pay()
        model.pay()
        assertTrue(model.state.value.saving)
        assertFalse(model.canLeaveCheckout())
        runCurrent()
        assertEquals(1, sales.requests.size)
        sales.gate!!.complete(Unit)
        runCurrent()
        assertEquals(81L, model.state.value.completedSaleId)
        model.pay()
        runCurrent()
        assertEquals(1, sales.requests.size)
    }

    @Test fun aCommittedSaleIsRecoveredWhenTheInitialSaveResponseFails() = runTest(dispatcher) {
        sales.failAfterCommit = true
        val model = model()
        model.add(product)
        model.exactCash()
        model.pay()
        runCurrent()
        assertEquals(81L, model.state.value.completedSaleId)
        assertFalse(model.state.value.recoveryRequired)
        assertEquals(1, sales.requests.size)
    }

    @Test fun aConfirmedRollbackCanBeEditedAndRetriedUsingTheSameRequestIdentity() = runTest(dispatcher) {
        sales.failBeforeCommit = true
        val model = model()
        model.add(product)
        model.exactCash()
        model.pay()
        runCurrent()
        assertFalse(model.state.value.locked)
        assertEquals(null, model.state.value.completedSaleId)
        assertTrue(model.editLine(product.id, "2", "0"))
        model.exactCash()
        sales.failBeforeCommit = false
        model.pay()
        runCurrent()
        assertEquals(listOf("stable-request", "stable-request"), sales.requests.map { it.requestId })
        assertEquals(listOf(1L, 2L), sales.requests.map { it.lines.single().quantity })
        assertEquals(81L, model.state.value.completedSaleId)
    }

    @Test fun stalePreSubmitLifecycleStateFindsTheLaterCompletedSaleWithoutPostingAgain() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val first = model(handle = handle)
        first.add(product)
        first.exactCash()
        val staleSnapshot = handle.keys().associateWith { handle.get<Any?>(it) }
        // The last lifecycle snapshot is deliberately older than the final cart/payment.
        assertTrue(first.editLine(product.id, "2", "0"))
        first.exactCash()
        first.pay()
        runCurrent()
        assertEquals(1, sales.requests.size)
        val restored = model(handle = SavedStateHandle(staleSnapshot))
        assertTrue(restored.state.value.locked)
        runCurrent()
        assertEquals(81L, restored.state.value.completedSaleId)
        restored.pay()
        runCurrent()
        assertEquals(1, sales.requests.size)
    }

    @Test fun anUnresolvedCheckoutStaysFrozenUntilItsSavedResultCanBeChecked() = runTest(dispatcher) {
        sales.failBeforeCommit = true
        sales.failLookup = true
        val model = model()
        model.add(product)
        model.exactCash()
        model.pay()
        runCurrent()
        assertTrue(model.state.value.recoveryRequired)
        model.add(product)
        model.setCashReceived("99.00")
        model.remove(product.id)
        model.pay()
        assertEquals(1L, model.state.value.cart.single().quantity)
        assertEquals("5.00", model.state.value.cashReceived)
        assertEquals(1, sales.requests.size)
        sales.failLookup = false
        model.recoverPending()
        model.recoverPending()
        runCurrent()
        assertFalse(model.state.value.locked)
        sales.failBeforeCommit = false
        model.pay()
        runCurrent()
        assertEquals(81L, model.state.value.completedSaleId)
        assertEquals("stable-request", sales.requests.last().requestId)
    }

    @Test fun restorationOfAPendingSaveRecoversItsSaleUsingTheOriginalShopAndRequest() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val first = model(handle = handle)
        first.add(product)
        first.exactCash()
        sales.gate = CompletableDeferred()
        first.pay()
        val pendingSnapshot = handle.keys().associateWith { handle.get<Any?>(it) }
        runCurrent()
        sales.gate!!.complete(Unit)
        runCurrent()
        val restored = model(handle = SavedStateHandle(pendingSnapshot))
        runCurrent()
        assertEquals(81L, restored.state.value.completedSaleId)
        assertTrue(sales.lookups.all { it.first == 1L && it.second == "stable-request" })
        assertEquals(1, sales.requests.size)
    }

    @Test fun aProductFromAnotherShopCannotEnterOrBePostedWithTheCurrentCart() = runTest(dispatcher) {
        val model = model(shop = 2)
        model.add(product)
        assertTrue(model.state.value.cart.isEmpty())
        val otherShopProduct = product.copy(shopId = 2)
        catalog.rows += otherShopProduct
        model.add(otherShopProduct)
        model.exactCash()
        model.pay()
        runCurrent()
        assertEquals(2L, sales.requests.single().shopId)
        assertEquals(81L, model.state.value.completedSaleId)
    }

    @Test fun aRestoredCartKeepsItsCapturedPriceUntilTheCashierExplicitlyRefreshesIt() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val first = model(handle = handle)
        first.add(product)
        first.exactCash()
        val snapshot = handle.keys().associateWith { handle.get<Any?>(it) }
        catalog.rows[0] = product.copy(sellingPricePaise = 700)
        val restored = model(handle = SavedStateHandle(snapshot))
        runCurrent()
        assertEquals(500L, restored.state.value.cart.single().unitPricePaise)
        restored.refreshCart()
        runCurrent()
        assertEquals(700L, restored.state.value.cart.single().unitPricePaise)
        assertEquals(null, restored.state.value.changePaise)
        assertTrue(restored.state.value.cashError != null)
    }

    @Test fun invalidQuantityOrDiscountCannotReplaceAValidCartLine() = runTest(dispatcher) {
        val model = model()
        model.add(product)
        assertFalse(model.editLine(product.id, "0", "0"))
        assertFalse(model.editLine(product.id, "11", "0"))
        assertFalse(model.editLine(product.id, "1", "5.01"))
        assertEquals(1L, model.state.value.cart.single().quantity)
        assertEquals(0L, model.state.value.cart.single().discountPaise)
        assertTrue(sales.requests.isEmpty())
    }

    private class FakeProducts : ProductRepository {
        val rows = mutableListOf<Product>()
        override fun observeProducts(shopId: Long, query: String): Flow<List<Product>> = flowOf(rows.filter { it.shopId == shopId })
        override suspend fun getProduct(shopId: Long, productId: Long): Product? = rows.firstOrNull { it.shopId == shopId && it.id == productId }
        override suspend fun findByBarcode(barcode: String): RegisteredProduct? = null
        override suspend fun createProduct(shopId: Long, draft: ProductDraft): Long = error("Not used by checkout")
        override suspend fun updateProduct(shopId: Long, productId: Long, draft: ProductDraft) = error("Not used by checkout")
        override suspend fun associateProduct(shopId: Long, productId: Long, purchaseCostPaise: Long, sellingPricePaise: Long, openingQuantity: Long) = error("Not used by checkout")
    }

    private class FakeSales : SalesRepository {
        val requests = mutableListOf<CheckoutRequest>()
        val lookups = mutableListOf<Pair<Long, String>>()
        val completed = mutableMapOf<String, SaleDetails>()
        var gate: CompletableDeferred<Unit>? = null
        var failBeforeCommit = false
        var failAfterCommit = false
        var failLookup = false
        override suspend fun completeSale(request: CheckoutRequest): SaleDetails {
            requests += request
            if (failBeforeCommit) error("Database write failed")
            gate?.await()
            val totals = CheckoutMath.validate(request)
            val details = SaleDetails(
                SaleSummary(81, request.shopId, "OP-S-000001", totals.totalPaise, 1_800_000_000_000),
                totals.subtotalPaise, totals.discountPaise, totals.orderDiscountPaise, request.amountPaidPaise,
                CheckoutMath.change(totals.totalPaise, request.amountPaidPaise), "CASH",
                request.lines.map { SoldItem(it.productId, "SKU", "Saved item", "", it.quantity, it.unitPricePaise, 300, it.lineDiscountPaise,
                    Math.subtractExact(Math.multiplyExact(it.unitPricePaise, it.quantity), it.lineDiscountPaise)) },
            )
            completed[request.requestId] = details
            if (failAfterCommit) error("Response lost after commit")
            return details
        }
        override suspend fun getSale(shopId: Long, saleId: Long): SaleDetails? = completed.values.firstOrNull { it.summary.shopId == shopId && it.summary.id == saleId }
        override suspend fun findCompletedRequest(shopId: Long, requestId: String): SaleDetails? {
            lookups += shopId to requestId
            if (failLookup) error("Database unavailable")
            return completed[requestId]?.takeIf { it.summary.shopId == shopId }
        }
        override fun observeSales(shopId: Long): Flow<List<SaleSummary>> = flowOf(emptyList())
        override fun observeTodaySales(shopId: Long): Flow<TodaySales> = flowOf(TodaySales(0, 0))
    }
}
