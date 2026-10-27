package com.offerpoint.pos.ui.printing

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.ReceiptSettingsRepository
import com.offerpoint.pos.ui.components.StatusCard
import kotlinx.coroutines.launch

@Composable
fun PrinterSettings(shop:Shop,repository:ReceiptSettingsRepository,paper:ReceiptPaper,onSaved:()->Unit={}){
    val context=LocalContext.current;val printer=remember{ThermalPrinter(context)};val scope=rememberCoroutineScope()
    var mode by remember{mutableStateOf(PrinterMode.ANDROID)};var candidates by remember{mutableStateOf<List<PrinterCandidate>>(emptyList())}
    var profile by remember{mutableStateOf<ReceiptSettings?>(null)};var cut by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(repository,shop.id){runCatching{repository.getSettings(shop.id)}.onSuccess{profile=it;cut=it.cutPaper}.onFailure{message=it.message}}
    fun loadBluetooth(){runCatching{printer.pairedBluetooth()}.onSuccess{candidates=it;mode=PrinterMode.BLUETOOTH;message=if(it.isEmpty())"No paired devices. Pair the printer in Android Bluetooth settings first." else "Choose your receipt printer from paired devices."}.onFailure{message=it.message}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)loadBluetooth()else message="Nearby devices permission was denied. Android printing and PDFs remain available."}
    fun save(candidate:PrinterCandidate?,chosen:PrinterMode){
        if(busy)return;busy=true
        scope.launch{try{val current=repository.getSettings(shop.id);val updated=current.copy(paper=paper,printerMode=chosen,printerKey=candidate?.key.orEmpty(),printerName=candidate?.name.orEmpty(),cutPaper=cut)
            repository.saveSettings(updated);profile=updated;message="Printer preference saved for ${shop.name}.";onSaved()}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;message=e.message}finally{busy=false}}
    }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Direct thermal printer",style=MaterialTheme.typography.titleLarge)
        StatusCard("Saved printer",profile?.let{if(it.printerMode==PrinterMode.ANDROID)"Android print service / PDF" else "${it.printerMode} · ${it.printerName}"} ?: "Loading…")
        Text("Bluetooth uses paired Classic SPP printers. USB supports the standard printer class with bulk output. The printer must understand ESC/POS raster images; select 58 mm or 80 mm paper above.")
        OutlinedButton(onClick={save(null,PrinterMode.ANDROID)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Use Android printing / PDF")}
        OutlinedButton(onClick={if(Build.VERSION.SDK_INT>=31&&!printer.bluetoothPermissionGranted())permission.launch(Manifest.permission.BLUETOOTH_CONNECT)else loadBluetooth()},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Choose paired Bluetooth printer")}
        OutlinedButton(onClick={mode=PrinterMode.USB;candidates=printer.attachedUsb();message=if(candidates.isEmpty())"No standard USB printer is connected. Serial USB adapters need a model-specific driver." else "Choose the connected USB printer."},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Choose attached USB printer")}
        for(candidate in candidates)OutlinedButton(onClick={save(candidate,mode)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(candidate.name)}
        Row {Checkbox(checked=cut,onCheckedChange={cut=it},enabled=!busy);Text("Cut paper after printing (only if supported)",Modifier.padding(top=10.dp))}
        if(profile?.printerMode!=null&&profile?.printerMode!=PrinterMode.ANDROID)TextButton(onClick={profile?.let{save(PrinterCandidate(it.printerName,it.printerKey),it.printerMode)}},enabled=!busy){Text("Save cutter preference")}
        OutlinedButton(onClick={try{context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))}catch(_:Exception){message="Open Bluetooth settings from Android Settings."}},modifier=Modifier.fillMaxWidth()){Text("Open Bluetooth pairing settings")}
        Button(onClick={val settings=profile ?: return@Button;busy=true;scope.launch{try{
            val sample=ReceiptDocument(0,shop.id,"TEST",shop.name,shop.address,"Printer alignment check",false,listOf(ReceiptLine("PRINTER TEST - NOT A SALE",style=ReceiptLineStyle.TOTAL),ReceiptLine("ABCDEFGHIJKLMNOPQRSTUVWXYZ"),ReceiptLine("0123456789"),ReceiptLine("Check alignment and paper width.")),"PRINTER TEST")
            if(settings.printerMode==PrinterMode.ANDROID)com.offerpoint.pos.ui.receipts.ReceiptPrinting.print(context,sample,settings.paper)else printer.send(sample,settings)
            message="Test data sent. Check the physical printer; no sale was created."
        }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;message=e.message}finally{busy=false}}},enabled=!busy&&profile!=null,modifier=Modifier.fillMaxWidth().height(52.dp)){Text(if(busy)"Working…" else "Print a test (no sale)")}
        if(message!=null)StatusCard("Printer status",message!!)
        Text("A transfer confirmation is not proof of paper output. Check the printer before retrying a failed transfer, since part of the receipt may have printed.",style=MaterialTheme.typography.bodySmall)
    }
}
