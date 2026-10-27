package com.offerpoint.pos.domain.usecase

import com.offerpoint.pos.domain.model.CheckoutLine
import com.offerpoint.pos.domain.model.SaleDetails
import com.offerpoint.pos.domain.repository.ProductRepository

/** Staff-only gross profit: sales after every discount, less recorded purchase costs. */
data class SaleProfit(val revenuePaise: Long, val costPaise: Long, val profitPaise: Long)

object CalculateSaleProfit {
    fun calculate(lines: List<CheckoutLine>, costs: Map<Long, Long>, orderDiscountPaise: Long): SaleProfit {
        val totals = CheckoutMath.totals(lines, orderDiscountPaise)
        val cost = lines.fold(0L) { sum, line ->
            val unitCost = requireNotNull(costs[line.productId]) { "Purchase cost is unavailable for a cart item." }
            require(unitCost >= 0) { "Purchase cost cannot be negative." }
            Math.addExact(sum, Math.multiplyExact(line.quantity, unitCost))
        }
        return SaleProfit(totals.totalPaise, cost, Math.subtractExact(totals.totalPaise, cost))
    }

    suspend fun estimate(products: ProductRepository, shopId: Long, lines: List<CheckoutLine>, orderDiscountPaise: Long): SaleProfit {
        val costs = lines.associate { line ->
            val product = requireNotNull(products.getProduct(shopId, line.productId)) { "A cart item is no longer available in this shop." }
            require(product.shopId == shopId) { "A cart item belongs to another shop." }
            require(product.sellingPricePaise == line.unitPricePaise) { "A selling price changed. Refresh cart prices before checking profit." }
            line.productId to product.purchaseCostPaise
        }
        return calculate(lines, costs, orderDiscountPaise)
    }

    /** Completed-sale profit always uses original cost snapshots, including when prices later change. */
    fun saved(sale: SaleDetails): SaleProfit {
        val result = calculate(sale.items.map { CheckoutLine(it.productId, it.quantity, it.unitPricePaise, it.discountPaise) },
            sale.items.associate { it.productId to it.unitCostPaise }, sale.orderDiscountPaise)
        require(result.revenuePaise == sale.summary.totalPaise) { "Saved sale totals are inconsistent." }
        return result
    }
}
