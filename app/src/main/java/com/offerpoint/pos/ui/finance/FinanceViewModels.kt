package com.offerpoint.pos.ui.finance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.*
import com.offerpoint.pos.domain.usecase.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class ReturnEdit(val quantity:String="0",val restock:String="0")
data class RefundState(val loading:Boolean=true,val eligible:ReturnEligibility?=null,val edits:Map<Long,ReturnEdit> = emptyMap(),
    val reason:String="",val previewPaise:Long?=null,val busy:Boolean=false,val recoveryRequired:Boolean=false,val error:String?=null,val completedId:Long?=null)

class RefundViewModel(private val repository:RefundRepository,private val shopId:Long,private val saleId:Long,private val saved:SavedStateHandle):ViewModel() {
    private var requestId:String=saved["returnRequestId"] ?: UUID.randomUUID().toString()
    private val mutable=MutableStateFlow(RefundState(reason=saved["reason"] ?: "",recoveryRequired=saved["pending"] ?: false))
    val state=mutable.asStateFlow()
    init { recover() }
    fun edit(id:Long,quantity:String,restock:String){if(state.value.busy||state.value.recoveryRequired)return;mutable.update{it.copy(edits=it.edits+(id to ReturnEdit(quantity,restock)),error=null)};persist();recalculate()}
    fun reason(value:String){if(!state.value.busy&&!state.value.recoveryRequired){mutable.update{it.copy(reason=value)};saved["reason"]=value}}
    private fun request():RefundRequest {
        val current=state.value;val eligible=requireNotNull(current.eligible)
        val nets=RefundMath.netLines(eligible.sale.items.map{it.lineTotalPaise},eligible.sale.orderDiscountPaise)
        var amount=0L
        val lines=eligible.items.mapIndexedNotNull { index,item ->
            val edit=current.edits[item.sold.saleItemId] ?: ReturnEdit()
            val quantity=Quantity.parse(edit.quantity.ifBlank{"0"});val restock=Quantity.parse(edit.restock.ifBlank{"0"})
            require(restock<=quantity){"Restock quantity cannot exceed returned quantity."}
            if(quantity==0L)null else { amount=Math.addExact(amount,RefundMath.amount(nets[index],item.sold.quantity,item.returnedQuantity,quantity));RefundLine(item.sold.saleItemId,quantity,restock) }
        }
        require(lines.isNotEmpty()){ "Choose at least one returned item." }
        return RefundRequest(requestId,shopId,saleId,lines,current.reason,amount)
    }
    private fun recalculate(){try{val value=request();mutable.update{it.copy(previewPaise=value.expectedRefundPaise)}}catch(e:Exception){mutable.update{it.copy(previewPaise=null,error=e.message)}}}
    private fun persist(){saved["returnRequestId"]=requestId;saved["reason"]=state.value.reason;saved["ids"]=state.value.edits.keys.toLongArray();saved["quantities"]=ArrayList(state.value.edits.values.map{it.quantity});saved["restocks"]=ArrayList(state.value.edits.values.map{it.restock})}
    fun post(){
        if(state.value.busy||state.value.recoveryRequired)return
        val request=try{RefundMath.validate(request())}catch(e:Exception){mutable.update{it.copy(error=e.message)};return}
        persist();saved["pending"]=true;mutable.update{it.copy(busy=true,recoveryRequired=true,error=null)}
        viewModelScope.launch {
            try { val posted=repository.post(request);saved["pending"]=false;mutable.update{it.copy(completedId=posted.id,busy=false,recoveryRequired=false)} }
            catch(e:Exception){if(e is CancellationException)throw e;mutable.update{it.copy(busy=false,recoveryRequired=true)};recover();mutable.update{it.copy(error=if(e is ArithmeticException)"This return exceeds the supported stock or amount range. Review the quantities." else e.message)}}
        }
    }
    fun recover(){
        if(state.value.busy)return
        mutable.update{it.copy(busy=true,error=null)}
        viewModelScope.launch {
            try {
                val posted=repository.findRequest(shopId,requestId)
                if(posted!=null){saved["pending"]=false;mutable.update{it.copy(loading=false,busy=false,recoveryRequired=false,completedId=posted.id)};return@launch}
                val eligible=repository.eligibility(shopId,saleId)
                val ids=saved.get<LongArray>("ids") ?: longArrayOf();val quantities=saved.get<ArrayList<String>>("quantities") ?: arrayListOf();val restocks=saved.get<ArrayList<String>>("restocks") ?: arrayListOf()
                val restored=if(ids.size==quantities.size&&ids.size==restocks.size)ids.indices.associate{ids[it] to ReturnEdit(quantities[it],restocks[it])}else emptyMap()
                saved["pending"]=false;mutable.update{it.copy(loading=false,busy=false,recoveryRequired=false,eligible=eligible,edits=restored)};persist();recalculate()
            }catch(e:Exception){if(e is CancellationException)throw e;mutable.update{it.copy(loading=false,busy=false,recoveryRequired=true,error=e.message ?: "The return could not be checked.")}}
        }
    }
}

