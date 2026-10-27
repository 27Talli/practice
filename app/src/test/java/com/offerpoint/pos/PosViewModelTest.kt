package com.offerpoint.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.Product
import com.offerpoint.pos.domain.model.ProductDraft
import com.offerpoint.pos.domain.model.RegisteredProduct
import com.offerpoint.pos.domain.repository.InventoryRepository
import com.offerpoint.pos.domain.repository.ProductRepository
import com.offerpoint.pos.ui.screens.ProductEditorViewModel
import com.offerpoint.pos.ui.screens.ProductFormInput
import com.offerpoint.pos.ui.screens.StockAdjustmentViewModel
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
class PosViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<ViewModel>()
    private val input = ProductFormInput("Test lipstick", "Cosmetics", "Rose", "0123456", "150.25", "250.50", "4")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun rapidSaveTapsCreateOnlyOneProductEvenBeforeTheCoroutineStarts() = runTest(dispatcher) {
        val repository = FakeProductRepository().apply { creationGate = CompletableDeferred() }
        val model = ProductEditorViewModel(repository, 2, null).also(viewModels::add)
        model.save(input, null)
        model.save(input, null)
        assertTrue(model.state.value.saving)
        runCurrent()
        assertEquals(1, repository.creations.size)
        assertEquals(2L, repository.creations.single().first)
        repository.creationGate!!.complete(55)
        runCurrent()
        assertTrue(model.state.value.saved)
        assertFalse(model.state.value.saving)
    }

    @Test fun failedSaveCanBeRetriedWithTheSameInput() = runTest(dispatcher) {
        val repository = FakeProductRepository().apply { creationFailure = IllegalStateException("Database unavailable") }
        val model = ProductEditorViewModel(repository, 1, null).also(viewModels::add)
        model.save(input, null)
        runCurrent()
        assertEquals("Database unavailable", model.state.value.error)
        assertFalse(model.state.value.saved)
        assertFalse(model.state.value.saving)
        repository.creationFailure = null
        model.save(input, null)
        runCurrent()
        assertTrue(model.state.value.saved)
        assertEquals(2, repository.creations.size)
        assertEquals(repository.creations[0], repository.creations[1])
        assertEquals(15025L, repository.creations[1].second.purchaseCostPaise)
        assertEquals(25050L, repository.creations[1].second.sellingPricePaise)
    }

    @Test fun existingProductIsAssociatedWithTheExplicitShopWithoutCreatingARegistryDuplicate() = runTest(dispatcher) {
        val repository = FakeProductRepository()
        val model = ProductEditorViewModel(repository, 2, null).also(viewModels::add)
        model.save(input, 19)
        runCurrent()
        assertTrue(model.state.value.saved)
        assertEquals(listOf(listOf(2L, 19L, 15025L, 25050L, 4L)), repository.associations)
        assertTrue(repository.creations.isEmpty())
        assertEquals(0, repository.updates)
    }

    @Test fun invalidStockNeverReachesTheRepositoryAndValidStockUsesTheExplicitShop() = runTest(dispatcher) {
        val calls = mutableListOf<StockCall>()
        val repository = object : InventoryRepository {
            override suspend fun adjustStock(shopId: Long, productId: Long, delta: Long, reason: String) {
                calls += StockCall(shopId, productId, delta, reason)
            }
        }
        val model = StockAdjustmentViewModel(repository, 2).also(viewModels::add)
        model.adjust(19, "0", false, "Counted")
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(model.state.value.error!!.contains("greater than zero"))
        model.adjust(19, "5", true, " ")
        runCurrent()
        assertTrue(calls.isEmpty())
        model.adjust(19, "5", true, " Count correction ")
        model.adjust(19, "5", true, " Count correction ")
        runCurrent()
        assertEquals(listOf(StockCall(2, 19, -5, "Count correction")), calls)
        assertEquals(1, model.state.value.completion)
    }

    private data class StockCall(val shopId: Long, val productId: Long, val delta: Long, val reason: String)

    private class FakeProductRepository : ProductRepository {
        val creations = mutableListOf<Pair<Long, ProductDraft>>()
        val associations = mutableListOf<List<Long>>()
        var updates = 0
        var creationGate: CompletableDeferred<Long>? = null
        var creationFailure: Exception? = null
        override fun observeProducts(shopId: Long, query: String): Flow<List<Product>> = flowOf(emptyList())
        override suspend fun getProduct(shopId: Long, productId: Long): Product? = null
        override suspend fun findByBarcode(barcode: String): RegisteredProduct? = null
        override suspend fun findProductForBarcode(shopId: Long, barcode: String): Product? = null
        override suspend fun createProduct(shopId: Long, draft: ProductDraft): Long {
            creations += shopId to draft
            val id = creationGate?.await() ?: 99L
            creationFailure?.let { throw it }
            return id
        }
        override suspend fun updateProduct(shopId: Long, productId: Long, draft: ProductDraft) { updates += 1 }
        override suspend fun associateProduct(shopId: Long, productId: Long, purchaseCostPaise: Long, sellingPricePaise: Long, openingQuantity: Long, reorderLevel: Long) {
            associations += listOf(shopId, productId, purchaseCostPaise, sellingPricePaise, openingQuantity)
        }
    }
}
