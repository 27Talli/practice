package com.offerpoint.pos.ui.labels

import com.google.zxing.oned.Code128Writer
import com.offerpoint.pos.domain.model.InternalBarcodeLabel

/** Device-independent barcode bars with an explicit ten-module quiet zone. */
object LabelBarcodePattern {
    const val QUIET_MODULES = 10

    fun modules(value: String): BooleanArray {
        require(value.isNotEmpty() && value.length <= 80 && value.all { it.code in 32..126 }) {
            "This label cannot be represented as a printable Code 128 barcode."
        }
        return Code128Writer().encode(value)
    }

    fun columnsFor(value: String): Int {
        val count = modules(value).size + QUIET_MODULES * 2
        // At least three printer dots per module at 300 dpi (0.72 PDF points).
        if (count * 0.72f <= 248.5f) return 2
        require(count * 0.72f <= 527f) { "This barcode needs a wider label than an A4 page can provide." }
        return 1
    }
}

/** Only these fields may be printed/exported. Staff purchase amounts and keys stay off the label. */
object LabelExportText {
    fun content(shopName: String, label: InternalBarcodeLabel, isDemo: Boolean = false) = LabelPrintContent(
        if (isDemo) "DEMO · $shopName" else shopName, label.name, label.variant, label.sku, label.barcodeValue,
    )

    fun lines(shopName: String, label: InternalBarcodeLabel, isDemo: Boolean = false): List<String> =
        content(shopName, label, isDemo).let { listOf(it.shopName, it.name, it.variant, it.sku, it.barcodeValue) }.filter(String::isNotBlank)
}

data class LabelPrintContent(val shopName: String, val name: String, val variant: String, val sku: String, val barcodeValue: String)
