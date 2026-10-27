package com.offerpoint.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.repository.*
import com.offerpoint.pos.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FinanceDatabaseTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db:PosDatabase
    private lateinit var products:RoomProductRepository
    private lateinit var sales:RoomSalesRepository
    private lateinit var refunds:RoomRefundRepository
    private lateinit var expenses:RoomExpenseRepository
    private lateinit var reports:RoomReportRepository
    private var now=1_000L
    @Before fun before()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(context,PosDatabase::class.java).build();RoomShopRepository(db,false).initialize()
        products=RoomProductRepository(db);sales=RoomSalesRepository(db){now};refunds=RoomRefundRepository(db){now};expenses=RoomExpenseRepository(db){now};reports=RoomReportRepository(db)
    }
    @After fun after(){db.close()}
    private fun token()=UUID.randomUUID().toString()
    private fun draft(stock:Long=10,cost:Long=40,price:Long=100)=ProductDraft("Lipstick","Cosmetics","Rose",null,cost,price,stock,reorderLevel=2)
    private suspend fun sell(id:Long,quantity:Long=3,discount:Long=0)=sales.completeSale(CheckoutRequest(token(),1,listOf(CheckoutLine(id,quantity,100)),discount,quantity*100-discount))
    private fun request(sale:SaleDetails,quantity:Long,restock:Long=quantity,amount:Long=quantity*100)=RefundRequest(token(),1,sale.summary.id,listOf(RefundLine(sale.items.single().saleItemId,quantity,restock)),"Wrong colour",amount)
    private fun count(table:String)=db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use{it.moveToFirst();it.getLong(0)}
    private suspend fun failure(block:suspend()->Unit):Exception{try{block()}catch(e:Exception){return e};throw AssertionError("Expected failure")}

    @Test fun partialReturnsConserveDiscountedCashAndRestockOnlyChosenUnits()=runBlocking {
        val id=products.createProduct(1,draft());products.associateProduct(2,id,30,100,20)
        val sale=sell(id,3,200);assertEquals("Cosmetics",sale.items.single().category)
        val first=refunds.post(request(sale,1,0,33));assertEquals("OP-R-000001",first.receiptNumber)
        assertEquals(7L,products.getProduct(1,id)?.quantity)
        val second=refunds.post(request(sale,1,1,33));val third=refunds.post(request(sale,1,1,34))
        assertEquals(100L,listOf(first,second,third).sumOf{it.refundPaise});assertEquals(9L,products.getProduct(1,id)?.quantity)
        assertEquals(20L,products.getProduct(2,id)?.quantity);assertEquals(sale,sales.getSale(1,sale.summary.id))
        assertEquals(0L,refunds.eligibility(1,sale.summary.id).items.single().refundablePaise)
        assertEquals(2,db.posDao().getMovements(1,id).count{it.type=="RETURN"})
        assertTrue(db.posDao().getAudits().any{it.action=="POST_CASH_REFUND"})
    }
    @Test fun aReturnRetryIsIdempotentEvenWhenNothingRemainsToReturn()=runBlocking {
        val id=products.createProduct(1,draft());val sale=sell(id);val request=request(sale,3)
        val posted=refunds.post(request);assertEquals(posted,refunds.post(request));assertEquals(posted,refunds.findRequest(1,request.requestId))
        assertEquals(1L,count("returns"));assertEquals(1L,count("return_items"));assertEquals(10L,products.getProduct(1,id)?.quantity)
        assertEquals(2L,db.posDao().getReturnSequence(1)?.nextValue)
    }
    @Test fun concurrentDuplicateReturnRequestsOnlyRefundAndRestockOnce()=runBlocking {
        val id=products.createProduct(1,draft());val sale=sell(id);val request=request(sale,2)
        val posted=coroutineScope{(1..6).map{async(Dispatchers.IO){refunds.post(request)}}.awaitAll()}
        assertEquals(1,posted.map{it.id}.toSet().size);assertEquals(1L,count("returns"));assertEquals(9L,products.getProduct(1,id)?.quantity)
    }
    @Test fun distinctConcurrentReturnsCannotExceedTheOriginalSoldQuantity()=runBlocking {
        val id=products.createProduct(1,draft());val sale=sell(id,1)
        val outcomes=coroutineScope{(1..2).map{async(Dispatchers.IO){runCatching{refunds.post(request(sale,1))}}}.awaitAll()}
        assertEquals(1,outcomes.count{it.isSuccess});assertEquals(1L,count("returns"));assertEquals(10L,products.getProduct(1,id)?.quantity)
    }
    @Test fun invalidAmountRestockQuantityShopAndOriginalItemNeverPost()=runBlocking {
        val id=products.createProduct(1,draft());val sale=sell(id);val valid=request(sale,1)
        for(bad in listOf(valid.copy(expectedRefundPaise=99),valid.copy(shopId=2),valid.copy(lines=listOf(RefundLine(valid.lines.single().saleItemId,4,4))),
            valid.copy(lines=listOf(RefundLine(valid.lines.single().saleItemId,1,2))),valid.copy(lines=listOf(RefundLine(99999,1,1))),valid.copy(reason=""))){failure{refunds.post(bad)}}
        assertEquals(0L,count("returns"));assertEquals(7L,products.getProduct(1,id)?.quantity);assertNull(db.posDao().getReturnSequence(1))
    }
    @Test fun returnRequestCannotBeReusedForChangedRestockOrReason()=runBlocking {
        val id=products.createProduct(1,draft());val sale=sell(id);val request=request(sale,1);refunds.post(request)
        failure{refunds.post(request.copy(reason="Changed"))};failure{refunds.post(request.copy(lines=listOf(request.lines.single().copy(restockQuantity=0))))}
        assertEquals(1L,count("returns"));assertNull(refunds.get(2,1));assertNull(refunds.findRequest(2,request.requestId))
    }
    @Test fun failedReturnLedgerRollsBackRefundStockAuditAndNumberTogether()=runBlocking {
        val id=products.createProduct(1,draft());val sale=sell(id);val request=request(sale,2);val audits=count("audit_events")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_return BEFORE INSERT ON stock_movements WHEN NEW.type='RETURN' BEGIN SELECT RAISE(ABORT,'test failure'); END")
        failure{refunds.post(request)}
        assertEquals(0L,count("returns"));assertEquals(0L,count("return_items"));assertEquals(7L,products.getProduct(1,id)?.quantity)
        assertEquals(audits,count("audit_events"));assertNull(db.posDao().getReturnSequence(1))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_return")
        assertEquals("OP-R-000001",refunds.post(request).receiptNumber)
    }
    @Test fun aMultiLineOrderDiscountIsAllocatedExactlyForACompleteRefund()=runBlocking {
        val first=products.createProduct(1,draft());val second=products.createProduct(1,draft());val third=products.createProduct(1,draft())
        val sale=sales.completeSale(CheckoutRequest(token(),1,listOf(CheckoutLine(first,1,100),CheckoutLine(second,1,100),CheckoutLine(third,1,100)),100,200))
        val all=sale.items.map{RefundLine(it.saleItemId,1,1)}
        val refund=refunds.post(RefundRequest(token(),1,sale.summary.id,all,"Full return",200))
        assertEquals(listOf(66L,67L,67L),refund.items.map{it.refundPaise});assertEquals(200L,refund.refundPaise)
    }
    @Test fun reportUsesSnapshotsPostingDatesSellableReturnsAndActiveExpenses()=runBlocking {
        val id=products.createProduct(1,draft());val sale=sell(id,3,1)
        products.updateProduct(1,id,draft().copy(category="New category",purchaseCostPaise=999))
        now=2_000;refunds.post(request(sale,1,0,99));refunds.post(request(sale,1,1,100))
        val expense=expenses.add(ExpenseDraft(token(),1,50,"Utilities","Test",2_000));expenses.add(ExpenseDraft(token(),2,900,"Rent","Other shop",2_000))
        val report=reports.get(1,1_000,3_000)
        assertEquals(299L,report.salesPaise);assertEquals(199L,report.refundsPaise);assertEquals(100L,report.netSalesPaise)
        assertEquals(80L,report.netCostPaise);assertEquals(20L,report.grossProfitPaise);assertEquals(-30L,report.operatingProfitPaise);assertEquals(50L,report.cashMovementPaise)
        assertEquals(1L,report.products.single().quantity);assertEquals(100L,report.products.single().revenuePaise);assertEquals("Cosmetics",report.categories.single().category)
        assertEquals(7_992L,report.currentStockCostPaise)
        val returnsOnly=reports.get(1,2_000,3_000);assertEquals(0L,returnsOnly.salesPaise);assertEquals(-199L,returnsOnly.netSalesPaise);assertEquals(-40L,returnsOnly.netCostPaise)
        expenses.void(1,expense.id,"Entered twice");assertEquals(0L,reports.get(1,1_000,3_000).expensesPaise)
        assertEquals(900L,reports.get(2,1_000,3_000).expensesPaise)
    }
    @Test fun expenseIdempotencyAndVoidsKeepOriginalRowsAndAnAuditTrail()=runBlocking {
        val draft=ExpenseDraft(token(),1,12345,"Rent","October",now)
        val record=expenses.add(draft);assertEquals(record,expenses.add(draft));assertEquals(1L,count("expenses"))
        failure{expenses.add(draft.copy(amountPaise=12346))};failure{expenses.void(2,record.id,"Wrong shop")}
        expenses.void(1,record.id,"Incorrect amount");expenses.void(1,record.id,"Incorrect amount")
        val stored=expenses.observe(1).first().single();assertTrue(stored.isVoided);assertEquals(12345L,stored.amountPaise)
        assertEquals(1,db.posDao().getAudits().count{it.action=="VOID_EXPENSE"});assertTrue(expenses.observe(2).first().isEmpty())
    }
    @Test fun expenseAuditFailureRollsBackTheInsertAndVoid()=runBlocking {
        val record=expenses.add(ExpenseDraft(token(),1,100,"Rent","",now))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_expense_audit BEFORE INSERT ON audit_events WHEN NEW.entityType='EXPENSE' BEGIN SELECT RAISE(ABORT,'test failure'); END")
        failure{expenses.add(ExpenseDraft(token(),1,200,"Rent","",now))};failure{expenses.void(1,record.id,"Incorrect")}
        assertEquals(1L,count("expenses"));assertFalse(expenses.observe(1).first().single().isVoided)
    }
    @Test fun aVoidInvalidatesTheReportFlowEvenWhenRowCountsDoNotChange()=runBlocking {
        val record=expenses.add(ExpenseDraft(token(),1,100,"Utilities","",now))
        assertEquals(100L,reports.observe(1,0,10_000).first().expensesPaise)
        val update=async{withTimeout(10_000){reports.observe(1,0,10_000).first{it.expensesPaise==0L}}}
        expenses.void(1,record.id,"Wrong amount");assertEquals(0L,update.await().expensesPaise)
    }
    @Test fun invalidExpensesAreRejectedAndCurrentLowStockAlertsAreShopSpecific()=runBlocking {
        val valid=ExpenseDraft(token(),1,100,"Rent","",now)
        for(bad in listOf(valid.copy(amountPaise=0),valid.copy(amountPaise=-1),valid.copy(category=" "),valid.copy(description="X".repeat(241))))failure{expenses.add(bad)}
        val id=products.createProduct(1,draft(stock=2));products.associateProduct(2,id,40,100,10,1)
        assertEquals(1,reports.get(1,0,10_000).lowStockCount);assertEquals(0,reports.get(2,0,10_000).lowStockCount)
        assertEquals(80L,reports.get(1,0,10_000).currentStockCostPaise);assertEquals(0L,count("expenses"))
    }
    @Test fun completedRefundAndExpenseRemainIdempotentAfterReopening()=runBlocking {
        val name="finance-persistence-${token()}.db";var disk:PosDatabase?=null
        try {
            disk=Room.databaseBuilder(context,PosDatabase::class.java,name).build();RoomShopRepository(disk,false).initialize()
            val id=RoomProductRepository(disk).createProduct(1,draft());val sale=RoomSalesRepository(disk){now}.completeSale(CheckoutRequest(token(),1,listOf(CheckoutLine(id,1,100)),0,100))
            val request=request(sale,1);val returned=RoomRefundRepository(disk){now}.post(request);val draft=ExpenseDraft(token(),1,100,"Rent","",now);val expense=RoomExpenseRepository(disk){now}.add(draft)
            disk.close();disk=Room.databaseBuilder(context,PosDatabase::class.java,name).build()
            assertEquals(returned,RoomRefundRepository(disk).post(request));assertEquals(expense,RoomExpenseRepository(disk).add(draft))
            assertEquals(10L,RoomProductRepository(disk).getProduct(1,id)?.quantity)
        } finally{disk?.close();context.deleteDatabase(name)}
    }
    @Test fun unrepresentableCostOfGoodsRejectsCheckoutBeforeAnyFinancialMutation()=runBlocking {
        val id=products.createProduct(1,draft(cost=Long.MAX_VALUE));failure{sell(id,2)}
        assertEquals(0L,count("sales"));assertEquals(10L,products.getProduct(1,id)?.quantity);assertNull(db.posDao().getSaleSequence(1))
    }
}
