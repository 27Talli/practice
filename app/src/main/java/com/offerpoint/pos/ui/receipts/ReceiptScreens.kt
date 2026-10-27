package com.offerpoint.pos.ui.receipts

import android.content.ClipData
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offerpoint.pos.BuildConfig
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.ReceiptSettingsRepository
import com.offerpoint.pos.domain.repository.SalesRepository
import com.offerpoint.pos.ui.components.ErrorNotice
import com.offerpoint.pos.ui.components.StatusCard
import com.offerpoint.pos.ui.screens.LoadingContent
import com.offerpoint.pos.ui.screens.PosScaffold
import com.offerpoint.pos.ui.screens.PosViewModelFactory
import com.offerpoint.pos.ui.printing.PrinterSettings
import com.offerpoint.pos.ui.printing.ThermalPrinter
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@Composable
fun ReceiptScreen(shop: Shop, saleId: Long, sales: SalesRepository, preferences: ReceiptSettingsRepository, onBack: () -> Unit) {
    val vm: ReceiptViewModel = viewModel(key = "receipt-${shop.id}-$saleId", factory = PosViewModelFactory { ReceiptViewModel(sales, preferences, shop, saleId, BuildConfig.DEMO_MODE) })
    ReceiptContent(shop,vm,preferences,onBack)
}

@Composable
fun RefundReceiptScreen(shop:Shop,returnId:Long,refunds:com.offerpoint.pos.domain.repository.RefundRepository,preferences:ReceiptSettingsRepository,onBack:()->Unit){
    val vm:RefundReceiptViewModel=viewModel(key="refund-receipt-${shop.id}-$returnId",factory=PosViewModelFactory{RefundReceiptViewModel(refunds,preferences,shop,returnId,BuildConfig.DEMO_MODE)})
    ReceiptContent(shop,vm,preferences,onBack)
}

