package com.offerpoint.pos.data.repository

import androidx.room.withTransaction
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.local.database.withWriteTransaction
import com.offerpoint.pos.data.local.entity.*
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.*
import com.offerpoint.pos.domain.usecase.RefundMath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

class RoomRefundRepository(private val database:PosDatabase,private val now:()->Long=System::currentTimeMillis):RefundRepository {
    private val dao=database.posDao()
    private val sales=RoomSalesRepository(database)
    override suspend fun eligibility(shopId:Long,saleId:Long):ReturnEligibility=database.withTransaction {
        val sale=sales.getSale(shopId,saleId) ?: error("The original completed sale was not found in this shop.")
        val nets=RefundMath.netLines(sale.items.map{it.lineTotalPaise},sale.orderDiscountPaise)
        ReturnEligibility(sale,sale.items.mapIndexed { index,item ->
            val returned=dao.returnedQuantity(shopId,item.saleItemId)
            require(returned in 0..item.quantity) { "The return history for this sale is inconsistent." }
            val remaining=item.quantity-returned
            ReturnableItem(item,returned,if(remaining==0L)0 else RefundMath.amount(nets[index],item.quantity,returned,remaining))
        })
    }
    override suspend fun post(request:RefundRequest):RefundDetails {
        val valid=RefundMath.validate(request);val fingerprint=RefundMath.fingerprint(valid)
        return database.withWriteTransaction {
            dao.findRefundRequest(valid.requestId)?.let { prior ->
                require(prior.shopId==valid.shopId && prior.requestFingerprint==fingerprint) { "This return identifier was used for another request. Check return history." }
                return@withWriteTransaction details(prior)
            }
            val eligible=eligibility(valid.shopId,valid.saleId)
            val nets=RefundMath.netLines(eligible.sale.items.map{it.lineTotalPaise},eligible.sale.orderDiscountPaise)
            val plan=valid.lines.map { line ->
                val index=eligible.items.indexOfFirst{it.sold.saleItemId==line.saleItemId};require(index>=0){"An item does not belong to the original sale."}
                val row=eligible.items[index]
                Triple(line,row.sold,RefundMath.amount(nets[index],row.sold.quantity,row.returnedQuantity,line.quantity))
            }
            val total=plan.fold(0L){sum,row->Math.addExact(sum,row.third)}
            require(total==valid.expectedRefundPaise) { "The return quantities changed. Reload the sale and review the refund again." }
            val shop=requireNotNull(dao.findShop(valid.shopId));val timestamp=now()
            var number=dao.getReturnSequence(valid.shopId)?.nextValue ?: 1L
            var reference:String
            do { require(number>0 && number<Long.MAX_VALUE){"Return numbering is exhausted."};reference="${shop.code}-R-${number.toString().padStart(6,'0')}";number++ }while(dao.refundReferenceExists(valid.shopId,reference))
            dao.upsertReturnSequence(ReturnSequenceEntity(valid.shopId,number))
            val id=dao.insertReturn(ReturnEntity(saleId=valid.saleId,shopId=valid.shopId,reason=valid.reason,refundPaise=total,createdAt=timestamp,
                receiptNumber=reference,requestId=valid.requestId,requestFingerprint=fingerprint))
            for((line,item,amount) in plan) {
                val inventory=requireNotNull(dao.getInventory(valid.shopId,item.productId)){"Inventory for a returned product is missing."}
                val returnItemId=dao.insertReturnItem(ReturnItemEntity(returnId=id,saleId=valid.saleId,shopId=valid.shopId,saleItemId=line.saleItemId,
                    productId=item.productId,quantity=line.quantity,restockQuantity=line.restockQuantity,unitRefundPaise=amount/line.quantity,refundPaise=amount,createdAt=timestamp))
                if(line.restockQuantity>0) {
                    val after=Math.addExact(inventory.quantity,line.restockQuantity)
                    check(dao.updateInventoryQty(valid.shopId,item.productId,after,timestamp)==1)
                    dao.insertMovement(StockMovementEntity(shopId=valid.shopId,productId=item.productId,delta=line.restockQuantity,quantityAfter=after,
                        type="RETURN",reason="Return $reference: ${valid.reason}",createdAt=timestamp,returnItemId=returnItemId))
                }
            }
            dao.insertAudit(AuditEventEntity(entityType="RETURN",entityId=id,shopId=valid.shopId,action="POST_CASH_REFUND",beforeValue=null,
                afterValue=JSONObject().put("saleId",valid.saleId).put("refundPaise",total).put("reference",reference).toString(),createdAt=timestamp))
            details(requireNotNull(dao.getRefund(valid.shopId,id)))
        }
    }
    override suspend fun get(shopId:Long,id:Long):RefundDetails?=database.withTransaction { dao.getRefund(shopId,id)?.let{details(it)} }
    override suspend fun findRequest(shopId:Long,requestId:String):RefundDetails?=database.withTransaction { dao.findRefundRequest(requestId)?.takeIf{it.shopId==shopId}?.let{details(it)} }
    override fun observe(shopId:Long):Flow<List<RefundDetails>> = dao.observeRefunds(shopId).map { rows -> database.withTransaction {rows.map{details(it)}} }
    private suspend fun details(row:ReturnEntity):RefundDetails {
        val sale=requireNotNull(sales.getSale(row.shopId,row.saleId));val sold=sale.items.associateBy{it.saleItemId}
        return RefundDetails(row.id,row.shopId,row.saleId,row.receiptNumber ?: "Legacy return #${row.id}",sale.summary.receiptNumber,row.reason,row.refundPaise,row.createdAt,
            dao.getRefundItems(row.shopId,row.id).map { item ->
                val original=requireNotNull(sold[item.saleItemId])
                RefundedItem(item.saleItemId,item.productId,original.sku,original.name,original.variant,item.quantity,item.restockQuantity,item.refundPaise,original.unitCostPaise,original.category)
            })
    }
}

