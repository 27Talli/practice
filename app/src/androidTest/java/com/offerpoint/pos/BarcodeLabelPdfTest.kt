package com.offerpoint.pos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.domain.model.InternalBarcodeLabel
import com.offerpoint.pos.ui.labels.LabelPdfExporter
import com.offerpoint.pos.ui.scanner.LuminanceFrame
import com.offerpoint.pos.ui.scanner.RetailBarcodeDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android PDF generation, rendered barcode readability, pagination and safe sharing. */
@RunWith(AndroidJUnit4::class)
class BarcodeLabelPdfTest {
    @Test fun exportedPdfCanBeReadByTheScannerAndSharedThroughThePrivateProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val label = InternalBarcodeLabel(1, 1, 1, "OP-000001", "Rose lipstick", "Red",
            "OP-000001~LAHHB", "LAHHB", 12_550, "BLACKHORSE", 1)
        val file = LabelPdfExporter.write(context, "Offer Point", label, 13)
        try {
            assertTrue(file.length() > 0)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.files", uri.authority)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                val header = ByteArray(4)
                assertEquals(4, input.read(header))
                assertEquals("%PDF", header.toString(Charsets.US_ASCII))
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(2, renderer.pageCount)
                    renderer.openPage(0).use { page ->
                        // Rasterize the first label at 288 dpi, including its quiet zone.
                        val bitmap = Bitmap.createBitmap(1090, 432, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            val matrix = Matrix().apply { setScale(4f, 4f); postTranslate(-104f, -104f) }
                            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            val luminance = ByteArray(pixels.size) { index ->
                                val pixel = pixels[index]
                                ((Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000).toByte()
                            }
                            val decoded = RetailBarcodeDecoder().decode(LuminanceFrame(luminance, bitmap.width, bitmap.height))
                            assertEquals(label.barcodeValue, decoded?.value)
                            assertEquals("CODE_128", decoded?.format)
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } finally { file.delete() }
    }
}
