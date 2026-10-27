package com.offerpoint.pos.ui.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

data class DecodedRetailBarcode(val value: String, val format: String)

/** Tightly packed luminance; independent of Android so actual decoding is covered by JVM tests. */
data class LuminanceFrame(val bytes: ByteArray, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= bytes.size) { "Invalid luminance frame." }
    }

    fun rotateClockwise(degrees: Int): LuminanceFrame {
        val rotation = ((degrees % 360) + 360) % 360
        require(rotation % 90 == 0) { "Rotation must be a multiple of 90 degrees." }
        if (rotation == 0) return this
        val rotated = ByteArray(width * height)
        val outputWidth = if (rotation == 180) width else height
        val outputHeight = if (rotation == 180) height else width
        for (y in 0 until height) for (x in 0 until width) {
            val targetX: Int
            val targetY: Int
            when (rotation) {
                90 -> { targetX = height - 1 - y; targetY = x }
                180 -> { targetX = width - 1 - x; targetY = height - 1 - y }
                else -> { targetX = y; targetY = width - 1 - x }
            }
            rotated[targetY * outputWidth + targetX] = bytes[y * width + x]
        }
        return LuminanceFrame(rotated, outputWidth, outputHeight)
    }
}

/** Copy the Y plane using its actual row/pixel strides and crop, including a non-zero buffer position. */
fun copyLuminancePlane(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    imageWidth: Int,
    imageHeight: Int,
    cropLeft: Int = 0,
    cropTop: Int = 0,
    cropWidth: Int = imageWidth,
    cropHeight: Int = imageHeight,
): LuminanceFrame {
    require(rowStride > 0 && pixelStride > 0 && imageWidth > 0 && imageHeight > 0)
    require(cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0)
    require(cropLeft.toLong() + cropWidth <= imageWidth && cropTop.toLong() + cropHeight <= imageHeight)
    require(cropWidth.toLong() * cropHeight <= Int.MAX_VALUE)
    val source = buffer.duplicate()
    val start = source.position().toLong()
    val last = start + (cropTop.toLong() + cropHeight - 1) * rowStride + (cropLeft.toLong() + cropWidth - 1) * pixelStride
    require(last < source.limit()) { "The camera luminance plane is shorter than its declared crop." }
    val output = ByteArray(cropWidth * cropHeight)
    for (y in 0 until cropHeight) {
        val row = start + (cropTop.toLong() + y) * rowStride + cropLeft.toLong() * pixelStride
        for (x in 0 until cropWidth) output[y * cropWidth + x] = source.get((row + x.toLong() * pixelStride).toInt())
    }
    return LuminanceFrame(output, cropWidth, cropHeight)
}

/** ZXing is bundled in the APK; no account, Google Play services, or model download is involved. */
class RetailBarcodeDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.CODE_128, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.CODE_39, BarcodeFormat.ITF,
            ),
            DecodeHintType.TRY_HARDER to true,
        ))
    }

    /** One analyzer thread owns an instance. A second orientation also supports vertical labels. */
    fun decode(frame: LuminanceFrame): DecodedRetailBarcode? = attempt(frame) ?: attempt(frame.rotateClockwise(90))

    private fun attempt(frame: LuminanceFrame): DecodedRetailBarcode? = try {
        val source = PlanarYUVLuminanceSource(frame.bytes, frame.width, frame.height, 0, 0, frame.width, frame.height, false)
        val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        result.text?.takeIf { it.isNotEmpty() }?.let { DecodedRetailBarcode(it, result.barcodeFormat.name) }
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}

/** A pause never counts as barcode absence; only analyzed no-code frames can rearm the same label. */
class BarcodeScanGate(private val absenceMillis: Long = 750L) {
    private var lastDelivered: String? = null
    private var emptySince: Long? = null

    @Synchronized
    fun accept(value: String, format: String, nowMillis: Long): Boolean {
        val key = "$format\u0000$value"
        val absentLongEnough = emptySince?.let { nowMillis - it >= absenceMillis } == true
        val accept = lastDelivered != key || absentLongEnough
        emptySince = null
        if (accept) lastDelivered = key
        return accept
    }

    @Synchronized fun noBarcode(nowMillis: Long) { if (emptySince == null) emptySince = nowMillis }
    @Synchronized fun pause() { emptySince = null }
    @Synchronized fun rearm() { lastDelivered = null; emptySince = null }
}
