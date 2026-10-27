package com.offerpoint.pos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.*
import com.offerpoint.pos.ui.finance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<androidx.lifecycle.ViewModel>()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){models.forEach{it.viewModelScope.cancel()};Dispatchers.resetMain()}
    private fun refund(repo:FakeRefunds,saved:SavedStateHandle=SavedStateHandle())=RefundViewModel(repo,1,1,saved).also{models.add(it)}
    private fun expense(repo:FakeExpenses,saved:SavedStateHandle=SavedStateHandle())=ExpenseViewModel(repo,1,saved).also{models.add(it)}

    @Test fun returnPreviewUsesOriginalDiscountAndRejectsOverReturnAndOverRestock()=runTest(dispatcher){
        val vm=refund(FakeRefunds());runCurrent()
        vm.edit(9,"1","1");assertEquals(33L,vm.state.value.previewPaise)
        vm.edit(9,"4","1");assertNull(vm.state.value.previewPaise)
        vm.edit(9,"1","2");assertNull(vm.state.value.previewPaise)
    }
    @Test fun doubleTapReturnPostsOnlyOnceAndUsesTheReviewedAmount()=runTest(dispatcher){
        val repo=FakeRefunds();val vm=refund(repo);runCurrent();vm.edit(9,"3","2");vm.reason("Wrong colour")
        vm.post();vm.post();runCurrent()
        assertEquals(1,repo.posts);assertEquals(100L,repo.request?.expectedRefundPaise);assertEquals(10L,vm.state.value.completedId)
    }
    @Test fun aLostReturnAcknowledgementRecoversTheCommittedReturnWithoutPostingAgain()=runTest(dispatcher){
        val repo=FakeRefunds().apply{loseAcknowledgement=true};val saved=SavedStateHandle();val vm=refund(repo,saved);runCurrent()
        vm.edit(9,"1","1");vm.reason("Size");vm.post();runCurrent()
        assertEquals(1,repo.posts);assertEquals(10L,vm.state.value.completedId);assertFalse(vm.state.value.recoveryRequired)
        val recreated=refund(repo,saved);runCurrent();assertEquals(10L,recreated.state.value.completedId);assertEquals(1,repo.posts)
    }
    @Test fun uncertainReturnLookupBlocksEditsAndNewPostingUntilRecoveryWorks()=runTest(dispatcher){
        val repo=FakeRefunds();val vm=refund(repo);runCurrent();vm.edit(9,"1","1");vm.reason("Size")
        repo.failPost=true;repo.failLookup=true;vm.post();runCurrent()
        assertTrue(vm.state.value.recoveryRequired);vm.edit(9,"2","2");vm.post();assertEquals("1",vm.state.value.edits[9]?.quantity);assertEquals(1,repo.posts)
        repo.failLookup=false;repo.failPost=false;vm.recover();runCurrent();assertFalse(vm.state.value.recoveryRequired)
        vm.post();runCurrent();assertEquals(10L,vm.state.value.completedId);assertEquals(2,repo.posts)
    }
    @Test fun expenseDoubleTapAndLostAcknowledgementSaveOneExpenseAndClearTheForm()=runTest(dispatcher){
        val repo=FakeExpenses().apply{loseAcknowledgement=true};val vm=expense(repo);runCurrent()
        vm.fields("150.25","Utilities","Electricity","2026-10-27");vm.add();vm.add();runCurrent()
        assertEquals(1,repo.adds);assertEquals(15025L,repo.draft?.amountPaise);assertEquals("",vm.state.value.amount);assertNotNull(vm.state.value.notice)
    }
    @Test fun expenseRecoveryAfterRecreationFindsTheOldRequestBeforeAcceptingNewInput()=runTest(dispatcher){
        val repo=FakeExpenses().apply{loseAcknowledgement=true;failLookup=true};val saved=SavedStateHandle();val vm=expense(repo,saved);runCurrent()
        vm.fields("100","Rent","","2026-10-27");vm.add();runCurrent();assertTrue(vm.state.value.recoveryRequired)
        repo.failLookup=false;val restored=expense(repo,saved);runCurrent();assertFalse(restored.state.value.recoveryRequired);assertEquals("",restored.state.value.amount);assertEquals(1,repo.adds)
    }
    @Test fun invalidExpenseDateOrMoneyNeverCallsTheRepository()=runTest(dispatcher){
        val repo=FakeExpenses();val vm=expense(repo);runCurrent()
        for((money,date) in listOf("1.001" to "2026-10-27","100" to "2026-02-30","-1" to "2026-10-27")){
            vm.fields(money,"Rent","",date);vm.add();runCurrent();assertNotNull(vm.state.value.error)
        };assertEquals(0,repo.adds)
    }
    @Test fun reportDatesUsePakistanMidnightAndRejectReversedRanges()=runTest(dispatcher){
        val repo=FakeReports();val vm=ReportViewModel(repo,1).also{models.add(it)};runCurrent()
        vm.load("2026-10-27","2026-10-27");runCurrent()
        assertEquals(java.time.Instant.parse("2026-10-26T19:00:00Z").toEpochMilli(),repo.start)
        assertEquals(java.time.Instant.parse("2026-10-27T19:00:00Z").toEpochMilli(),repo.end)
        vm.load("2026-10-28","2026-10-27");assertNotNull(vm.state.value.error)
    }

    private class FakeRefunds:RefundRepository {
        var posts=0;var failPost=false;var failLookup=false;var loseAcknowledgement=false;var request:RefundRequest?=null;var posted:RefundDetails?=null
        private val sold=SoldItem(1,"OP-000001","Lipstick","",3,100,50,0,300,9,"Cosmetics")
        private val sale=SaleDetails(SaleSummary(1,1,"OP-S-000001",100,0),300,200,200,100,0,"CASH",listOf(sold))
        override suspend fun eligibility(shopId:Long,saleId:Long)=ReturnEligibility(sale,listOf(ReturnableItem(sold,0,100)))
        override suspend fun post(request:RefundRequest):RefundDetails {posts++;this.request=request;if(failPost)error("Save failed")
            val line=request.lines.single();posted=RefundDetails(10,1,1,"OP-R-000001","OP-S-000001",request.reason,request.expectedRefundPaise,0,listOf(RefundedItem(9,1,sold.sku,sold.name,"",line.quantity,line.restockQuantity,request.expectedRefundPaise,50,"Cosmetics")))
            if(loseAcknowledgement)error("Reply lost");return posted!!}
        override suspend fun get(shopId:Long,id:Long)=posted
        override suspend fun findRequest(shopId:Long,requestId:String):RefundDetails?{if(failLookup)error("Lookup unavailable");return posted?.takeIf{request?.requestId==requestId}}
        override fun observe(shopId:Long)=flowOf(listOfNotNull(posted))
    }
    private class FakeExpenses:ExpenseRepository {
        var adds=0;var loseAcknowledgement=false;var failLookup=false;var draft:ExpenseDraft?=null;private var row:ExpenseRecord?=null
        override suspend fun add(draft:ExpenseDraft):ExpenseRecord {adds++;this.draft=draft;row=ExpenseRecord(1,draft.shopId,draft.amountPaise,draft.category,draft.description,draft.incurredAt,false,null);if(loseAcknowledgement)error("Reply lost");return row!!}
        override suspend fun findRequest(shopId:Long,requestId:String):ExpenseRecord?{if(failLookup)error("Lookup unavailable");return row?.takeIf{draft?.requestId==requestId}}
        override suspend fun void(shopId:Long,id:Long,reason:String){}
        override fun observe(shopId:Long)=flowOf(emptyList<ExpenseRecord>())
    }
    private class FakeReports:ReportRepository {
        var start=0L;var end=0L
        override fun observe(shopId:Long,start:Long,endExclusive:Long):Flow<FinancialReport>{this.start=start;end=endExclusive;return emptyFlow()}
        override suspend fun get(shopId:Long,start:Long,endExclusive:Long):FinancialReport=error("Not used")
    }
}
