package com.offerpoint.pos.domain.usecase

import com.offerpoint.pos.domain.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ReceiptValidation {
    fun settings(settings: ReceiptSettings): ReceiptSettings {
        require(settings.shopId > 0) { "Choose a valid shop." }
        val footer = settings.footer.trim()
        require(footer.length <= 240 && footer.none { it.isISOControl() && it != '\n' }) {
            "Use a footer of at most 240 characters without control characters."
        }
        return settings.copy(footer = footer)
    }
}

/** Receipts derive exclusively from completed-sale snapshots; generating one has no write side effects. */
object BuildReceipt {
    fun create(shop: Shop, sale: SaleDetails, settings: ReceiptSettings, demo: Boolean): ReceiptDocument {
        val valid = ReceiptValidation.settings(settings)
        require(shop.id == sale.summary.shopId && valid.shopId == shop.id) { "This sale belongs to another shop." }
        require(sale.summary.id > 0 && sale.summary.receiptNumber.isNotBlank() && sale.items.isNotEmpty()) { "A saved sale is required for a receipt." }
        require(sale.paymentMethod == "CASH") { "This payment method is not supported by receipts yet." }
        val totals = CheckoutMath.totals(sale.items.map { CheckoutLine(it.productId, it.quantity, it.unitPricePaise, it.discountPaise) }, sale.orderDiscountPaise)
        require(totals.subtotalPaise == sale.subtotalPaise && totals.discountPaise == sale.discountPaise && totals.totalPaise == sale.summary.totalPaise &&
            CheckoutMath.change(totals.totalPaise, sale.amountPaidPaise) == sale.changePaise) { "Saved sale totals are inconsistent. The receipt was not generated." }
        sale.items.forEach { item ->
            require(Math.subtractExact(Math.multiplyExact(item.quantity, item.unitPricePaise), item.discountPaise) == item.lineTotalPaise) { "A saved sale line is inconsistent." }
        }
        val lines = buildList {
            for (item in sale.items) {
                add(ReceiptLine(clean(item.name), style = ReceiptLineStyle.EMPHASIS))
                if (item.variant.isNotBlank()) add(ReceiptLine(clean(item.variant)))
                add(ReceiptLine("SKU: ${clean(item.sku)}"))
                add(ReceiptLine("${item.quantity} x ${Money.format(item.unitPricePaise)}", Money.format(item.lineTotalPaise)))
                if (item.discountPaise > 0) add(ReceiptLine("Line discount", Money.format(item.discountPaise)))
            }
            add(ReceiptLine("Subtotal", Money.format(totals.subtotalPaise)))
            add(ReceiptLine("Line discounts", Money.format(totals.lineDiscountPaise)))
            add(ReceiptLine("Order discount", Money.format(totals.orderDiscountPaise)))
            add(ReceiptLine("TOTAL", Money.format(totals.totalPaise), ReceiptLineStyle.TOTAL))
            add(ReceiptLine("Cash received", Money.format(sale.amountPaidPaise)))
            add(ReceiptLine("Change", Money.format(sale.changePaise), ReceiptLineStyle.EMPHASIS))
            if (valid.footer.isNotBlank()) valid.footer.lines().filter(String::isNotBlank).forEach { add(ReceiptLine(clean(it))) }
        }
        return ReceiptDocument(sale.summary.id, shop.id, clean(sale.summary.receiptNumber), clean(shop.name), clean(shop.address),
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'PKT'", Locale.ENGLISH).withZone(ZoneId.of("Asia/Karachi"))
                .format(Instant.ofEpochMilli(sale.summary.completedAt)), demo, lines)
    }

    private fun clean(text: String): String = text.replace(Regex("[\\s\\p{Cntrl}]+"), " ").trim()
}