class RoomExpenseRepository(private val database:PosDatabase,private val now:()->Long=System::currentTimeMillis):ExpenseRepository {
    private val dao=database.posDao()
    override suspend fun add(draft:ExpenseDraft):ExpenseRecord {
        val valid=draft.copy(category=draft.category.trim(),description=draft.description.trim())
        require(valid.requestId.isNotBlank() && valid.requestId.length<=128 && valid.shopId>0) { "Choose a valid shop." }
        require(valid.amountPaise>0 && valid.category.length in 1..80 && valid.description.length<=240) { "Enter a positive amount, a category and a note of at most 240 characters." }
        val fp=RefundMath.sha256(JSONObject().put("shopId",valid.shopId).put("amount",valid.amountPaise).put("category",valid.category).put("description",valid.description).put("incurredAt",valid.incurredAt).toString())
        return database.withWriteTransaction {
            dao.findExpenseRequest(valid.requestId)?.let { prior ->
                require(prior.shopId==valid.shopId && prior.requestFingerprint==fp){"This expense identifier was used for a different entry."}
                return@withWriteTransaction prior.domain()
            }
            requireNotNull(dao.findShop(valid.shopId)){"Shop not found."};val timestamp=now()
            val id=dao.insertExpense(ExpenseEntity(shopId=valid.shopId,amountPaise=valid.amountPaise,category=valid.category,description=valid.description,
                incurredAt=valid.incurredAt,createdAt=timestamp,updatedAt=timestamp,requestId=valid.requestId,requestFingerprint=fp))
            dao.insertAudit(AuditEventEntity(entityType="EXPENSE",entityId=id,shopId=valid.shopId,action="ADD_EXPENSE",beforeValue=null,
                afterValue=JSONObject().put("amountPaise",valid.amountPaise).put("category",valid.category).put("incurredAt",valid.incurredAt).toString(),createdAt=timestamp))
            requireNotNull(dao.getExpense(valid.shopId,id)).domain()
        }
    }
    override suspend fun findRequest(shopId:Long,requestId:String):ExpenseRecord?=dao.findExpenseRequest(requestId)?.takeIf{it.shopId==shopId}?.domain()
    override suspend fun void(shopId:Long,id:Long,reason:String) {
        val valid=reason.trim();require(valid.length in 1..240){"Enter a void reason of 1–240 characters."}
        database.withWriteTransaction {
            val row=requireNotNull(dao.getExpense(shopId,id)){"Expense not found in this shop."}
            if(row.isVoided){require(row.voidReason==valid){"This expense is already voided."};return@withWriteTransaction}
            val timestamp=now();check(dao.updateExpense(row.copy(isVoided=true,voidReason=valid,voidedAt=timestamp,updatedAt=timestamp))==1)
            dao.insertAudit(AuditEventEntity(entityType="EXPENSE",entityId=id,shopId=shopId,action="VOID_EXPENSE",beforeValue=JSONObject().put("amountPaise",row.amountPaise).toString(),
                afterValue=JSONObject().put("reason",valid).toString(),createdAt=timestamp))
        }
    }
    override fun observe(shopId:Long)=dao.observeExpenses(shopId).map{rows->rows.map{it.domain()}}
    private fun ExpenseEntity.domain()=ExpenseRecord(id,shopId,amountPaise,category,description,incurredAt,isVoided,voidReason)
}

