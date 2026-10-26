package com.offerpoint.pos.domain.model

/** Price is the price shown in the cart; persistence must reject a changed price. */
data class CheckoutLine(val productId: Long, val quantity: Long, val unitPricePaise: Long, val lineDiscountPaise: Long = 0)

data class CheckoutRequest(
    val requestId: String,
    val shopId: Long,
    val lines: List<CheckoutLine>,
    val orderDiscountPaise: Long = 0,
    val amountPaidPaise: Long,
)

data class CheckoutTotals(
    val subtotalPaise: Long,
    val lineDiscountPaise: Long,
    val orderDiscountPaise: Long,
    val totalPaise: Long,
) {
    val discountPaise: Long get() = Math.addExact(lineDiscountPaise, orderDiscountPaise)
}

data class SaleSummary(val id: Long, val shopId: Long, val receiptNumber: String, val totalPaise: Long, val completedAt: Long)

data class SoldItem(
    val productId: Long,
    val sku: String,
    val name: String,
    val variant: String,
    val quantity: Long,
    val unitPricePaise: Long,
    val unitCostPaise: Long,
    val discountPaise: Long,
    val lineTotalPaise: Long,
)

data class SaleDetails(
    val summary: SaleSummary,
    val subtotalPaise: Long,
    val discountPaise: Long,
    val orderDiscountPaise: Long,
    val amountPaidPaise: Long,
    val changePaise: Long,
    val paymentMethod: String,
    val items: List<SoldItem>,
)

data class TodaySales(val totalPaise: Long, val transactionCount: Int)

class CheckoutException(override val message: String) : IllegalArgumentException(message)
