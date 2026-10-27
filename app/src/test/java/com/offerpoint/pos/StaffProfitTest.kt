package com.offerpoint.pos

import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.usecase.CalculateSaleProfit
import com.offerpoint.pos.domain.usecase.CapturedProductBarcode
import com.offerpoint.pos.domain.usecase.ReceiptReference
import com.offerpoint.pos.ui.receipts.ReceiptQr
import com.offerpoint.pos.ui.scanner.LuminanceFrame
import com.offerpoint.pos.ui.scanner.RetailBarcodeDecoder
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.*
import org.junit.Test

class StaffProfitTest {
    @Test fun quantitiesLineAndOrderDiscountsAllReduceGrossProfitExactly() {
        val result = CalculateSaleProfit.calculate(listOf(CheckoutLine(1, 3, 1050, 5), CheckoutLine(2, 2, 201, 1)), mapOf(1L to 650L, 2L to 99L), 21)
        assertEquals(3525L, result.revenuePaise)
        assertEquals(2148L, result.costPaise)
        assertEquals(1377L, result.profitPaise)
    }
    @Test fun zeroPricesAndBelowCostSalesShowAnHonestLoss() {
        assertEquals(-600L, CalculateSaleProfit.calculate(listOf(CheckoutLine(1, 2, 0)), mapOf(1L to 300L), 0).profitPaise)
        assertEquals(0L, CalculateSaleProfit.calculate(listOf(CheckoutLine(1, 1, 50)), mapOf(1L to 0L), 50).profitPaise)
    }
    @Test fun missingOrNegativeCostsCannotBePretendedToBeZero() {
        assertThrows(IllegalArgumentException::class.java) { CalculateSaleProfit.calculate(listOf(CheckoutLine(1, 1, 500)), emptyMap(), 0) }
        assertThrows(IllegalArgumentException::class.java) { CalculateSaleProfit.calculate(listOf(CheckoutLine(1, 1, 500)), mapOf(1L to -1L), 0) }
    }
    @Test fun multiplyingAndAccumulatingCostsRejectsOverflow() {
        assertThrows(ArithmeticException::class.java) { CalculateSaleProfit.calculate(listOf(CheckoutLine(1, 2, 0)), mapOf(1L to Long.MAX_VALUE), 0) }
        assertThrows(ArithmeticException::class.java) { CalculateSaleProfit.calculate(listOf(CheckoutLine(1, 1, 0), CheckoutLine(2, 1, 0)), mapOf(1L to Long.MAX_VALUE, 2L to 1L), 0) }
    }
    @Test fun savedProfitUsesTheRecordedCostAndValidatesTheRecordedTotal() {
        val sale = SaleDetails(SaleSummary(1, 1, "OP-S-000001", 899, 1), 1000, 101, 100, 1000, 101, "CASH",
            listOf(SoldItem(1,"OP-000001","Lipstick","",2,500,300,1,999)))
        assertEquals(299L, CalculateSaleProfit.saved(sale).profitPaise)
        assertThrows(IllegalArgumentException::class.java) { CalculateSaleProfit.saved(sale.copy(summary = sale.summary.copy(totalPaise = 900))) }
    }
    @Test fun capturedPackagingCodesPreserveLeadingZerosAndNeverDecodePrices() {
        assertEquals("0000123456789", CapturedProductBarcode.validate(" 0000123456789 "))
        assertEquals("OP-000001~BLACK~123", CapturedProductBarcode.validate("OP-000001~BLACK~123"))
        listOf("", "X".repeat(129), "123\u0000").forEach { value -> assertThrows(IllegalArgumentException::class.java) { CapturedProductBarcode.validate(value) } }
    }
    @Test fun receiptLookupAcceptsOnlyAnOriginalSaleNumberInTheSelectedShop() {
        assertEquals("OP-S-000001", ReceiptReference.originalSale(" op-s-000001 ", "OP"))
        listOf("FF-S-000001", "OP-R-000001", "https://example.com/OP-S-000001", "OP-S-000000", "OP-S-9999999999999999999", "OP-S-000001\nX", "OP-000001").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { ReceiptReference.originalSale(value, "OP") }
        }
    }
    @Test fun generatedReceiptQrDecodesToOnlyTheNumberAndCheckoutIgnoresIt() {
        val reference = "OP-S-000001"
        val matrix = ReceiptQr.matrix(reference)
        val scale = 6
        val width = matrix.width * scale
        val bytes = ByteArray(width * width) { index -> if (matrix[index % width / scale, index / width / scale]) 0 else 255.toByte() }
        val frame = LuminanceFrame(bytes, width, width)
        assertEquals(reference, RetailBarcodeDecoder(receiptQrOnly = true).decode(frame)?.value)
        assertNull(RetailBarcodeDecoder().decode(frame))
        for (rotation in listOf(90, 180, 270)) assertEquals(reference, RetailBarcodeDecoder(true).decode(frame.rotateClockwise(rotation))?.value)
    }
    @Test fun receiptScannerDoesNotAcceptProductBarcodes() {
        val matrix = MultiFormatWriter().encode("OP-000001", BarcodeFormat.CODE_128, 600, 240)
        val bytes = ByteArray(matrix.width * matrix.height) { index -> if (matrix[index % matrix.width, index / matrix.width]) 0 else 255.toByte() }
        assertNull(RetailBarcodeDecoder(true).decode(LuminanceFrame(bytes, matrix.width, matrix.height)))
    }
}
