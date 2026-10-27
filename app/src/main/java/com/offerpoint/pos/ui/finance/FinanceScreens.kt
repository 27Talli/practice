package com.offerpoint.pos.ui.finance

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.usecase.Money
import com.offerpoint.pos.domain.usecase.ReportCsv
import com.offerpoint.pos.ui.components.*
import com.offerpoint.pos.ui.screens.*
import com.offerpoint.pos.ui.scanner.BarcodeCaptureDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun financeDate(time:Long)=DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm",Locale.ENGLISH).withZone(ZoneId.of("Asia/Karachi")).format(Instant.ofEpochMilli(time))

@Composable
fun RefundScreen(shop:Shop,state:RefundState,onEdit:(Long,String,String)->Unit,onReason:(String)->Unit,onPost:()->Unit,onRetry:()->Unit,onSaved:(Long)->Unit,onBack:()->Unit){
    val locked=state.busy||state.recoveryRequired
    BackHandler(locked){}
    LaunchedEffect(state.completedId){state.completedId?.let(onSaved)}
    PosScaffold("Return items",shop.name,{if(!locked)onBack()}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            item{Text("Return & cash refund",style=MaterialTheme.typography.headlineMedium)}
            item{StatusCard("Original sale",state.eligible?.sale?.summary?.receiptNumber ?: "Checking the saved sale…")}
            item{Text("Refunds use the original selling prices and discounts. Restock only items that can be sold again. Enter zero for items you are keeping.")}
            state.eligible?.items?.let { rows->items(rows,key={it.sold.saleItemId}){row->
                val edit=state.edits[row.sold.saleItemId] ?: ReturnEdit()
                Card {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(row.sold.name,style=MaterialTheme.typography.titleMedium)
                    Text("${row.sold.sku} · Sold ${row.sold.quantity} · Already returned ${row.returnedQuantity}",style=MaterialTheme.typography.bodySmall)
                    Text("Remaining refundable amount: ${Money.format(row.refundablePaise)}")
                    OutlinedTextField(edit.quantity,{onEdit(row.sold.saleItemId,it,edit.restock)},label={Text("Quantity to return")},singleLine=true,enabled=!locked,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(edit.restock,{onEdit(row.sold.saleItemId,edit.quantity,it)},label={Text("Quantity to restock")},supportingText={Text("0 for damaged or unsaleable items")},singleLine=true,enabled=!locked,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                }}
            }}
            item{OutlinedTextField(state.reason,onReason,label={Text("Return reason (required)")},minLines=2,maxLines=4,enabled=!locked,modifier=Modifier.fillMaxWidth())}
            state.previewPaise?.let{amount->item{StatusCard("Cash refund to customer",Money.format(amount))}}
            if(state.error!=null)item{ErrorNotice(state.error)}
            if(state.recoveryRequired&&!state.busy)item{OutlinedButton(onClick=onRetry,modifier=Modifier.fillMaxWidth()){Text("Check previous return")}}
            if(state.loading||state.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth());Text("Checking or saving the return…")}
            item{Button(onClick=onPost,enabled=!locked&&state.previewPaise!=null&&state.reason.isNotBlank(),modifier=Modifier.fillMaxWidth().height(56.dp)){Text("Confirm cash refund")}}
        }
    }
}

