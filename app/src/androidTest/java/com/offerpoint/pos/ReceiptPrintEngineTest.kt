package com.offerpoint.pos

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offerpoint.pos.ui.receipts.ReceiptPrintEngine
import com.offerpoint.pos.ui.receipts.ReceiptLayoutResult
import com.offerpoint.pos.ui.receipts.ReceiptWriteResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReceiptPrintEngineTest {
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun attributes(media: PrintAttributes.MediaSize) = PrintAttributes.Builder().setMediaSize(media)
        .setMinMargins(PrintAttributes.Margins(100, 100, 100, 100)).setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME).build()

    @Test fun printLayoutTracksPaperChangesAndWritesOnlyRequestedPages() {
        val adapter = ReceiptPrintEngine(ReceiptAndroidFixtures.receipt(50))
        val file = File.createTempFile("receipt-spool-", ".pdf", ApplicationProvider.getApplicationContext<Context>().cacheDir)
        try {
            val a4 = attributes(PrintAttributes.MediaSize.ISO_A4)
            val narrow = attributes(PrintAttributes.MediaSize("receipt58", "58 mm", 2283, 10000))
            val a4Count = layout(adapter, a4)
            val narrowCount = layout(adapter, narrow)
            assertTrue(narrowCount > a4Count)
            val latch = CountDownLatch(1)
            val error = AtomicReference<String?>()
            val ranges = AtomicReference<Array<out PageRange>>()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { destination ->
                main {
                    adapter.write(arrayOf(PageRange(1, 1)), destination, CancellationSignal(), object : ReceiptWriteResult {
                        override fun onWriteFinished(pages: Array<out PageRange>) { ranges.set(pages); latch.countDown() }
                        override fun onWriteFailed(message: CharSequence?) { error.set(message?.toString() ?: "Write failed"); latch.countDown() }
                        override fun onWriteCancelled() { error.set("Unexpected cancellation"); latch.countDown() }
                    })
                }
                assertTrue(latch.await(15, TimeUnit.SECONDS)); assertNull(error.get())
            }
            assertEquals(1, ranges.get().single().start); assertEquals(1, ranges.get().single().end)
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { assertEquals(1, it.pageCount) }
        } finally { main { adapter.finish() }; file.delete() }
    }

    @Test fun cancellingLayoutAndWritingIsReportedAsCancellation() {
        val adapter = ReceiptPrintEngine(ReceiptAndroidFixtures.receipt())
        val file = File.createTempFile("receipt-cancel-", ".pdf", ApplicationProvider.getApplicationContext<Context>().cacheDir)
        try {
            val signal = CancellationSignal().apply { cancel() }
            val latch = CountDownLatch(1)
            val outcome = AtomicReference<String>()
            main {
                adapter.layout(null, attributes(PrintAttributes.MediaSize.ISO_A4), signal, object : ReceiptLayoutResult {
                    override fun onLayoutCancelled() { outcome.set("cancelled"); latch.countDown() }
                    override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) { outcome.set("finished"); latch.countDown() }
                    override fun onLayoutFailed(error: CharSequence?) { outcome.set("failed"); latch.countDown() }
                })
            }
            assertTrue(latch.await(15, TimeUnit.SECONDS)); assertEquals("cancelled", outcome.get())
            layout(adapter, attributes(PrintAttributes.MediaSize.ISO_A4))
            val writeLatch = CountDownLatch(1)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { destination ->
                main {
                    adapter.write(arrayOf(PageRange.ALL_PAGES), destination, signal, object : ReceiptWriteResult {
                        override fun onWriteCancelled() { outcome.set("write-cancelled"); writeLatch.countDown() }
                        override fun onWriteFinished(pages: Array<out PageRange>) { outcome.set("finished"); writeLatch.countDown() }
                        override fun onWriteFailed(error: CharSequence?) { outcome.set("failed"); writeLatch.countDown() }
                    })
                }
                assertTrue(writeLatch.await(15, TimeUnit.SECONDS)); assertEquals("write-cancelled", outcome.get())
            }
            assertEquals(0L, file.length())
        } finally { main { adapter.finish() }; file.delete() }
    }

    private fun layout(adapter: ReceiptPrintEngine, attributes: PrintAttributes): Int {
        val latch = CountDownLatch(1)
        val count = AtomicReference<Int>()
        val error = AtomicReference<String?>()
        main {
            adapter.layout(null, attributes, CancellationSignal(), object : ReceiptLayoutResult {
                override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) { count.set(info.pageCount); latch.countDown() }
                override fun onLayoutFailed(message: CharSequence?) { error.set(message?.toString() ?: "Layout failed"); latch.countDown() }
                override fun onLayoutCancelled() { error.set("Unexpected cancellation"); latch.countDown() }
            })
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS)); assertNull(error.get())
        return count.get()
    }
}
