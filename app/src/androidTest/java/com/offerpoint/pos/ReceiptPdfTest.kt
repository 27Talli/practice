package com.offerpoint.pos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.domain.model.ReceiptLine
import com.offerpoint.pos.domain.model.ReceiptPaper
import com.offerpoint.pos.ui.receipts.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ReceiptPdfTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun allPaperSizesProduceReadablePaginatedPdfsAndSafeProviderUris() {
        val receipt = ReceiptAndroidFixtures.receipt()
        for (paper in ReceiptPaper.entries) {
            val plan = ReceiptPaginator.layout(receipt, ReceiptPageSpec.forPaper(paper))
            assertTrue(plan.pages.size > 1)
            val file = ReceiptPdfExporter.write(context, receipt, paper)
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                assertEquals("content", uri.scheme)
                context.contentResolver.openInputStream(uri)!!.use {
                    val header = ByteArray(4); assertEquals(4, it.read(header)); assertEquals("%PDF", header.toString(Charsets.US_ASCII))
                }
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    assertEquals(plan.pages.size, renderer.pageCount)
                    renderer.openPage(0).use { page ->
                        assertEquals(plan.spec.width, page.width); assertEquals(plan.spec.height, page.height)
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            assertTrue(pixels.count { Color.red(it) < 128 } > 500)
                        } finally { bitmap.recycle() }
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun everyPageRepeatsDemoAndIdentityAndAllTextRemainsInsideMargins() {
        val receipt = ReceiptAndroidFixtures.receipt(50)
        for (paper in ReceiptPaper.entries) {
            val plan = ReceiptPaginator.layout(receipt, ReceiptPageSpec.forPaper(paper))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (page in plan.pages) {
                assertTrue(page.text.any { it.text == "DEMO - NOT A REAL SALE" })
                assertTrue(page.text.any { it.text.contains(receipt.receiptNumber) })
                for (line in page.text) {
                    paint.textSize = line.size
                    paint.typeface = if (line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    assertTrue(line.x >= plan.spec.left - 0.1f)
                    assertTrue(line.x + paint.measureText(line.text) <= plan.spec.width - plan.spec.right + 0.1f)
                    assertTrue(line.baseline + paint.fontMetrics.ascent >= plan.spec.top - 0.1f)
                    assertTrue(line.baseline + paint.fontMetrics.descent < plan.spec.height - plan.spec.bottom - 10f)
                }
            }
            val text = plan.pages.flatMap { it.text }.joinToString(" ") { it.text }
            assertFalse(text.contains("98,765.43") || text.contains("9876543") || text.contains("BLACKHORSE"))
            (1..50).forEach { assertTrue(text.contains("OP-${it.toString().padStart(6, '0')}")) }
            assertTrue(text.contains("TOTAL")); assertTrue(text.contains("Rs. 500.00"))
        }
    }

    @Test fun longUnbrokenNamesAndMaximumAmountsWrapWithoutTruncation() {
        val name = "W".repeat(400)
        val amount = "Rs. 92,233,720,368,547,758.07"
        val receipt = ReceiptAndroidFixtures.receipt(1).copy(lines = listOf(ReceiptLine(name), ReceiptLine("TOTAL", amount)))
        val plan = ReceiptPaginator.layout(receipt, ReceiptPageSpec.forPaper(ReceiptPaper.ROLL_58))
        val text = plan.pages.flatMap { it.text }.joinToString("") { it.text }
        assertTrue(text.contains(name)); assertTrue(text.contains(amount.replace(" ", "")) || text.contains(amount))
        assertFalse(text.contains("…"))
    }

    @Test fun cancellationClosesTheDocumentAndDoesNotWriteAPartialPdf() {
        val plan = ReceiptPaginator.layout(ReceiptAndroidFixtures.receipt(), ReceiptPageSpec.forPaper(ReceiptPaper.ROLL_58))
        val output = ByteArrayOutputStream()
        var checks = 0
        try {
            ReceiptPdfExporter.write(plan, output) { if (++checks > 10) throw OperationCanceledException() }
            fail("Expected cancellation")
        } catch (_: OperationCanceledException) { assertEquals(0, output.size()) }
        val retry = ByteArrayOutputStream()
        ReceiptPdfExporter.write(plan, retry)
        assertTrue(retry.size() > 0)
    }

    @Test fun impossiblePrinterMarginsFailInsteadOfClippingTheReceipt() {
        assertThrows(IllegalArgumentException::class.java) { ReceiptPageSpec(164, 720, 80f, 0f, 80f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { ReceiptPageSpec(100, 720, 0f, 0f, 0f, 0f) }
    }
}
