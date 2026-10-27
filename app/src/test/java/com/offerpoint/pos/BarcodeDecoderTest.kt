package com.offerpoint.pos

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.offerpoint.pos.ui.scanner.BarcodeScanGate
import com.offerpoint.pos.ui.scanner.LuminanceFrame
import com.offerpoint.pos.ui.scanner.RetailBarcodeDecoder
import com.offerpoint.pos.ui.scanner.copyLuminancePlane
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeDecoderTest {
    private fun image(value: String, format: BarcodeFormat): LuminanceFrame {
        val matrix = MultiFormatWriter().encode(value, format, 900, 280)
        val bytes = ByteArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) bytes[y * matrix.width + x] = if (matrix[x, y]) 0 else 255.toByte()
        return LuminanceFrame(bytes, matrix.width, matrix.height)
    }

    @Test fun actualCode128PixelsDecodeOffline() {
        val result = RetailBarcodeDecoder().decode(image("OP-000001", BarcodeFormat.CODE_128))!!
        assertEquals("OP-000001", result.value)
        assertEquals("CODE_128", result.format)
    }

    @Test fun manufacturerEan13RetainsLeadingDigits() {
        val result = RetailBarcodeDecoder().decode(image("5901234123457", BarcodeFormat.EAN_13))!!
        assertEquals("5901234123457", result.value)
        assertEquals("EAN_13", result.format)
    }

    @Test fun verticalAndUpsideDownBarcodesStillDecode() {
        val source = image("OP-000089", BarcodeFormat.CODE_128)
        for (rotation in listOf(90, 180, 270)) {
            assertEquals("OP-000089", RetailBarcodeDecoder().decode(source.rotateClockwise(rotation))?.value)
        }
    }

    @Test fun whiteFrameIsNotAProduct() {
        assertNull(RetailBarcodeDecoder().decode(LuminanceFrame(ByteArray(400 * 200) { 255.toByte() }, 400, 200)))
    }

    @Test fun cameraPlaneCopyUsesStrideCropPixelSpacingAndBufferPosition() {
        val data = ByteArray(2 + 3 * 10) { 99 }
        // Image is four pixels wide, with two bytes per sample and padding to ten bytes per row.
        for (y in 0 until 3) for (x in 0 until 4) data[2 + y * 10 + x * 2] = (y * 4 + x).toByte()
        val input = ByteBuffer.wrap(data).apply { position(2) }
        val frame = copyLuminancePlane(input, 10, 2, 4, 3, 1, 1, 2, 2)
        assertArrayEquals(byteArrayOf(5, 6, 9, 10), frame.bytes)
        assertEquals(2, input.position())
    }

    @Test fun rotationChangesDimensionsAndPreservesPixels() {
        val source = LuminanceFrame(byteArrayOf(1, 2, 3, 4, 5, 6), 3, 2)
        val rotated = source.rotateClockwise(90)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), rotated.bytes)
        assertArrayEquals(source.bytes, rotated.rotateClockwise(270).bytes)
        assertThrows(IllegalArgumentException::class.java) { source.rotateClockwise(45) }
    }

    @Test fun malformedPlaneDoesNotReadOutsideBuffer() {
        assertThrows(IllegalArgumentException::class.java) { copyLuminancePlane(ByteBuffer.allocate(2), 10, 1, 4, 3) }
    }

    @Test fun heldCodeIsSuppressedUntilActualAbsenceOrExplicitRearm() {
        val gate = BarcodeScanGate(750)
        assertTrue(gate.accept("OP-1", "CODE_128", 0))
        assertFalse(gate.accept("OP-1", "CODE_128", 10_000))
        gate.noBarcode(10_010)
        assertFalse(gate.accept("OP-1", "CODE_128", 10_100))
        gate.noBarcode(10_200)
        assertTrue(gate.accept("OP-1", "CODE_128", 10_950))
        gate.rearm()
        assertTrue(gate.accept("OP-1", "CODE_128", 10_951))
    }

    @Test fun disabledOrBackgroundTimeDoesNotCountAsBarcodeLeavingTheFrame() {
        val gate = BarcodeScanGate(750)
        assertTrue(gate.accept("OP-1", "CODE_128", 0))
        gate.noBarcode(1)
        gate.pause()
        assertFalse(gate.accept("OP-1", "CODE_128", 50_000))
        assertTrue(gate.accept("OP-2", "CODE_128", 50_001))
    }
}
