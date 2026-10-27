package com.offerpoint.pos

import com.offerpoint.pos.domain.model.BarcodeLookupException
import com.offerpoint.pos.domain.model.CostCodeSettings
import com.offerpoint.pos.domain.model.InternalBarcodeLabel
import com.offerpoint.pos.domain.model.Product
import com.offerpoint.pos.domain.model.ProductDraft
import com.offerpoint.pos.domain.model.RegisteredProduct
import com.offerpoint.pos.domain.repository.BarcodeLabelRepository
import com.offerpoint.pos.domain.repository.ProductRepository
import com.offerpoint.pos.domain.usecase.LookupScannedProduct
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BarcodeLookupTest {
    private val item = Product(1, 1, "OP-000001", "Lipstick", "Cosmetics", "Rose", "0000123456789", 12550, 20000, 3)
    private val products = FakeProducts()
    private val labels = FakeLabels()
    private val lookup = LookupScannedProduct(products, labels)

    private fun register(value: String, product: Product = item) {
        products.registry[value] = RegisteredProduct(product.id, product.sku, product.name, product.category, product.variant)
        products.active[product.shopId to value] = product
    }

    @Test fun manufacturerBarcodeKeepsLeadingZerosAndUsesStoredShopPrices() = runBlocking {
        register("0000123456789")
        val found = lookup(1, "0000123456789", "CODE_128")
        assertEquals(item, found.product)
        assertNull(found.label)
        assertEquals(20000L, found.product.sellingPricePaise)
    }

    @Test fun generatedCostBarcodeReturnsItsSnapshotWithoutChangingCurrentPrices() = runBlocking {
        val value = "OP-000001~LAHHB"
        register(value)
        val label = InternalBarcodeLabel(9, 1, 1, item.sku, item.name, item.variant, value, "LAHHB", 12550, "BLACKHORSE", 1)
        labels.rows[value] = label
        assertEquals(label, lookup(1, value, "CODE_128").label)
        assertEquals(20000L, lookup(1, value, "CODE_128").product.sellingPricePaise)
    }

    @Test fun unknownBarcodeDoesNotBecomeAProductOrAnUnregisteredCostLabel() = runBlocking {
        expectFailure { lookup(1, "OP-000001~LAHHB", "CODE_128") }
        assertTrue(products.active.isEmpty())
    }

    @Test fun inactiveUnassociatedAndOutOfStockProductsCannotBeAdded() = runBlocking {
        register("known")
        products.active.clear()
        expectFailure { lookup(1, "known", "CODE_128") }
        register("known", item.copy(quantity = 0))
        expectFailure { lookup(1, "known", "CODE_128") }
    }

    @Test fun anotherShopsCostLabelIsRejectedEvenWhenTheProductIsShared() = runBlocking {
        register("cost-label", item.copy(shopId = 2))
        labels.rows["cost-label"] = InternalBarcodeLabel(9, 1, 1, item.sku, item.name, item.variant, "cost-label", "LAHHB", 12550, "BLACKHORSE", 1)
        expectFailure { lookup(2, "cost-label", "CODE_128") }
    }

    @Test fun upcAndLeadingZeroEanAliasesResolveOnlyAnUnambiguousRegistryItem() = runBlocking {
        register("012345678905")
        assertEquals(item, lookup(1, "0012345678905", "EAN_13").product)
        products.registry.clear(); products.active.clear()
        register("0012345678905")
        assertEquals(item, lookup(1, "012345678905", "UPC_A").product)
    }

    @Test fun conflictingUpcEanRegistryEntriesNeverPickOneArbitrarily() = runBlocking {
        register("012345678905")
        register("0012345678905", item.copy(id = 2, sku = "OP-000002"))
        expectFailure { lookup(1, "0012345678905", "EAN_13") }
        expectFailure { lookup(1, "012345678905", "UPC_A") }
        // Code 128 text does not have the optical UPC/EAN equivalence.
        assertEquals(item, lookup(1, "012345678905", "CODE_128").product)
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block() } catch (_: BarcodeLookupException) { return }
        fail("Expected barcode lookup to reject this value")
    }

    private class FakeProducts : ProductRepository {
        val registry = mutableMapOf<String, RegisteredProduct>()
        val active = mutableMapOf<Pair<Long, String>, Product>()
        override fun observeProducts(shopId: Long, query: String): Flow<List<Product>> = flowOf(active.values.filter { it.shopId == shopId })
        override suspend fun getProduct(shopId: Long, productId: Long): Product? = active.values.firstOrNull { it.shopId == shopId && it.id == productId }
        override suspend fun findByBarcode(barcode: String) = registry[barcode]
        override suspend fun findProductForBarcode(shopId: Long, barcode: String) = active[shopId to barcode]
        override suspend fun createProduct(shopId: Long, draft: ProductDraft): Long = error("Unexpected write")
        override suspend fun updateProduct(shopId: Long, productId: Long, draft: ProductDraft) = error("Unexpected write")
        override suspend fun associateProduct(shopId: Long, productId: Long, purchaseCostPaise: Long, sellingPricePaise: Long, openingQuantity: Long, reorderLevel: Long) = error("Unexpected write")
    }

    private class FakeLabels : BarcodeLabelRepository {
        val rows = mutableMapOf<String, InternalBarcodeLabel>()
        override suspend fun findLabelByValue(value: String) = rows[value]
        override suspend fun getLabel(shopId: Long, barcodeId: Long) = rows.values.firstOrNull { it.shopId == shopId && it.barcodeId == barcodeId }
        override suspend fun getOrCreateCostCodeSettings(shopId: Long): CostCodeSettings = error("Unexpected write")
        override suspend fun updateCostCodeAlphabet(shopId: Long, alphabet: String) = error("Unexpected write")
        override suspend fun createLabel(shopId: Long, productId: Long): InternalBarcodeLabel = error("Unexpected write")
    }
}
