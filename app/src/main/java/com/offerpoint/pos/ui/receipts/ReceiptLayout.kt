package com.offerpoint.pos.ui.receipts

import android.graphics.Paint
import android.graphics.Typeface
import android.print.PrintAttributes
import com.offerpoint.pos.domain.model.ReceiptDocument
import com.offerpoint.pos.domain.model.ReceiptLineStyle
import com.offerpoint.pos.domain.model.ReceiptPaper
import kotlin.math.roundToInt

data class ReceiptPageSpec(val width: Int, val height: Int, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(width >= 120 && height >= 240 && listOf(left, top, right, bottom).all { it >= 0 }) { "Choose a larger paper size." }
        require(width - left - right >= 90 && height - top - bottom >= 180) { "The printer margins leave too little space for a receipt." }
    }
    companion object {
        fun forPaper(paper: ReceiptPaper): ReceiptPageSpec = when (paper) {
            ReceiptPaper.A4 -> ReceiptPageSpec(595, 842, 28f, 28f, 28f, 28f)
            ReceiptPaper.ROLL_58 -> ReceiptPageSpec(164, 720, 8f, 10f, 8f, 10f)
            ReceiptPaper.ROLL_80 -> ReceiptPageSpec(227, 720, 10f, 10f, 10f, 10f)
        }
        fun fromPrint(attributes: PrintAttributes): ReceiptPageSpec {
            val size = requireNotNull(attributes.mediaSize) { "Choose a paper size in the print dialog." }
            val margins = attributes.minMargins ?: PrintAttributes.Margins.NO_MARGINS
            fun points(mils: Int) = mils * 72f / 1000f
            return ReceiptPageSpec(points(size.widthMils).roundToInt(), points(size.heightMils).roundToInt(),
                points(margins.leftMils), points(margins.topMils), points(margins.rightMils), points(margins.bottomMils))
        }
    }
}

data class ReceiptDrawText(val text: String, val x: Float, val baseline: Float, val size: Float, val bold: Boolean)
data class ReceiptPage(val text: List<ReceiptDrawText>)
data class ReceiptLayout(val spec: ReceiptPageSpec, val pages: List<ReceiptPage>)

/** One layout engine for PDF export and Android printing. All prices arrive as exact formatted text. */
object ReceiptPaginator {
    fun layout(receipt: ReceiptDocument, spec: ReceiptPageSpec, checkCancelled: () -> Unit = {}): ReceiptLayout {
        val width = spec.width - spec.left - spec.right
        val normalSize = if (spec.width < 300) 8.5f else 10f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun setStyle(size: Float, bold: Boolean) { paint.textSize = size; paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
        fun wrap(text: String): List<String> {
            val result = mutableListOf<String>()
            var remaining = text.trim()
            while (remaining.isNotEmpty()) {
                checkCancelled()
                var count = paint.breakText(remaining, true, width, null)
                require(count > 0) { "The receipt text cannot fit this paper size." }
                if (count < remaining.length) {
                    if (remaining[count - 1].isHighSurrogate()) count--
                    val space = remaining.lastIndexOf(' ', count - 1)
                    if (space > 0) count = space
                }
                require(count > 0) { "The receipt text cannot fit this paper size." }
                result.add(remaining.take(count).trimEnd())
                remaining = remaining.drop(count).trimStart()
            }
            return result.ifEmpty { listOf("") }
        }
        val header = mutableListOf<ReceiptDrawText>()
        var headerY = spec.top
        fun header(text: String, size: Float, bold: Boolean) {
            setStyle(size, bold)
            for (line in wrap(text)) {
                header.add(ReceiptDrawText(line, spec.left, headerY - paint.fontMetrics.ascent, size, bold))
                headerY += paint.fontSpacing + 3f
            }
        }
        if (receipt.demo) header("DEMO - NOT A REAL SALE", normalSize, true)
        header(receipt.shopName, normalSize + 3f, true)
        header(receipt.shopAddress, normalSize, false)
        header("SALE RECEIPT", normalSize, true)
        header("Receipt: ${receipt.receiptNumber}", normalSize, false)
        header(receipt.soldAt, normalSize, false)
        val contentTop = headerY + 10f
        val contentBottom = spec.height - spec.bottom - 22f
        require(contentBottom - contentTop >= 50f) { "Choose taller paper to fit the receipt header and totals." }
        val pages = mutableListOf<ReceiptPage>()
        var current = header.toMutableList()
        var y = contentTop
        fun newPage() { pages.add(ReceiptPage(current.toList())); current = header.toMutableList(); y = contentTop }
        fun draw(text: String, size: Float, bold: Boolean, alignRight: Boolean = false) {
            setStyle(size, bold)
            for (line in wrap(text)) {
                checkCancelled()
                val height = paint.fontSpacing + 4f
                if (y + height > contentBottom) newPage()
                val x = if (alignRight) spec.width - spec.right - paint.measureText(line) else spec.left
                current.add(ReceiptDrawText(line, x, y - paint.fontMetrics.ascent, size, bold))
                y += height
            }
        }
        for (line in receipt.lines) {
            checkCancelled()
            val bold = line.style != ReceiptLineStyle.NORMAL
            val size = if (line.style == ReceiptLineStyle.TOTAL) normalSize + 1f else normalSize
            setStyle(size, bold)
            val value = line.value
            if (value != null && paint.measureText(line.text) + paint.measureText(value) + 12f <= width) {
                val height = paint.fontSpacing + 4f
                if (y + height > contentBottom) newPage()
                val baseline = y - paint.fontMetrics.ascent
                current.add(ReceiptDrawText(line.text, spec.left, baseline, size, bold))
                current.add(ReceiptDrawText(value, spec.width - spec.right - paint.measureText(value), baseline, size, bold))
                y += height
            } else {
                draw(line.text, size, bold)
                if (value != null) draw(value, size, bold, alignRight = true)
            }
            if (line.style == ReceiptLineStyle.TOTAL) y += 5f
        }
        pages.add(ReceiptPage(current.toList()))
        return ReceiptLayout(spec, pages)
    }
}
