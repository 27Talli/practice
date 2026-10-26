package com.offerpoint.pos.domain.repository

import com.offerpoint.pos.domain.model.CheckoutRequest
import com.offerpoint.pos.domain.model.SaleDetails
import com.offerpoint.pos.domain.model.SaleSummary
import com.offerpoint.pos.domain.model.TodaySales
import kotlinx.coroutines.flow.Flow

interface SalesRepository {
    /** Atomic and idempotent: a repeated identical request returns the saved sale. */
    suspend fun completeSale(request: CheckoutRequest): SaleDetails
    suspend fun getSale(shopId: Long, saleId: Long): SaleDetails?
    suspend fun findCompletedRequest(shopId: Long, requestId: String): SaleDetails?
    fun observeSales(shopId: Long): Flow<List<SaleSummary>>
    /** The business day is always Asia/Karachi, independent of the phone's time zone. */
    fun observeTodaySales(shopId: Long): Flow<TodaySales>
}
