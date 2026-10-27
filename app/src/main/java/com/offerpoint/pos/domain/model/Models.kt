package com.offerpoint.pos.domain.model

data class Shop(val id: Long, val code: String, val name: String, val address: String)

/** Shared product identity joined with the selected shop's prices and stock. */
data class Product(
    val id: Long,
    val shopId: Long,
    val sku: String,
    val name: String,
    val category: String,
    val variant: String,
    val manufacturerBarcode: String?,
    val purchaseCostPaise: Long,
    val sellingPricePaise: Long,
    val quantity: Long,
    val reorderLevel: Long = 5,
)

data class RegisteredProduct(val id: Long, val sku: String, val name: String, val category: String, val variant: String)

data class ProductDraft(
    val name: String,
    val category: String,
    val variant: String = "",
    val manufacturerBarcode: String? = null,
    val purchaseCostPaise: Long,
    val sellingPricePaise: Long,
    val openingQuantity: Long = 0,
    val reorderLevel: Long = 5,
)

class ValidationException(val field: String, override val message: String) : IllegalArgumentException(message)
class BarcodeConflictException(val barcode: String) : IllegalArgumentException("Barcode $barcode is already registered. Use Find existing product to associate it with this shop.")
class StockException(override val message: String) : IllegalArgumentException(message)
