package com.offerpoint.pos.ui.labels

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.offerpoint.pos.BuildConfig
import com.offerpoint.pos.domain.model.InternalBarcodeLabel
import java.io.File
import java.util.UUID
import kotlin.math.floor

/** Vector barcodes print sharply; no purchase-cost amount or code key is drawn. */
object LabelPdfExporter {
    fun write(context: Context, shopName: String, label: InternalBarcodeLabel, quantity: Int): File {
        require(quantity in 1..100) { "Choose between 1 and 100 labels." }
        val bars = LabelBarcodePattern.modules(label.barcodeValue)
        val content = LabelExportText.content(shopName, label, BuildConfig.DEMO_MODE)
        val columns = LabelBarcodePattern.columnsFor(label.barcodeValue)
        val pageWidth = 595
        val pageHeight = 842
        val margin = 26f
        val gap = 14f
        val labelWidth = (pageWidth - margin * 2 - gap * (columns - 1)) / columns
        val labelHeight = 108f
        val rows = floor((pageHeight - margin * 2) / (labelHeight + gap)).toInt()
        val perPage = rows * columns
        val document = PdfDocument()
        val folder = File(context.cacheDir, "barcode-labels").apply { check(mkdirs() || isDirectory) }
        val destination = File(folder, "offer-point-labels-${UUID.randomUUID()}.pdf")
        try {
            var completed = 0
            var pageNumber = 1
            while (completed < quantity) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber++).create())
                page.canvas.drawColor(Color.WHITE)
                val count = minOf(perPage, quantity - completed)
                for (index in 0 until count) {
                    val x = margin + (index % columns) * (labelWidth + gap)
                    val y = margin + (index / columns) * (labelHeight + gap)
                    drawLabel(page.canvas, x, y, labelWidth, labelHeight, content, bars)
                }
                document.finishPage(page)
                completed += count
            }
            destination.outputStream().use(document::writeTo)
            return destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            document.close()
        }
    }

    private fun drawLabel(canvas: Canvas, x: Float, y: Float, width: Float, height: Float,
                          label: LabelPrintContent, bars: BooleanArray) {
        val border = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.4f }
        canvas.drawRect(x, y, x + width, y + height, border)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.DEFAULT }
        val usable = width - 16f
        text.textSize = 8f
        text.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(fit(label.shopName, text, usable), x + 8f, y + 12f, text)
        text.textSize = 10f
        canvas.drawText(fit(label.name, text, usable), x + 8f, y + 26f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 7f
        val description = listOf(label.sku, label.variant).filter(String::isNotBlank).joinToString(" · ")
        canvas.drawText(fit(description, text, usable), x + 8f, y + 38f, text)
        val module = usable / (bars.size + LabelBarcodePattern.QUIET_MODULES * 2)
        val ink = Paint().apply { color = Color.BLACK; isAntiAlias = false }
        val start = x + 8f + module * LabelBarcodePattern.QUIET_MODULES
        for (index in bars.indices) if (bars[index]) {
            canvas.drawRect(start + index * module, y + 44f, start + (index + 1) * module, y + 91f, ink)
        }
        // Human-readable coded payload is allowed; actual cost and alphabet never enter export text.
        text.textSize = 6.5f
        val valueWidth = text.measureText(label.barcodeValue)
        if (valueWidth > usable) text.textSize *= usable / valueWidth
        canvas.drawText(label.barcodeValue, x + 8f, y + 102f, text)
    }

    private fun fit(value: String, paint: Paint, width: Float): String {
        if (paint.measureText(value) <= width) return value
        var end = paint.breakText(value, true, (width - paint.measureText("…")).coerceAtLeast(0f), null)
        if (end > 0 && value[end - 1].isHighSurrogate()) end--
        return value.take(end) + "…"
    }
}
