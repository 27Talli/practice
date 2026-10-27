package com.offerpoint.pos.ui.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offerpoint.pos.domain.model.TodaySales
import com.offerpoint.pos.domain.repository.ReportRepository
import com.offerpoint.pos.domain.usecase.BusinessDay
import com.offerpoint.pos.ui.screens.TodaySalesState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class TodayNetSalesViewModel(repo:ReportRepository,shopId:Long):ViewModel(){
    private val mutable=MutableStateFlow(TodaySalesState());val state=mutable.asStateFlow();private val retry=MutableStateFlow(0)
    init{viewModelScope.launch{retry.flatMapLatest{flow{
        while(currentCoroutineContext().isActive){val now=System.currentTimeMillis();val bounds=BusinessDay.bounds(now);emit(bounds);delay(minOf(60_000L,(bounds.second-now).coerceAtLeast(1)))}
    }.distinctUntilChanged().flatMapLatest{(start,end)->repo.observe(shopId,start,end)}.map{TodaySalesState(false,TodaySales(it.netSalesPaise,it.saleCount))}.catch{e->if(e is CancellationException)throw e;emit(TodaySalesState(loading=false,error=e.message))}}.collect{mutable.value=it}}}
    fun retry(){retry.value++}
}
