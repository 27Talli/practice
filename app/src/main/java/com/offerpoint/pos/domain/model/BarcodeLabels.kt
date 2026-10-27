package com.offerpoint.pos.domain.model

data class CostCodeSettings(val shopId: Long, val alphabet: String)

/** The purchase cost belongs to this printed label's snapshot, never to the selling-price calculation. */
data class InternalBarcodeLabel(
    val barcodeId: Long,
    val shopId: Long,
    val productId: Long,
    val sku: String,
    val name: String,
    val variant: String,
    val barcodeValue: String,
    val costCode: String,
    val purchaseCostPaise: Long,
    val alphabetSnapshot: String,
    val createdAt: Long,
)

data class ScannedProduct(val product: Product, val label: InternalBarcodeLabel? = null)

class BarcodeLookupException(override val message: String) : IllegalArgumentException(message)