class RoomReportRepository(private val database:PosDatabase):ReportRepository {
    private val dao=database.posDao();private val sales=RoomSalesRepository(database);private val refunds=RoomRefundRepository(database)
    override fun observe(shopId:Long,start:Long,endExclusive:Long):Flow<FinancialReport> = dao.observeFinanceChanges(shopId).map { get(shopId,start,endExclusive) }
    override suspend fun get(shopId:Long,start:Long,endExclusive:Long):FinancialReport=database.withTransaction {
        require(endExclusive>start){"Choose a valid report date range."};requireNotNull(dao.findShop(shopId)){"Shop not found."}
        val posted=dao.reportSales(shopId,start,endExclusive);val returned=dao.reportRefunds(shopId,start,endExclusive);val expenses=dao.reportExpenses(shopId,start,endExclusive)
        var gross=0L;var refundsTotal=0L;var costs=0L;var expenseTotal=0L
        val productRows=linkedMapOf<String,ReportProduct>();val categories=linkedMapOf<String,Long>()
        fun record(sku:String,name:String,category:String,quantity:Long,amount:Long) {
            val prior=productRows[sku] ?: ReportProduct(sku,name,0,0)
            productRows[sku]=prior.copy(name=name,quantity=Math.addExact(prior.quantity,quantity),revenuePaise=Math.addExact(prior.revenuePaise,amount))
            val key=category.ifBlank{"Uncategorised (legacy)"};categories[key]=Math.addExact(categories[key] ?: 0,amount)
        }
        for(header in posted) {
            val sale=requireNotNull(sales.getSale(shopId,header.id));gross=Math.addExact(gross,sale.summary.totalPaise)
            val nets=RefundMath.netLines(sale.items.map{it.lineTotalPaise},sale.orderDiscountPaise)
            sale.items.forEachIndexed { index,item -> costs=Math.addExact(costs,Math.multiplyExact(item.unitCostPaise,item.quantity));record(item.sku,item.name,item.category,item.quantity,nets[index]) }
        }
        for(header in returned) {
            val refund=requireNotNull(refunds.get(shopId,header.id));refundsTotal=Math.addExact(refundsTotal,refund.refundPaise)
            for(item in refund.items) { costs=Math.subtractExact(costs,Math.multiplyExact(item.unitCostPaise,item.restockQuantity));record(item.sku,item.name,item.category,-item.quantity,-item.refundPaise) }
        }
        for(expense in expenses)expenseTotal=Math.addExact(expenseTotal,expense.amountPaise)
        var stockValue=0L;var lowStock=0
        // Snapshot current inventory separately from the selected transaction period.
        database.openHelper.readableDatabase.query("SELECT i.quantity,p.purchaseCostPaise,p.reorderLevel FROM inventory i JOIN shop_products p ON p.shopId=i.shopId AND p.productId=i.productId JOIN products r ON r.id=i.productId WHERE i.shopId=? AND p.isActive=1 AND r.isActive=1",arrayOf(shopId)).use { c->while(c.moveToNext()) {
            stockValue=Math.addExact(stockValue,Math.multiplyExact(c.getLong(0),c.getLong(1)));if(c.getLong(0)<=c.getLong(2))lowStock++
        } }
        val net=Math.subtractExact(gross,refundsTotal);val profit=Math.subtractExact(net,costs)
        FinancialReport(shopId,start,endExclusive,gross,refundsTotal,expenseTotal,net,costs,profit,Math.subtractExact(profit,expenseTotal),posted.size,returned.size,stockValue,lowStock,
            productRows.values.sortedByDescending{it.revenuePaise},categories.map{ReportCategory(it.key,it.value)}.sortedByDescending{it.revenuePaise})
    }
}