@Composable
private fun ReceiptContent(shop:Shop,vm:ReceiptDocumentViewModel,preferences:ReceiptSettingsRepository,onBack:()->Unit){
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var printing by remember { mutableStateOf(false) }
    var printerNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm, context) {
        vm.pdfExports.collect { file ->
            try {
                val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("Sale receipt", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share receipt PDF").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (error: Exception) { vm.reportError(error.message ?: "No application could share the PDF. Try Android printing instead.") }
        }
    }
    PosScaffold("Receipt", shop.name, onBack) { padding ->
        when {
            state.loading -> Column(Modifier.fillMaxSize().padding(padding)) { LoadingContent("Preparing the saved receipt…") }
            state.receipt == null -> Column(Modifier.padding(padding).padding(20.dp)) { ErrorNotice(state.error ?: "Receipt unavailable.", vm::load) }
            else -> {
                val receipt = state.receipt!!
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Text("Receipt preview", style = MaterialTheme.typography.headlineMedium) }
                    item { StatusCard("Saved record", "Reprinting or sharing uses this same receipt reference. Your stock and transactions remain unchanged.") }
                    item { PaperChoices(state.paper, vm::paper, !state.exporting) }
                    item {
                        Button(onClick = { vm.export(context) }, enabled = !state.exporting, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (state.exporting) "Creating PDF…" else "Share receipt PDF") }
                        OutlinedButton(onClick = {
                            try { ReceiptPrinting.print(context, receipt, state.paper) }
                            catch (error: Exception) { vm.reportError(error.message ?: "Android printing could not open. Share the PDF instead.") }
                        }, enabled = !state.exporting, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(54.dp)) { Text("Print receipt") }
                        Text("Choose a compatible printer or Save as PDF in Android’s print dialog. Printer availability depends on the installed print service.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            printing = true; printerNotice = null
                            scope.launch {
                                try {
                                    val settings = preferences.getSettings(shop.id)
                                    require(settings.printerMode != PrinterMode.ANDROID) { "Select a Bluetooth or USB printer in Settings → Receipts & printing first." }
                                    ThermalPrinter(context).send(receipt, settings)
                                    printerNotice = "Receipt data sent. Check the physical printer before printing another copy."
                                } catch (error: Exception) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    vm.reportError(error.message ?: "The printer could not be reached. Check the paper before retrying.")
                                } finally { printing = false }
                            }
                        }, enabled = !printing && !state.exporting, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(54.dp)) { Text(if (printing) "Sending to printer…" else "Print to saved thermal printer") }
                        if (printerNotice != null) StatusCard("Printer status", printerNotice!!)
                    }
                    if (state.error != null) item { ErrorNotice(state.error!!) }
                    item {
                        Surface(color = Color.White, contentColor = Color.Black, tonalElevation = 0.dp) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (receipt.demo) Text("DEMO - NOT A REAL SALE", style = MaterialTheme.typography.titleMedium)
                                Text(receipt.shopName, style = MaterialTheme.typography.titleLarge)
                                Text(receipt.shopAddress, style = MaterialTheme.typography.bodySmall)
                                Text(receipt.title, style = MaterialTheme.typography.titleMedium)
                                Text("Receipt: ${receipt.receiptNumber}")
                                Text(receipt.soldAt, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    items(receipt.lines) { line ->
                        Surface(color = Color.White, contentColor = Color.Black) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                                val style = when (line.style) {
                                    ReceiptLineStyle.TOTAL -> MaterialTheme.typography.titleLarge
                                    ReceiptLineStyle.EMPHASIS -> MaterialTheme.typography.titleMedium
                                    ReceiptLineStyle.NORMAL -> MaterialTheme.typography.bodyMedium
                                }
                                Text(line.text, style = style)
                                line.value?.let { Text(it, style = style) }
                            }
                        }
                    }
                    item {
                        Surface(color = Color.White, contentColor = Color.Black) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ReceiptQrPreview(receipt.receiptNumber)
                                Text(receipt.receiptNumber, style = MaterialTheme.typography.titleMedium)
                                Text("Receipt number QR", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item { Text("The PDF and print dialog wrap this saved record to the selected paper size.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
fun ReceiptSettingsScreen(shop: Shop, repository: ReceiptSettingsRepository, onBack: () -> Unit) {
    val vm: ReceiptSettingsViewModel = viewModel(key = "receipt-settings-${shop.id}", factory = PosViewModelFactory { ReceiptSettingsViewModel(repository, shop.id) })
    val state by vm.state.collectAsStateWithLifecycle()
    var paperName by rememberSaveable { mutableStateOf(ReceiptPaper.A4.name) }
    var footer by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var printerError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(state.settings) {
        if (!initialized && state.settings != null) { paperName = state.settings!!.paper.name; footer = state.settings!!.footer; initialized = true }
    }
    PosScaffold("Receipts & printing", shop.name, onBack) { padding ->
        if (state.loading) Column(Modifier.padding(padding)) { LoadingContent("Loading receipt settings…") }
        else LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { StatusCard("Offline receipts", "Open any saved sale to preview, share a PDF, or print. Purchase costs and private barcode codes never appear on a receipt.") }
            item { Text("Default paper size", style = MaterialTheme.typography.titleMedium); PaperChoices(ReceiptPaper.valueOf(paperName), { paperName = it.name }, !state.saving) }
            item { OutlinedTextField(footer, { footer = it }, label = { Text("Receipt footer") }, supportingText = { Text("Optional; up to 240 characters. No return policy is added automatically.") }, minLines = 2, maxLines = 5, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) }
            if (state.error != null) item { ErrorNotice(state.error!!, if (state.settings == null) vm::load else null) }
            if (state.notice != null) item { StatusCard("Saved", state.notice!!) }
            item { Button(onClick = { vm.save(ReceiptPaper.valueOf(paperName), footer) }, enabled = !state.saving && state.settings != null, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (state.saving) "Saving…" else "Save receipt settings") } }
            item {
                OutlinedButton(onClick = {
                    try { context.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)); printerError = null }
                    catch (_: Exception) { printerError = "Open Printing from Android Settings. You can still share receipt PDFs." }
                }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Open Android print settings") }
                if (printerError != null) ErrorNotice(printerError!!)
            }
            item { PrinterSettings(shop, repository, ReceiptPaper.valueOf(paperName), vm::load) }
        }
    }
}

@Composable
private fun PaperChoices(paper: ReceiptPaper, onChoose: (ReceiptPaper) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReceiptPaper.entries.forEach { choice ->
            FilterChip(selected = paper == choice, onClick = { onChoose(choice) }, label = { Text(choice.title) }, enabled = enabled)
        }
    }
}
