package com.offerpoint.pos.ui.receipts

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

data class ReceiptDrawQr(val reference: String, val x: Float, val top: Float, val moduleSize: Float, val modules: Int) {
    val side: Float get() = moduleSize * modules
    val bottom: Float get() = top + side
}

/** The QR payload is exactly the public receipt number. Four modules of quiet space are included. */
object ReceiptQr {
    fun matrix(reference: String): BitMatrix {
        require(reference.isNotBlank() && reference.length <= 128 && reference.none(Char::isISOControl)) { "This receipt has an invalid number for a QR code." }
        return QRCodeWriter().encode(reference, BarcodeFormat.QR_CODE, 0, 0, mapOf(
            EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        ))
    }

    fun draw(canvas: Canvas, qr: ReceiptDrawQr, checkCancelled: () -> Unit = {}) {
        val matrix = matrix(qr.reference)
        require(matrix.width == qr.modules)
        val paint = Paint().apply { color = Color.WHITE; isAntiAlias = false }
        canvas.drawRect(qr.x, qr.top, qr.x + qr.side, qr.bottom, paint)
        paint.color = Color.BLACK
        for (y in 0 until matrix.height) {
            checkCancelled()
            for (x in 0 until matrix.width) if (matrix[x, y]) {
                canvas.drawRect(qr.x + x * qr.moduleSize, qr.top + y * qr.moduleSize,
                    qr.x + (x + 1) * qr.moduleSize, qr.top + (y + 1) * qr.moduleSize, paint)
            }
        }
    }
}

@Composable
fun ReceiptQrPreview(reference: String) {
    val matrix = remember(reference) { ReceiptQr.matrix(reference) }
    Canvas(Modifier.size(176.dp).background(androidx.compose.ui.graphics.Color.White)
        .semantics { contentDescription = "Receipt QR: $reference" }) {
        // Integral physical pixels keep the preview sharp even on low-density displays.
        val module = kotlin.math.floor(size.minDimension / matrix.width).coerceAtLeast(1f)
        val origin = Offset((size.width - module * matrix.width) / 2, (size.height - module * matrix.height) / 2)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) {
            drawRect(androidx.compose.ui.graphics.Color.Black, origin + Offset(x * module, y * module), Size(module, module))
        }
    }
}
