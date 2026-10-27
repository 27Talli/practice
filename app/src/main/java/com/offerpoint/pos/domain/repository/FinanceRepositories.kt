package com.offerpoint.pos.domain.repository

import com.offerpoint.pos.domain.model.*
import kotlinx.coroutines.flow.Flow

interface RefundRepository {
    suspend fun eligibility(shopId: Long, saleId: Long): ReturnEligibility
    suspend fun post(request: RefundRequest): RefundDetails
    suspend fun get(shopId: Long, id: Long): RefundDetails?
    suspend fun findRequest(shopId: Long, requestId: String): RefundDetails?
    fun observe(shopId: Long): Flow<List<RefundDetails>>
}
interface ExpenseRepository {
    suspend fun add(draft: ExpenseDraft): ExpenseRecord
    suspend fun findRequest(shopId: Long, requestId: String): ExpenseRecord?
    suspend fun void(shopId: Long, id: Long, reason: String)
    fun observe(shopId: Long): Flow<List<ExpenseRecord>>
}
interface ReportRepository {
    fun observe(shopId: Long, start: Long, endExclusive: Long): Flow<FinancialReport>
    suspend fun get(shopId: Long, start: Long, endExclusive: Long): FinancialReport
}
