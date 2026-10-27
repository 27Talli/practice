package com.offerpoint.pos.domain.usecase

import com.offerpoint.pos.domain.model.BarcodeLookupException
import com.offerpoint.pos.domain.model.ScannedProduct
import com.offerpoint.pos.domain.repository.BarcodeLabelRepository
import com.offerpoint.pos.domain.repository.ProductRepository

class LookupScannedProduct(private val products: ProductRepository, private val labels: BarcodeLabelRepository) {
    suspend operator fun invoke(shopId: Long, value: String, format: String, requireStock: Boolean = true): ScannedProduct {
        val raw = value.trim()
        if (raw.isEmpty() || raw.length > 256 || raw.any { it.isISOControl() }) {
            throw BarcodeLookupException("The barcode could not be read. Try scanning it again.")
        }
        // UPC-A may be decoded as EAN-13 with a leading zero. Never guess between conflicting registry entries.
        val candidates = when {
            format == "EAN_13" && raw.length == 13 && raw.startsWith('0') && raw.all(Char::isDigit) -> listOf(raw, raw.drop(1))
            format == "UPC_A" && raw.length == 12 && raw.all(Char::isDigit) -> listOf(raw, "0$raw")
            else -> listOf(raw)
        }
        val matches = candidates.mapNotNull { candidate -> products.findByBarcode(candidate)?.let { candidate to it } }
        if (matches.map { it.second.id }.distinct().size > 1) {
            throw BarcodeLookupException("Conflicting UPC/EAN barcode records were found. Correct the product barcodes before checkout.")
        }
        val match = matches.firstOrNull()
            ?: throw BarcodeLookupException("Barcode not registered. Add it to a product before scanning it at checkout.")
        val label = labels.findLabelByValue(match.first)
        if (label != null && label.shopId != shopId) {
            throw BarcodeLookupException("This internal label belongs to another shop. Choose the correct shop before scanning it.")
        }
        val product = products.findProductForBarcode(shopId, match.first)
            ?: throw BarcodeLookupException("This product is not active in the selected shop. Check Products before selling it.")
        if (requireStock && product.quantity <= 0) throw BarcodeLookupException("${product.name} is out of stock.")
        return ScannedProduct(product, label)
    }
}
