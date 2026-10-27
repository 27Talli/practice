package com.offerpoint.pos.domain.usecase

/** Scanning fills a draft field; registration and uniqueness checks still happen at Save. */
object CapturedProductBarcode {
    fun validate(value: String): String {
        val barcode = value.trim()
        require(barcode.isNotEmpty() && barcode.length <= 128 && barcode.none(Char::isISOControl)) {
            "Scan a product barcode of at most 128 characters without control characters."
        }
        return barcode
    }
}
