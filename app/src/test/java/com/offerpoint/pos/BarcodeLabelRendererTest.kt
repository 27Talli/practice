package com.offerpoint.pos

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.offerpoint.pos.domain.model.InternalBarcodeLabel
import com.offerpoint.pos.ui.labels.LabelBarcodePattern
import com.offerpoint.pos.ui.labels.LabelExportText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BarcodeLabelRendererTest {
    @Test fun actualCode128BarsDecodeToTheExactRegisteredCodedValue() {
        val value = "OP-000001~LAHHB~1"
        val bars = LabelBarcodePattern.modules(value)
        val quiet = LabelBarcodePattern.QUIET_MODULES
        val width = (bars.size + quiet * 2) * 3
        val height = 90
        val pixels = ByteArray(width * height) { 0xff.toByte() }
        for (index in bars.indices) if (bars[index]) {
            for (row in 0 until height) for (column in 0 until 3) {
                pixels[row * width + (index + quiet) * 3 + column] = 0
            }
        }
        val source = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
        val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.CODE_128),
            DecodeHintType.TRY_HARDER to true,
        ))
        assertEquals(value, result.text)
        assertEquals(BarcodeFormat.CODE_128, result.barcodeFormat)
    }

    @Test fun longLabelsUseOneColumnWithoutShrinkingBarcodeModulesToThinLines() {
        assertEquals(2, LabelBarcodePattern.columnsFor("OP-000001~LAHHB"))
        val longValue = "OP-${Long.MAX_VALUE}~" + "A".repeat(19) + "~${Long.MAX_VALUE}"
        assertEquals(1, LabelBarcodePattern.columnsFor(longValue))
        assertThrows(IllegalArgumentException::class.java) { LabelBarcodePattern.columnsFor("A".repeat(80)) }
    }

    @Test fun emptyControlAndUnsupportedUnicodeBarcodesAreRejectedBeforeRendering() {
        listOf("", "BAD\nCODE", "OP-😀", "X".repeat(81)).forEach {
            assertThrows(IllegalArgumentException::class.java) { LabelBarcodePattern.modules(it) }
        }
    }

    @Test fun exportedTextCarriesOnlyCustomerLabelFieldsAndNeverThePrivateKeyOrCost() {
        val label = InternalBarcodeLabel(1, 1, 1, "OP-000001", "Rose lipstick", "Red", "OP-000001~LAHHB",
            "LAHHB", 7_654_321, "BLACKHORSE", 1)
        val content = LabelExportText.content("Offer Point", label)
        val lines = LabelExportText.lines("Offer Point", label)
        assertEquals(listOf("Offer Point", "Rose lipstick", "Red", "OP-000001", "OP-000001~LAHHB"), lines)
        assertEquals(label.barcodeValue, content.barcodeValue)
        assertFalse(lines.any { it.contains("BLACKHORSE") || it.contains("7654321") || it.contains("76543.21") || it.contains("Rs.") })
    }

    @Test fun demoMarkerIsInPrintContentEvenWhenProductNameLooksLikeARealProduct() {
        val label = InternalBarcodeLabel(1, 1, 1, "OP-000001", "Rose lipstick", "Red", "OP-000001~LAHHB",
            "LAHHB", 12550, "BLACKHORSE", 1)
        val content = LabelExportText.content("Offer Point", label, isDemo = true)
        assertEquals("DEMO · Offer Point", content.shopName)
        assertEquals("Rose lipstick", content.name)
        assertEquals(label.barcodeValue, content.barcodeValue)
    }
}
