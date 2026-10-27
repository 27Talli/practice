package com.offerpoint.pos

import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.usecase.*
import com.offerpoint.pos.ui.printing.EscPosRaster
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import kotlin.random.Random

class FinanceMathTest {
    @Test fun orderDiscountUsesLargestRemaindersWithDeterministicTies() {
        assertEquals(listOf(66L,67L,67L),RefundMath.netLines(listOf(100,100,100),100))
        assertEquals(listOf(99L,199L,298L),RefundMath.netLines(listOf(100,200,300),4))
        assertEquals(listOf(0L,0L),RefundMath.netLines(listOf(0,0),0))
    }
    @Test fun partialReturnsTogetherRefundEveryPaisa() {
        assertEquals(listOf(33L,33L,34L),(0L..2L).map{RefundMath.amount(100,3,it,1)})
        assertEquals(67L,RefundMath.amount(100,3,1,2))
        assertEquals(0L,RefundMath.amount(0,3,0,3))
    }
    @Test fun randomDiscountsAndRepeatedUnitReturnsAlwaysConserveTheOriginalPayment() {
        val random=Random(1947)
        repeat(500) {
            val totals=List(random.nextInt(1,20)){random.nextLong(0,1_000_000)}
            val total=totals.sum();val discount=random.nextLong(0,total+1)
            val nets=RefundMath.netLines(totals,discount)
            assertEquals(total-discount,nets.sum())
            nets.forEach { net ->
                val quantity=random.nextLong(1,50)
                assertEquals(net,(0 until quantity).sumOf{RefundMath.amount(net,quantity,it,1)})
            }
        }
    }
    @Test fun allocationWorksAtLongMaximumWithoutMultiplicationOverflow() {
        assertEquals(listOf(1L,0L),RefundMath.netLines(listOf(Long.MAX_VALUE-1,1),Long.MAX_VALUE-1))
        assertEquals(Long.MAX_VALUE,RefundMath.amount(Long.MAX_VALUE,Long.MAX_VALUE,0,Long.MAX_VALUE))
    }
    @Test fun invalidDiscountsAndExcessiveReturnsAreRejected() {
        listOf(-1L,101L).forEach{discount->assertThrows(IllegalArgumentException::class.java){RefundMath.netLines(listOf(100),discount)}}
        assertThrows(IllegalArgumentException::class.java){RefundMath.netLines(emptyList(),0)}
        assertThrows(IllegalArgumentException::class.java){RefundMath.amount(100,2,1,2)}
        assertThrows(IllegalArgumentException::class.java){RefundMath.amount(100,0,0,1)}
        assertThrows(IllegalArgumentException::class.java){RefundMath.amount(100,2,0,0)}
    }
    @Test fun returnValidationRequiresReasonUniqueLinesAndValidRestock() {
        val request=RefundRequest("return-1",1,1,listOf(RefundLine(1,1,1)),"  Wrong size  ",100)
        assertEquals("Wrong size",RefundMath.validate(request).reason)
        listOf(request.copy(reason=" "),request.copy(lines=emptyList()),request.copy(lines=request.lines+request.lines),
            request.copy(lines=listOf(RefundLine(1,1,2))),request.copy(expectedRefundPaise=-1)).forEach{
            assertThrows(IllegalArgumentException::class.java){RefundMath.validate(it)}
        }
    }
    @Test fun retryFingerprintDetectsChangedCashRestockAndReasonButAllowsReorderedLines() {
        val request=RefundRequest("return-1",1,1,listOf(RefundLine(1,1,1),RefundLine(2,2,0)),"Wrong size",300)
        assertEquals(RefundMath.fingerprint(request),RefundMath.fingerprint(request.copy(lines=request.lines.reversed())))
        assertNotEquals(RefundMath.fingerprint(request),RefundMath.fingerprint(request.copy(reason="Damaged")))
        assertNotEquals(RefundMath.fingerprint(request),RefundMath.fingerprint(request.copy(expectedRefundPaise=301)))
        assertNotEquals(RefundMath.fingerprint(request),RefundMath.fingerprint(request.copy(lines=listOf(RefundLine(1,1,0),RefundLine(2,2,0)))))
    }
    @Test fun refundReceiptIncludesOriginalReferenceAndExactRefundWithoutPrivateCost() {
        val refund=RefundDetails(2,1,1,"OP-R-000001","OP-S-000001","Size",101,0,
            listOf(RefundedItem(1,1,"OP-000001","Lipstick","Rose",2,1,101,987654321,"Cosmetics")))
        val doc=BuildRefundReceipt.create(Shop(1,"OP","Offer Point","Mardan"),refund,ReceiptSettings(1),false)
        assertEquals("CASH REFUND RECEIPT",doc.title)
        assertTrue(doc.lines.any{it.text=="Original sale: OP-S-000001"})
        assertTrue(doc.lines.any{it.text=="CASH REFUND"&&it.value=="Rs. 1.01"})
        assertFalse(doc.toString().contains("987654321"))
        assertThrows(IllegalArgumentException::class.java){BuildRefundReceipt.create(Shop(1,"OP","Offer Point","Mardan"),refund.copy(refundPaise=102),ReceiptSettings(1),false)}
    }
    @Test fun csvKeepsExactSignedMoneyQuotesTextAndNeutralisesSpreadsheetFormulas() {
        val report=FinancialReport(1,0,100,100,301,50,-201,0,-201,-251,1,1,0,0,
            listOf(ReportProduct("OP-000001","=HYPERLINK(\"bad\")",-2,-201)),listOf(ReportCategory("@formula",-201)))
        val out=StringWriter();ReportCsv.write(out,Shop(1,"OP","Offer, Point","Mardan"),report)
        val csv=out.toString()
        assertTrue(csv.contains("\"Offer, Point\""))
        assertTrue(csv.contains("\"Net sales\",-2.01\r\n"))
        assertTrue(csv.contains("\"Net operating cash movement\",-2.51\r\n"))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"bad\"\")\""))
        assertTrue(csv.contains("\"'@formula\",-2.01"))
    }
    @Test fun directPrinterProfileRequiresRollPaperAndAnExplicitDevice() {
        assertThrows(IllegalArgumentException::class.java){ReceiptValidation.settings(ReceiptSettings(1,printerMode=PrinterMode.BLUETOOTH,printerKey="AA:BB"))}
        assertThrows(IllegalArgumentException::class.java){ReceiptValidation.settings(ReceiptSettings(1,ReceiptPaper.ROLL_58,printerMode=PrinterMode.USB))}
        assertEquals("123:456",ReceiptValidation.settings(ReceiptSettings(1,ReceiptPaper.ROLL_58,printerMode=PrinterMode.USB,printerKey="123:456")).printerKey)
    }
    @Test fun rasterBandsUseEscPosHeadersAndMostSignificantBitFirst() {
        val band=EscPosRaster.band(16,2){x,y->if(y==0)x==0||x==7||x==8 else x==15}
        assertArrayEquals(byteArrayOf(0x1d,0x76,0x30,0,2,0,2,0,0x81.toByte(),0x80.toByte(),0,1),band)
        assertThrows(IllegalArgumentException::class.java){EscPosRaster.band(15,1){_,_->false}}
        assertThrows(IllegalArgumentException::class.java){EscPosRaster.band(8,129){_,_->false}}
    }
    @Test fun lowStockThresholdAndDisplayTextHaveBoundedValidation() {
        val draft=ProductDraft("Lipstick","Cosmetics","",null,100,200,5)
        assertEquals(0L,ProductValidation.validate(draft.copy(reorderLevel=0)).reorderLevel)
        assertThrows(IllegalArgumentException::class.java){ProductValidation.validate(draft.copy(reorderLevel=-1))}
        assertThrows(IllegalArgumentException::class.java){ProductValidation.validate(draft.copy(name="A".repeat(121)))}
    }
}
