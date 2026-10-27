package com.offerpoint.pos.ui.receipts

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.offerpoint.pos.domain.model.ReceiptDocument
import com.offerpoint.pos.domain.model.ReceiptPaper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

interface ReceiptLayoutResult {
    fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean)
    fun onLayoutCancelled()
    fun onLayoutFailed(error: CharSequence?)
}

interface ReceiptWriteResult {
    fun onWriteFinished(pages: Array<out PageRange>)
    fun onWriteCancelled()
    fun onWriteFailed(error: CharSequence?)
}

/** Real Android PDF work behind a small callback boundary; no inaccessible framework constructors in tests. */
class ReceiptPrintEngine(private val receipt: ReceiptDocument) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    @Volatile private var layout: ReceiptLayout? = null

    fun layout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal,
               callback: ReceiptLayoutResult) {
        if (finished.get()) { callback.onLayoutCancelled(); return }
        layout = null
        executor.execute {
            try {
                checkCancelled(cancellationSignal)
                val plan = ReceiptPaginator.layout(receipt, ReceiptPageSpec.fromPrint(newAttributes)) { checkCancelled(cancellationSignal) }
                main.post {
                    if (finished.get() || cancellationSignal.isCanceled) callback.onLayoutCancelled()
                    else {
                        layout = plan
                        callback.onLayoutFinished(PrintDocumentInfo.Builder("${receipt.receiptNumber}.pdf")
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(plan.pages.size).build(), oldAttributes != newAttributes)
                    }
                }
            } catch (_: OperationCanceledException) { main.post { callback.onLayoutCancelled() } }
            catch (error: Exception) { main.post { callback.onLayoutFailed(error.message ?: "The receipt could not be laid out.") } }
        }
    }

    fun write(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal, callback: ReceiptWriteResult) {
        if (finished.get()) { callback.onWriteCancelled(); return }
        val plan = layout
        if (plan == null) { callback.onWriteFailed("Choose a valid paper size first."); return }
        executor.execute {
            try {
                checkCancelled(cancellationSignal)
                val selected = plan.pages.indices.filter { index -> pages.any { index in it.start..it.end } }
                require(selected.isNotEmpty()) { "No receipt pages were selected." }
                // Own a duplicate descriptor; Android retains ownership of its supplied descriptor.
                ParcelFileDescriptor.dup(destination.fileDescriptor).use { duplicate ->
                    ParcelFileDescriptor.AutoCloseOutputStream(duplicate).use { output ->
                        ReceiptPdfExporter.write(plan, output, selected) { checkCancelled(cancellationSignal) }
                    }
                }
                main.post {
                    if (finished.get() || cancellationSignal.isCanceled) callback.onWriteCancelled()
                    else callback.onWriteFinished(ranges(selected))
                }
            } catch (_: OperationCanceledException) { main.post { callback.onWriteCancelled() } }
            catch (error: Exception) { main.post { callback.onWriteFailed(error.message ?: "The receipt could not be written.") } }
        }
    }

    fun finish() { finished.set(true); layout = null; executor.shutdownNow() }

    private fun checkCancelled(signal: CancellationSignal) {
        if (finished.get() || signal.isCanceled || Thread.currentThread().isInterrupted) throw OperationCanceledException()
    }

    private fun ranges(indices: List<Int>): Array<PageRange> {
        val result = mutableListOf<PageRange>()
        var first = indices.first()
        var last = first
        for (index in indices.drop(1)) {
            if (index == last + 1) last = index else { result.add(PageRange(first, last)); first = index; last = index }
        }
        result.add(PageRange(first, last))
        return result.toTypedArray()
    }
}

/** Android owns its callbacks. This adapter only forwards them to the tested PDF/printing engine. */
class ReceiptPrintAdapter(receipt: ReceiptDocument) : PrintDocumentAdapter() {
    private val engine = ReceiptPrintEngine(receipt)
    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal,
                          callback: LayoutResultCallback, extras: Bundle?) {
        engine.layout(oldAttributes, newAttributes, cancellationSignal, object : ReceiptLayoutResult {
            override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) = callback.onLayoutFinished(info, changed)
            override fun onLayoutCancelled() = callback.onLayoutCancelled()
            override fun onLayoutFailed(error: CharSequence?) = callback.onLayoutFailed(error)
        })
    }
    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
        engine.write(pages, destination, cancellationSignal, object : ReceiptWriteResult {
            override fun onWriteFinished(pages: Array<out PageRange>) = callback.onWriteFinished(pages)
            override fun onWriteCancelled() = callback.onWriteCancelled()
            override fun onWriteFailed(error: CharSequence?) = callback.onWriteFailed(error)
        })
    }
    override fun onFinish() = engine.finish()
}

object ReceiptPrinting {
    fun print(context: Context, receipt: ReceiptDocument, paper: ReceiptPaper) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            ?: throw IllegalStateException("Android printing is unavailable on this device. Share the PDF instead.")
        val media = when (paper) {
            ReceiptPaper.A4 -> PrintAttributes.MediaSize.ISO_A4
            ReceiptPaper.ROLL_58 -> PrintAttributes.MediaSize("receipt_58", "58 mm receipt", 2283, 10000)
            ReceiptPaper.ROLL_80 -> PrintAttributes.MediaSize("receipt_80", "80 mm receipt", 3150, 10000)
        }
        val margins = if (paper == ReceiptPaper.A4) PrintAttributes.Margins(389, 389, 389, 389) else PrintAttributes.Margins(112, 139, 112, 139)
        manager.print("${if (receipt.demo) "DEMO " else ""}${receipt.receiptNumber}", ReceiptPrintAdapter(receipt),
            PrintAttributes.Builder().setMediaSize(media).setMinMargins(margins).setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME).build())
    }
}
