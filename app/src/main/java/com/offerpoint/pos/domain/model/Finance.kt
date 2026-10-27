package com.offerpoint.pos.domain.model

data class RefundLine(val saleItemId: Long, val quantity: Long, val restockQuantity: Long)
data class RefundRequest(val requestId: String, val shopId: Long, val saleId: Long, val lines: List<RefundLine>, val reason: String, val expectedRefundPaise: Long)
data class ReturnableItem(val sold: SoldItem, val returnedQuantity: Long, val refundablePaise: Long)
data class ReturnEligibility(val sale: SaleDetails, val items: List<ReturnableItem>)
data class RefundedItem(val saleItemId: Long, val productId: Long, val sku: String, val name: String, val variant: String,
                        val quantity: Long, val restockQuantity: Long, val refundPaise: Long, val unitCostPaise: Long, val category: String)
data class RefundDetails(val id: Long, val shopId: Long, val saleId: Long, val receiptNumber: String, val originalReceiptNumber: String,
                         val reason: String, val refundPaise: Long, val createdAt: Long, val items: List<RefundedItem>)
data class ExpenseDraft(val requestId: String, val shopId: Long, val amountPaise: Long, val category: String, val description: String, val incurredAt: Long)
data class ExpenseRecord(val id: Long, val shopId: Long, val amountPaise: Long, val category: String, val description: String,
                         val incurredAt: Long, val isVoided: Boolean, val voidReason: String?)
data class ReportProduct(val sku: String, val name: String, val quantity: Long, val revenuePaise: Long)
data class ReportCategory(val category: String, val revenuePaise: Long)
data class FinancialReport(val shopId: Long, val start: Long, val endExclusive: Long, val salesPaise: Long, val refundsPaise: Long,
    val expensesPaise: Long, val netSalesPaise: Long, val netCostPaise: Long, val grossProfitPaise: Long, val operatingProfitPaise: Long,
    val saleCount: Int, val refundCount: Int, val currentStockCostPaise: Long, val lowStockCount: Int,
    val products: List<ReportProduct>, val categories: List<ReportCategory>) {
    val cashMovementPaise: Long get() = Math.subtractExact(netSalesPaise, expensesPaise)
}
