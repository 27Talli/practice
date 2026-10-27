package com.offerpoint.pos

import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.usecase.BuildReceipt
import com.offerpoint.pos.domain.usecase.ReceiptValidation
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class ReceiptTest {
    private val shop = Shop(1, "OP", "Offer Point", "Shop No. 8, Yahya Khan Market, Mardan")
    private val settings = ReceiptSettings(1)
    private val item = SoldItem(1, "OP-000001", "Lipstick at sale", "Rose", 2, 5_000, 9_876_543, 500, 9_500)
    private val sale = SaleDetails(SaleSummary(1, 1, "OP-S-000001", 9_200, Instant.parse("2026-10-26T20:30:00Z").toEpochMilli()),
        10_000, 800, 300, 10_000, 800, "CASH", listOf(item))

    @Test fun exactSavedAmountsAndDiscountsAppearWithoutPurchaseCost() {
        val receipt = BuildReceipt.create(shop, sale, settings, false)
        assertEquals(1L, receipt.saleId)
        assertEquals("OP-S-000001", receipt.receiptNumber)
        assertTrue(receipt.lines.contains(ReceiptLine("2 x Rs. 50.00", "Rs. 95.00")))
        assertTrue(receipt.lines.contains(ReceiptLine("Line discounts", "Rs. 5.00")))
        assertTrue(receipt.lines.contains(ReceiptLine("Order discount", "Rs. 3.00")))
        assertTrue(receipt.lines.contains(ReceiptLine("TOTAL", "Rs. 92.00", ReceiptLineStyle.TOTAL)))
        assertTrue(receipt.lines.contains(ReceiptLine("Cash received", "Rs. 100.00")))
        assertTrue(receipt.lines.contains(ReceiptLine("Change", "Rs. 8.00", ReceiptLineStyle.EMPHASIS)))
        assertFalse(receipt.toString().contains("9876543") || receipt.toString().contains("98,765.43"))
    }

    @Test fun anotherShopOrMismatchedSettingsCannotBrandASale() {
        assertThrows(IllegalArgumentException::class.java) { BuildReceipt.create(shop.copy(id = 2), sale, settings, false) }
        assertThrows(IllegalArgumentException::class.java) { BuildReceipt.create(shop, sale, settings.copy(shopId = 2), false) }
    }

    @Test fun demoMarkerIsAnExplicitPartOfTheCustomerSafeDocument() {
        assertTrue(BuildReceipt.create(shop, sale, settings, true).demo)
        assertFalse(BuildReceipt.create(shop, sale, settings, false).demo)
    }

    @Test fun pakistanTimestampDoesNotFollowThePhoneTimeZone() {
        val before = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("27 Oct 2026, 01:30 PKT", BuildReceipt.create(shop, sale, settings, false).soldAt)
        } finally { TimeZone.setDefault(before) }
    }

    @Test fun inconsistentTotalsChangeAndLineValuesAreRejected() {
        val invalid = listOf(sale.copy(subtotalPaise = 1), sale.copy(discountPaise = 1), sale.copy(orderDiscountPaise = 1),
            sale.copy(changePaise = 1), sale.copy(amountPaidPaise = 1), sale.copy(summary = sale.summary.copy(totalPaise = 1)),
            sale.copy(items = listOf(item.copy(lineTotalPaise = 1))))
        invalid.forEach { value -> assertThrows(IllegalArgumentException::class.java) { BuildReceipt.create(shop, value, settings, false) } }
    }

    @Test fun zeroTotalSaleIsRepresentedExactly() {
        val zero = sale.copy(summary = sale.summary.copy(totalPaise = 0), discountPaise = 10_000, orderDiscountPaise = 9_500, amountPaidPaise = 0, changePaise = 0)
        assertTrue(BuildReceipt.create(shop, zero, settings, false).lines.contains(ReceiptLine("TOTAL", "Rs. 0.00", ReceiptLineStyle.TOTAL)))
    }

    @Test fun maximumMoneyIsFormattedWithoutFloatingPointRounding() {
        val large = SoldItem(1, "OP-000001", "Large amount", "", 1, Long.MAX_VALUE, 0, 0, Long.MAX_VALUE)
        val value = sale.copy(summary = sale.summary.copy(totalPaise = Long.MAX_VALUE), subtotalPaise = Long.MAX_VALUE,
            discountPaise = 0, orderDiscountPaise = 0, amountPaidPaise = Long.MAX_VALUE, changePaise = 0, items = listOf(large))
        assertTrue(BuildReceipt.create(shop, value, settings, false).lines.contains(ReceiptLine("TOTAL", "Rs. 92,233,720,368,547,758.07", ReceiptLineStyle.TOTAL)))
    }

    @Test fun missingIdentityEmptySalesAndUnknownPaymentAreRejected() {
        listOf(sale.copy(summary = sale.summary.copy(id = 0)), sale.copy(summary = sale.summary.copy(receiptNumber = "")),
            sale.copy(items = emptyList()), sale.copy(paymentMethod = "CARD")).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { BuildReceipt.create(shop, value, settings, false) }
        }
    }

    @Test fun customFooterIsOptionalAndDoesNotInventAReturnPolicy() {
        val empty = BuildReceipt.create(shop, sale, settings.copy(footer = ""), false)
        assertFalse(empty.lines.any { it.text.contains("Thank you") || it.text.contains("return", ignoreCase = true) })
        val custom = BuildReceipt.create(shop, sale, settings.copy(footer = "  Thank you\nVisit again  "), false)
        assertEquals(listOf("Thank you", "Visit again"), custom.lines.takeLast(2).map { it.text })
    }

    @Test fun invalidPreferencesAreRejectedBeforePersistence() {
        assertThrows(IllegalArgumentException::class.java) { ReceiptValidation.settings(settings.copy(shopId = 0)) }
        assertThrows(IllegalArgumentException::class.java) { ReceiptValidation.settings(settings.copy(footer = "X".repeat(241))) }
        assertThrows(IllegalArgumentException::class.java) { ReceiptValidation.settings(settings.copy(footer = "Bad\u0000footer")) }
        assertEquals("Thanks", ReceiptValidation.settings(settings.copy(footer = " Thanks ")).footer)
    }
}
