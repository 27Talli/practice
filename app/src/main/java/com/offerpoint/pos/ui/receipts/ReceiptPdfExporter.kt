package com.offerpoint.pos.ui.receipts

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.offerpoint.pos.domain.model.ReceiptDocument
import com.offerpoint.pos.domain.model.ReceiptPaper
import java.io.File
import java.io.OutputStream
import java.util.UUID

object ReceiptPdfExporter {
    fun write(context: Context, receipt: ReceiptDocument, paper: ReceiptPaper): File {
        val plan = ReceiptPaginator.layout(receipt, ReceiptPageSpec.forPaper(paper))
        val folder = File(context.cacheDir, "receipts").apply { check(mkdirs() || isDirectory) }
        // Neither a product name nor an external sale reference becomes a path.
        val file = File(folder, "receipt-${UUID.randomUUID()}.pdf")
        try { file.outputStream().use { write(plan, it) }; return file }
        catch (error: Exception) { file.delete(); throw error }
    }

    fun write(layout: ReceiptLayout, output: OutputStream, selectedPages: List<Int> = layout.pages.indices.toList(), checkCancelled: () -> Unit = {}) {
        require(selectedPages.isNotEmpty() && selectedPages.distinct().size == selectedPages.size && selectedPages.all { it in layout.pages.indices }) { "Choose valid receipt pages." }
        val document = PdfDocument()
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            for (index in selectedPages) {
                checkCancelled()
                val spec = layout.spec
                val page = document.startPage(PdfDocument.PageInfo.Builder(spec.width, spec.height, index + 1).create())
                try {
                    page.canvas.drawColor(Color.WHITE)
                    for (line in layout.pages[index].text) {
                        checkCancelled()
                        paint.textSize = line.size
                        paint.typeface = if (line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        page.canvas.drawText(line.text, line.x, line.baseline, paint)
                    }
                    paint.textSize = 8f
                    paint.typeface = Typeface.DEFAULT
                    page.canvas.drawText("Page ${index + 1} of ${layout.pages.size}", spec.left, spec.height - spec.bottom - 3f, paint)
                } finally { document.finishPage(page) }
            }
            checkCancelled()
            document.writeTo(output)
        } finally { document.close() }
    }
}