@Composable
fun RefundHistoryScreen(shop:Shop,state:RefundHistoryState,onReference:(String)->Unit,onFind:()->Unit,onScan:(String)->Unit,onReturn:()->Unit,onRetry:()->Unit,onOpen:(Long)->Unit,onBack:()->Unit){
    var scanning by remember { mutableStateOf(false) }
    PosScaffold("Returns & refunds",shop.name,onBack){padding->LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text("Start a return",style=MaterialTheme.typography.headlineMedium);Text("Scan the original receipt QR or enter its number. Review the saved sale here before selecting items to return.")}
        item{OutlinedButton(onClick={scanning=true},enabled=!state.lookingUp,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("Scan receipt QR")}}
        item{OutlinedTextField(state.reference,onReference,label={Text("Original sale receipt number")},placeholder={Text("${shop.code}-S-000001")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{Button(onClick=onFind,enabled=!state.lookingUp&&state.reference.isNotBlank(),modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(if(state.lookingUp)"Finding receipt…" else "Find original sale")}}
        if(state.lookingUp)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        state.lookupError?.let{item{ErrorNotice(it)}}
        state.sale?.let{sale->item{StatusCard("Original sale found","${sale.summary.receiptNumber}\n${financeDate(sale.summary.completedAt)}\n${Money.format(sale.summary.totalPaise)}");Button(onClick=onReturn,modifier=Modifier.fillMaxWidth().padding(top=8.dp).heightIn(min=52.dp)){Text("Select items to return")}}}
        item{Text("Saved returns",style=MaterialTheme.typography.headlineMedium)}
        state.historyError?.let{item{ErrorNotice(it,onRetry)}}
        if(state.loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(state.rows.isEmpty()&&state.historyError==null&&!state.loading)item{EmptyState("No returns yet","Find an original sale receipt above to create a return.")}
        items(state.rows,key={it.id}){row->Card(onClick={onOpen(row.id)}){Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(row.receiptNumber,style=MaterialTheme.typography.titleMedium);Text("Original: ${row.originalReceiptNumber}")
            Text(financeDate(row.createdAt));Text("Refund ${Money.format(row.refundPaise)}",color=MaterialTheme.colorScheme.primary);Text(row.reason)
        }}}
    }}
    if(scanning)BarcodeCaptureDialog("Scan receipt QR","Point at the QR on the original sales receipt. This finds the saved sale; it does not issue a refund.",
        onCaptured={value,_->scanning=false;onScan(value)},onDismiss={scanning=false},receiptQrOnly=true)
}

@Composable
fun ExpenseScreen(shop:Shop,state:ExpenseState,onFields:(String,String,String,String)->Unit,onAdd:()->Unit,onRecover:()->Unit,onVoid:(Long,String)->Unit,onBack:()->Unit){
    var voiding by remember{mutableStateOf<ExpenseRecord?>(null)};var voidReason by remember{mutableStateOf("")}
    val locked=state.busy||state.recoveryRequired;BackHandler(locked){}
    PosScaffold("Expenses",shop.name,{if(!locked)onBack()}){padding->LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text("Record a shop expense",style=MaterialTheme.typography.headlineMedium)}
        item{OutlinedTextField(state.amount,{onFields(it,state.category,state.note,state.date)},label={Text("Amount (Rs.)")},singleLine=true,enabled=!locked,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(state.category,{onFields(state.amount,it,state.note,state.date)},label={Text("Category (e.g. Rent, Utilities)")},singleLine=true,enabled=!locked,modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(state.date,{onFields(state.amount,state.category,state.note,it)},label={Text("Expense date (YYYY-MM-DD)")},singleLine=true,enabled=!locked,modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(state.note,{onFields(state.amount,state.category,it,state.date)},label={Text("Note (optional)")},enabled=!locked,modifier=Modifier.fillMaxWidth())}
        if(state.error!=null)item{ErrorNotice(state.error)}
        if(state.notice!=null)item{StatusCard("Expense status",state.notice)}
        if(state.recoveryRequired&&!state.busy)item{OutlinedButton(onClick=onRecover,modifier=Modifier.fillMaxWidth()){Text("Check previous expense")}}
        item{Button(onClick=onAdd,enabled=!locked,modifier=Modifier.fillMaxWidth().height(54.dp)){Text(if(state.busy)"Saving…" else "Save expense")}}
        item{Text("Expense history",style=MaterialTheme.typography.titleLarge)}
        if(state.rows.isEmpty()&&!state.loading)item{EmptyState("No expenses recorded","Add actual shop expenses above. Voids keep the original record and a reason.")}
        items(state.rows,key={it.id}){row->Card{Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("${row.category} · ${Money.format(row.amountPaise)}",style=MaterialTheme.typography.titleMedium)
            Text(financeDate(row.incurredAt));if(row.description.isNotBlank())Text(row.description)
            if(row.isVoided)Text("VOIDED · ${row.voidReason}",color=MaterialTheme.colorScheme.error)
            else TextButton(onClick={voiding=row;voidReason=""},enabled=!locked){Text("Void incorrect expense")}
        }}}
    }}
    voiding?.let{row->AlertDialog(onDismissRequest={if(!locked)voiding=null},title={Text("Void this expense?")},text={Column{Text("${Money.format(row.amountPaise)} will be removed from reports; the original entry and audit history remain.");OutlinedTextField(voidReason,{voidReason=it},label={Text("Reason (required)")})}},confirmButton={TextButton(onClick={onVoid(row.id,voidReason);voiding=null},enabled=voidReason.isNotBlank()&&!locked){Text("Void expense")}},dismissButton={TextButton(onClick={voiding=null}){Text("Cancel")}})}
}

@Composable
fun ReportsScreen(shop:Shop,state:ReportState,onRange:(String,String)->Unit,onBack:()->Unit){
    val today=LocalDate.now(ZoneId.of("Asia/Karachi"))
    var from by rememberSaveable{mutableStateOf(today.toString())};var to by rememberSaveable{mutableStateOf(today.toString())}
    var exporting by remember{mutableStateOf(false)};var exportMessage by remember{mutableStateOf<String?>(null)}
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->if(uri!=null&&state.report!=null){val report=state.report;exporting=true;scope.launch{try{
        withContext(Dispatchers.IO){requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use{ReportCsv.write(it,shop,report)}};exportMessage="Report CSV saved. Keep financial exports private."
    }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;exportMessage=e.message ?: "The report could not be exported."}finally{exporting=false}}}}
    PosScaffold("Reports",shop.name,onBack){padding->LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{Text("Shop financial report",style=MaterialTheme.typography.headlineMedium);Text("Dates use Pakistan time. Sales and refunds are counted on their posting dates; expenses use the entered expense date.")}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=from==today.toString()&&to==today.toString(),onClick={from=today.toString();to=from;onRange(from,to)},label={Text("Today")})
            FilterChip(selected=from==today.minusDays(6).toString()&&to==today.toString(),onClick={from=today.minusDays(6).toString();to=today.toString();onRange(from,to)},label={Text("7 days")})
            FilterChip(selected=from==today.withDayOfMonth(1).toString()&&to==today.toString(),onClick={from=today.withDayOfMonth(1).toString();to=today.toString();onRange(from,to)},label={Text("Month")})
        }}
        item{OutlinedTextField(from,{from=it},label={Text("From (YYYY-MM-DD)")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(to,{to=it},label={Text("Through (YYYY-MM-DD)")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{Button(onClick={onRange(from,to)},modifier=Modifier.fillMaxWidth().height(52.dp)){Text("Apply dates")}}
        if(state.loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(state.error!=null)item{ErrorNotice(state.error)}
        state.report?.let{r->
            item{StatusCard("Saved activity","${r.saleCount} completed sales · ${r.refundCount} returns")}
            for((label,value) in listOf("Completed sales" to r.salesPaise,"Cash refunds" to r.refundsPaise,"Net sales" to r.netSalesPaise,"Recorded net cost of goods" to r.netCostPaise,
                "Gross profit" to r.grossProfitPaise,"Expenses" to r.expensesPaise,"Operating profit" to r.operatingProfitPaise,"Net operating cash movement" to r.cashMovementPaise))item{FinanceAmount(label,value)}
            item{Text("Profit uses recorded sale costs. Only restocked returns reverse cost of goods; unsaleable returned items retain their cost. Cash movement excludes opening float, owner drawings and other unrecorded cash.",style=MaterialTheme.typography.bodySmall)}
            item{StatusCard("Current inventory — not historical", "Cost value: ${Money.format(r.currentStockCostPaise)}\n${r.lowStockCount} products at or below their alert level")}
            item{OutlinedButton(onClick={export.launch("${shop.code}-report-$from-$to.csv")},enabled=!exporting,modifier=Modifier.fillMaxWidth().height(52.dp)){Text(if(exporting)"Exporting…" else "Export report CSV")};exportMessage?.let{Text(it)}}
            item{Text("Products by net revenue",style=MaterialTheme.typography.titleLarge)}
            items(r.products,key={it.sku}){row->StatusCard("${row.name} · ${row.sku}","${row.quantity} net units · ${Money.format(row.revenuePaise)}")}
            item{Text("Categories",style=MaterialTheme.typography.titleLarge)}
            items(r.categories,key={it.category}){row->FinanceAmount(row.category,row.revenuePaise)}
        }
    }}
}

@Composable private fun FinanceAmount(label:String,paise:Long){Card{Column(Modifier.fillMaxWidth().padding(16.dp)){Text(label,style=MaterialTheme.typography.labelLarge);Text(Money.format(paise),style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)}}}
