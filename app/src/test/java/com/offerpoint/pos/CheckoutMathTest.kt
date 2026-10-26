package com.offerpoint.pos

import com.offerpoint.pos.domain.model.CheckoutException
import com.offerpoint.pos.domain.model.CheckoutLine
import com.offerpoint.pos.domain.model.CheckoutRequest
import com.offerpoint.pos.domain.usecase.BusinessDay
import com.offerpoint.pos.domain.usecase.CheckoutMath
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CheckoutMathTest {
    private fun line(id: Long = 1, quantity: Long = 2, price: Long = 10025, discount: Long = 50) =
        CheckoutLine(id, quantity, price, discount)

    private fun request(lines: List<CheckoutLine> = listOf(line()), paid: Long = 25000, order: Long = 100) =
        CheckoutRequest("checkout-1", 1, lines, order, paid)

    @Test fun quantityAndBothDiscountLevelsTotalExactly() {
        val totals = CheckoutMath.validate(request())
        assertEquals(20050L, totals.subtotalPaise)
        assertEquals(50L, totals.lineDiscountPaise)
        assertEquals(100L, totals.orderDiscountPaise)
        assertEquals(150L, totals.discountPaise)
        assertEquals(19900L, totals.totalPaise)
        assertEquals(5100L, CheckoutMath.change(totals.totalPaise, 25000))
    }

    @Test fun decimalPricesNeverNeedFloatingPointRounding() {
        val totals = CheckoutMath.totals(listOf(line(price = 10, discount = 0), line(2, 1, 20, 0)))
        assertEquals(40L, totals.totalPaise)
    }

    @Test fun discountsCanReduceTheSaleToZero() {
        val totals = CheckoutMath.validate(request(paid = 0, order = 20000))
        assertEquals(0L, totals.totalPaise)
        assertEquals(0L, CheckoutMath.change(0, 0))
    }

    @Test fun exactCashProducesZeroChange() {
        assertEquals(0L, CheckoutMath.change(19900, 19900))
    }

    @Test fun emptyCartIsNotASale() {
        assertThrows(CheckoutException::class.java) { CheckoutMath.totals(emptyList()) }
    }

    @Test fun duplicateProductsMustBeCombinedBeforeCheckout() {
        assertThrows(CheckoutException::class.java) { CheckoutMath.totals(listOf(line(), line())) }
    }

    @Test fun quantityMustBePositiveAndProductMustExist() {
        listOf(line(quantity = 0), line(quantity = -1), line(id = 0)).forEach {
            assertThrows(CheckoutException::class.java) { CheckoutMath.totals(listOf(it)) }
        }
    }

    @Test fun pricesAndDiscountsCannotBeNegative() {
        assertThrows(CheckoutException::class.java) { CheckoutMath.totals(listOf(line(price = -1))) }
        assertThrows(CheckoutException::class.java) { CheckoutMath.totals(listOf(line(discount = -1))) }
        assertThrows(CheckoutException::class.java) { CheckoutMath.totals(listOf(line()), -1) }
    }

    @Test fun lineDiscountCannotExceedGrossLineAmount() {
        assertThrows(CheckoutException::class.java) { CheckoutMath.totals(listOf(line(discount = 20051))) }
    }

    @Test fun orderDiscountCannotExceedValueRemainingAfterLineDiscounts() {
        assertThrows(CheckoutException::class.java) { CheckoutMath.totals(listOf(line()), 20001) }
    }

    @Test fun underpaymentCannotCompleteASale() {
        assertThrows(CheckoutException::class.java) { CheckoutMath.validate(request(paid = 19899)) }
        assertThrows(CheckoutException::class.java) { CheckoutMath.change(0, -1) }
    }

    @Test fun multiplicationAndSubtotalOverflowAreRejected() {
        assertThrows(CheckoutException::class.java) { CheckoutMath.totals(listOf(line(quantity = 2, price = Long.MAX_VALUE, discount = 0))) }
        assertThrows(CheckoutException::class.java) {
            CheckoutMath.totals(listOf(line(quantity = 1, price = Long.MAX_VALUE, discount = 0), line(2, 1, 1, 0)))
        }
    }

    @Test fun maximumRepresentableTotalWithExactCashIsAccepted() {
        val totals = CheckoutMath.validate(request(listOf(line(quantity = 1, price = Long.MAX_VALUE, discount = 0)), Long.MAX_VALUE, 0))
        assertEquals(Long.MAX_VALUE, totals.totalPaise)
    }

    @Test fun requestNeedsAShopAndAnIdempotencyKey() {
        assertThrows(CheckoutException::class.java) { CheckoutMath.validate(request().copy(shopId = 0)) }
        assertThrows(CheckoutException::class.java) { CheckoutMath.validate(request().copy(requestId = " ")) }
    }

    @Test fun retryFingerprintIsOrderIndependentButDetectsChangedPaymentAndShop() {
        val original = request(listOf(line(), line(2, 1, 100, 0)))
        val fingerprint = CheckoutMath.fingerprint(original)
        assertEquals(fingerprint, CheckoutMath.fingerprint(original.copy(lines = original.lines.reversed())))
        assertNotEquals(fingerprint, CheckoutMath.fingerprint(original.copy(amountPaidPaise = 25001)))
        assertNotEquals(fingerprint, CheckoutMath.fingerprint(original.copy(shopId = 2)))
        assertNotEquals(fingerprint, CheckoutMath.fingerprint(original.copy(orderDiscountPaise = 0)))
        assertEquals(64, fingerprint.length)
    }

    @Test fun businessDayUsesPakistanMidnightAndAnExclusiveEnd() {
        val justBefore = BusinessDay.bounds(Instant.parse("2026-10-25T18:59:59.999Z").toEpochMilli())
        val midnight = BusinessDay.bounds(Instant.parse("2026-10-25T19:00:00Z").toEpochMilli())
        assertEquals(Instant.parse("2026-10-24T19:00:00Z").toEpochMilli(), justBefore.first)
        assertEquals(midnight.first, justBefore.second)
        assertEquals(Instant.parse("2026-10-26T19:00:00Z").toEpochMilli(), midnight.second)
        assertEquals(86400000L, midnight.second - midnight.first)
    }
}