data class ExpenseState(val rows:List<ExpenseRecord> = emptyList(),val loading:Boolean=true,val amount:String="",val category:String="",val note:String="",
    val date:String=LocalDate.now(ZoneId.of("Asia/Karachi")).toString(),val busy:Boolean=false,val recoveryRequired:Boolean=false,val error:String?=null,val notice:String?=null)
class ExpenseViewModel(private val repo:ExpenseRepository,private val shopId:Long,private val saved:SavedStateHandle):ViewModel() {
    private var id:String=saved["expenseRequestId"] ?: UUID.randomUUID().toString()
    private val mutable=MutableStateFlow(ExpenseState(amount=saved["amount"] ?: "",category=saved["category"] ?: "",note=saved["note"] ?: "",date=saved["date"] ?: LocalDate.now(ZoneId.of("Asia/Karachi")).toString(),recoveryRequired=saved["pending"] ?: false))
    val state=mutable.asStateFlow()
    init {
        viewModelScope.launch {repo.observe(shopId).catch{e->if(e is CancellationException)throw e;mutable.update{it.copy(loading=false,error=e.message)}}.collect{rows->mutable.update{it.copy(loading=false,rows=rows)}}}
        if(saved.get<Boolean>("pending")==true)recover()
    }
    fun fields(amount:String,category:String,note:String,date:String){if(state.value.busy||state.value.recoveryRequired)return;mutable.update{it.copy(amount=amount,category=category,note=note,date=date,error=null)};saved["amount"]=amount;saved["category"]=category;saved["note"]=note;saved["date"]=date}
    fun add(){
        if(state.value.busy||state.value.recoveryRequired)return
        val current=state.value
        val draft=try{ExpenseDraft(id,shopId,Money.parse(current.amount),current.category,current.note,LocalDate.parse(current.date).atTime(12,0).atZone(ZoneId.of("Asia/Karachi")).toInstant().toEpochMilli())}catch(e:Exception){mutable.update{it.copy(error="Enter a valid amount and date (YYYY-MM-DD).")};return}
        saved["expenseRequestId"]=id;saved["pending"]=true;mutable.update{it.copy(busy=true,recoveryRequired=true,error=null,notice=null)}
        viewModelScope.launch {try{repo.add(draft);complete()}catch(e:Exception){if(e is CancellationException)throw e;mutable.update{it.copy(busy=false,error=e.message)};recover()}}
    }
    private fun complete(){id=UUID.randomUUID().toString();saved["expenseRequestId"]=id;saved["pending"]=false;saved["amount"]="";saved["category"]="";saved["note"]="";mutable.update{it.copy(busy=false,recoveryRequired=false,amount="",category="",note="",error=null,notice="Expense saved once in this shop’s records.")}}
    fun recover(){if(state.value.busy)return;mutable.update{it.copy(busy=true)};viewModelScope.launch{try{if(repo.findRequest(shopId,id)!=null)complete()else{saved["pending"]=false;mutable.update{it.copy(busy=false,recoveryRequired=false)}}}catch(e:Exception){if(e is CancellationException)throw e;mutable.update{it.copy(busy=false,recoveryRequired=true,error=e.message)}}}}
    fun void(id:Long,reason:String){if(state.value.busy||state.value.recoveryRequired)return;mutable.update{it.copy(busy=true,error=null)};viewModelScope.launch{try{repo.void(shopId,id,reason);mutable.update{it.copy(notice="Expense voided; its audit history is retained.")}}catch(e:Exception){if(e is CancellationException)throw e;mutable.update{it.copy(error=e.message)}}finally{mutable.update{it.copy(busy=false)}}}}
}

data class ReportState(val loading:Boolean=true,val report:FinancialReport?=null,val error:String?=null)
class ReportViewModel(private val repo:ReportRepository,private val shopId:Long):ViewModel(){
    private val mutable=MutableStateFlow(ReportState());val state=mutable.asStateFlow();private var job:kotlinx.coroutines.Job?=null
    init{val date=LocalDate.now(ZoneId.of("Asia/Karachi"));load(date.toString(),date.toString())}
    fun load(from:String,to:String){
        val range=try{val a=LocalDate.parse(from);val b=LocalDate.parse(to);require(!b.isBefore(a));a.atStartOfDay(ZoneId.of("Asia/Karachi")).toInstant().toEpochMilli() to b.plusDays(1).atStartOfDay(ZoneId.of("Asia/Karachi")).toInstant().toEpochMilli()}catch(e:Exception){mutable.update{it.copy(error="Use a valid date range (YYYY-MM-DD), with the end on or after the start.")};return}
        job?.cancel();mutable.value=ReportState();job=viewModelScope.launch{repo.observe(shopId,range.first,range.second).catch{e->if(e is CancellationException)throw e;mutable.value=ReportState(loading=false,error=if(e is ArithmeticException)"These totals exceed the supported amount range. Narrow the dates or review recorded values." else e.message ?: "The report could not be calculated.")}.collect{mutable.value=ReportState(false,it)}}
    }
}
