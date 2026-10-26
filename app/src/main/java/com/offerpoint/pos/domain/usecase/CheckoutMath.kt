package com.offerpoint.pos.domain.usecase

import com.offerpoint.pos.domain.model.CheckoutException
import com.offerpoint.pos.domain.model.CheckoutLine
import com.offerpoint.pos.domain.model.CheckoutRequest
import com.offerpoint.pos.domain.model.CheckoutTotals
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** Fixed-amount discounts in paise. Every multiplication and accumulation is checked. */
object CheckoutMath {
    fun totals(lines: List<CheckoutLine>, orderDiscountPaise: Long = 0): CheckoutTotals {
        if (lines.isEmpty()) throw CheckoutException("Add at least one product to the cart.")
        if (lines.map { it.productId }.toSet().size != lines.size) throw CheckoutException("Combine duplicate products into one cart line.")
        if (orderDiscountPaise < 0) throw CheckoutException("The order discount cannot be negative.")
        return checked {
            var subtotal = 0L
            var lineDiscount = 0L
            for (line in lines) {
                if (line.productId <= 0 || line.quantity <= 0) throw CheckoutException("Every cart line needs a product and a positive whole quantity.")
                if (line.unitPricePaise < 0 || line.lineDiscountPaise < 0) throw CheckoutException("Prices and discounts cannot be negative.")
                val gross = Math.multiplyExact(line.quantity, line.unitPricePaise)
                if (line.lineDiscountPaise > gross) throw CheckoutException("A line discount cannot exceed that line's value.")
                subtotal = Math.addExact(subtotal, gross)
                lineDiscount = Math.addExact(lineDiscount, line.lineDiscountPaise)
            }
            val afterLines = Math.subtractExact(subtotal, lineDiscount)
            if (orderDiscountPaise > afterLines) throw CheckoutException("The order discount cannot exceed the remaining cart value.")
            CheckoutTotals(subtotal, lineDiscount, orderDiscountPaise, Math.subtractExact(afterLines, orderDiscountPaise))
        }
    }

    fun validate(request: CheckoutRequest): CheckoutTotals {
        if (request.shopId <= 0) throw CheckoutException("Choose a valid shop before checking out.")
        if (request.requestId.isBlank() || request.requestId.length > 128) throw CheckoutException("This checkout has no valid request identifier.")
        val totals = totals(request.lines, request.orderDiscountPaise)
        change(totals.totalPaise, request.amountPaidPaise)
        return totals
    }

    fun change(totalPaise: Long, amountPaidPaise: Long): Long {
        if (totalPaise < 0 || amountPaidPaise < totalPaise) throw CheckoutException("Cash received must cover the sale total.")
        return checked { Math.subtractExact(amountPaidPaise, totalPaise) }
    }

    /** Stable across line order; detects reuse of an idempotency key with a different payment/cart. */
    fun fingerprint(request: CheckoutRequest): String {
        val canonical = buildString {
            append(request.shopId).append('|').append(request.orderDiscountPaise).append('|').append(request.amountPaidPaise)
            request.lines.sortedBy { it.productId }.forEach {
                append(';').append(it.productId).append(',').append(it.quantity).append(',').append(it.unitPricePaise).append(',').append(it.lineDiscountPaise)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }

    private inline fun <T> checked(block: () -> T): T = try { block() } catch (_: ArithmeticException) {
        throw CheckoutException("These quantities or amounts are too large to total safely.")
    }
}

object BusinessDay {
    val zone: ZoneId = ZoneId.of("Asia/Karachi")
    fun bounds(nowMillis: Long): Pair<Long, Long> {
        val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
