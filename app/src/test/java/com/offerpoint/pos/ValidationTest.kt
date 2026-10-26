package com.offerpoint.pos

import com.offerpoint.pos.domain.model.ProductDraft
import com.offerpoint.pos.domain.model.StockException
import com.offerpoint.pos.domain.model.ValidationException
import com.offerpoint.pos.domain.usecase.Money
import com.offerpoint.pos.domain.usecase.ProductValidation
import com.offerpoint.pos.domain.usecase.Quantity
import com.offerpoint.pos.domain.usecase.StockValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ValidationTest {
    @Test fun pricesAreExactPaise() {
        assertEquals(10L, Money.parse("0.10"))
        assertEquals(20L, Money.parse("0.20"))
        assertEquals(30L, Math.addExact(Money.parse("0.10"), Money.parse("0.20")))
        assertEquals(125050L, Money.parse(" 1250.50 "))
        assertEquals(0L, Money.parse("0"))
    }

    @Test fun priceFormattingAndEditingPreserveEveryPaisa() {
        assertEquals("Rs. 1,250.50", Money.format(125050L))
        assertEquals("0.01", Money.input(1L))
        assertEquals(Long.MAX_VALUE, Money.parse(Money.input(Long.MAX_VALUE)))
    }

    @Test fun pricesRejectNegativeFractionsExponentAndOverflow() {
        listOf("-1", "0.001", "1e3", "NaN", "Infinity", "", "1,000", "92233720368547758.08")
            .forEach { text -> assertThrows("Unexpected price accepted: $text", ValidationException::class.java) { Money.parse(text) } }
    }

    @Test fun quantitiesAreWholeNonNegativeAndBounded() {
        assertEquals(0L, Quantity.parse("0"))
        assertEquals(500L, Quantity.parse(" 500 "))
        assertEquals(Long.MAX_VALUE, Quantity.parse(Long.MAX_VALUE.toString()))
        listOf("0.5", "-1", "", "1e3", "9223372036854775808").forEach { text ->
            assertThrows(ValidationException::class.java) { Quantity.parse(text) }
        }
    }

    private fun draft() = ProductDraft(
        name = " Lipstick ", category = " Cosmetics ", variant = " Red ",
        manufacturerBarcode = " 0012345678905 ", purchaseCostPaise = 10000,
        sellingPricePaise = 15000, openingQuantity = 5,
    )

    @Test fun productValidationTrimsFieldsAndPreservesBarcodeZeros() {
        val result = ProductValidation.validate(draft())
        assertEquals("Lipstick", result.name)
        assertEquals("Cosmetics", result.category)
        assertEquals("Red", result.variant)
        assertEquals("0012345678905", result.manufacturerBarcode)
        assertEquals(null, ProductValidation.validate(draft().copy(manufacturerBarcode = "  ")).manufacturerBarcode)
    }

    @Test fun productValidationRejectsMissingDetailsAndNegativeNumbers() {
        listOf(draft().copy(name = " "), draft().copy(category = ""),
            draft().copy(purchaseCostPaise = -1), draft().copy(sellingPricePaise = -1),
            draft().copy(openingQuantity = -1), draft().copy(manufacturerBarcode = "12\n34"))
            .forEach { assertThrows(ValidationException::class.java) { ProductValidation.validate(it) } }
    }

    @Test fun zeroPricesAndBelowCostPricesAreAllowed() {
        assertEquals(0L, ProductValidation.validate(draft().copy(sellingPricePaise = 0)).sellingPricePaise)
    }

    @Test fun adjustmentRequiresReasonAndCannotCrossZeroOrOverflow() {
        assertEquals("Stock count correction", StockValidation.reason(" Stock count correction "))
        assertEquals(0L, StockValidation.resultingQuantity(5, -5))
        assertEquals(9L, StockValidation.resultingQuantity(5, 4))
        assertThrows(StockException::class.java) { StockValidation.reason(" ") }
        assertThrows(StockException::class.java) { StockValidation.resultingQuantity(5, 0) }
        assertThrows(StockException::class.java) { StockValidation.resultingQuantity(5, -6) }
        assertThrows(StockException::class.java) { StockValidation.resultingQuantity(Long.MAX_VALUE, 1) }
        assertThrows(StockException::class.java) { StockValidation.resultingQuantity(0, Long.MIN_VALUE) }
    }
}
