package com.offerpoint.pos.domain.repository

import com.offerpoint.pos.domain.model.Product
import com.offerpoint.pos.domain.model.ProductDraft
import com.offerpoint.pos.domain.model.RegisteredProduct
import com.offerpoint.pos.domain.model.Shop
import kotlinx.coroutines.flow.Flow

interface ShopRepository {
    /** Idempotently seeds the two real shops, and demo fixtures only in a demo build. */
    suspend fun initialize()
    fun observeShops(): Flow<List<Shop>>
    fun observeSelectedShop(): Flow<Shop?>
    suspend fun selectShop(shopId: Long)
    suspend fun databaseStatus(): String
}

interface ProductRepository {
    fun observeProducts(shopId: Long, query: String = ""): Flow<List<Product>>
    suspend fun getProduct(shopId: Long, productId: Long): Product?
    suspend fun findByBarcode(barcode: String): RegisteredProduct?
    suspend fun createProduct(shopId: Long, draft: ProductDraft): Long
    suspend fun updateProduct(shopId: Long, productId: Long, draft: ProductDraft)
    /** Explicitly associates an existing registry product, keeping its shared identity intact. */
    suspend fun associateProduct(shopId: Long, productId: Long, purchaseCostPaise: Long, sellingPricePaise: Long, openingQuantity: Long)
}

interface InventoryRepository {
    suspend fun adjustStock(shopId: Long, productId: Long, delta: Long, reason: String)
}
