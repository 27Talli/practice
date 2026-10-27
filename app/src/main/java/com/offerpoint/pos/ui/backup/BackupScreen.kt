package com.offerpoint.pos.ui.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offerpoint.pos.domain.repository.BackupRepository
import com.offerpoint.pos.ui.components.ErrorNotice
import com.offerpoint.pos.ui.components.StatusCard
import com.offerpoint.pos.ui.screens.PosScaffold
import com.offerpoint.pos.ui.screens.PosViewModelFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun BackupScreen(repository: BackupRepository, onBack: () -> Unit) {
    val vm: BackupViewModel = viewModel(factory=PosViewModelFactory { BackupViewModel(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    // Passphrases and confirmations intentionally do not use rememberSaveable/SavedStateHandle.
    var operation by remember { mutableStateOf("") }
    var location by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var dialogError by remember { mutableStateOf<String?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if(uri!=null) { operation="export"; location=uri.toString(); dialogError=null }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) { operation="inspect"; location=uri.toString(); dialogError=null }
    }
    val recovery = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        // The explicit confirmation occurs BEFORE the picker; never restore after an unrelated recreated callback.
        if(uri!=null && confirmation=="RESTORE") { vm.restore(uri.toString(),confirmation); confirmation="" }
    }
    BackHandler(state.busy) { }
    PosScaffold("Backups & restore", "All shops on this device", { if(!state.busy) { vm.discard(); onBack() } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Text("Protect your shop records",style=MaterialTheme.typography.headlineMedium) }
            item { StatusCard("Encrypted local backup", "Includes both shops, products, stock history, sales, private cost-code keys and receipt settings. Choose Downloads, a USB drive or another trusted location. There is no account or automatic cloud upload.") }
            item { Text("Keep a copy off this phone. A forgotten passphrase cannot be recovered. Files saved only in this app’s private storage will be lost if the app is uninstalled.",style=MaterialTheme.typography.bodyMedium) }
            item { Button(onClick={ create.launch("OfferPoint-Backup-${System.currentTimeMillis()}.opbackup") },enabled=!state.busy,modifier=Modifier.fillMaxWidth().height(54.dp)) { Text("Create encrypted backup") } }
            item { OutlinedButton(onClick={ open.launch(arrayOf("*/*")) },enabled=!state.busy,modifier=Modifier.fillMaxWidth().height(54.dp)) { Text("Inspect backup for restore") } }
            if(state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Working… Keep the app open. Current shop records are protected by a database transaction.") }
            if(state.error!=null) item { ErrorNotice(state.error!!) }
            if(state.notice!=null) item { StatusCard("Backup status",state.notice!!) }
            state.preview?.let { preview ->
                item {
                    val date = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'PKT'",Locale.ENGLISH).withZone(ZoneId.of("Asia/Karachi")).format(Instant.ofEpochMilli(preview.createdAt))
                    StatusCard("Validated backup", "${if(preview.demo) "DEMO" else "LIVE"} · $date\n${preview.shops.joinToString("\n")}\n${preview.counts["products"] ?: 0} products · ${preview.counts["sales"] ?: 0} sales\n${preview.counts["stock_movements"] ?: 0} stock records · ${preview.counts["product_labels"] ?: 0} cost labels")
                }
                item { StatusCard("Replace all shop data", "Restore replaces BOTH shops on this device with this backup. It does not merge records. First, a new encrypted recovery backup of current data must be saved and verified. It uses the same passphrase as the backup you inspected. Unpaid carts will be cleared.") }
                item { OutlinedTextField(confirmation,{confirmation=it},label={Text("Type RESTORE to confirm")},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth()) }
                item { Button(onClick={ recovery.launch("OfferPoint-Recovery-${System.currentTimeMillis()}.opbackup") },enabled=!state.busy && confirmation=="RESTORE",modifier=Modifier.fillMaxWidth().height(54.dp)) { Text("Save recovery backup & restore") } }
                item { TextButton(onClick={ vm.discard(); confirmation="" },enabled=!state.busy) { Text("Cancel restore") } }
            }
        }
    }
    if(location!=null) AlertDialog(onDismissRequest={location=null;password="";repeat=""}, title={Text(if(operation=="export") "Set backup passphrase" else "Unlock backup")},
        text={ Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Use at least 10 characters. Keep it separate from the backup file; no recovery service exists.")
            OutlinedTextField(password,{password=it},label={Text("Passphrase")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),visualTransformation=PasswordVisualTransformation())
            if(operation=="export") OutlinedTextField(repeat,{repeat=it},label={Text("Repeat passphrase")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),visualTransformation=PasswordVisualTransformation())
            if(dialogError!=null) Text(dialogError!!,color=MaterialTheme.colorScheme.error)
        } }, confirmButton={TextButton(onClick={
            if(password.length<10 || (operation=="export" && password!=repeat)) dialogError="Use at least 10 characters and matching passphrases."
            else { val uri=location!!;val secret=password.toCharArray();password="";repeat="";location=null
                if(operation=="export") vm.export(uri,secret) else vm.inspect(uri,secret) }
        }) {Text(if(operation=="export") "Save backup" else "Validate backup")}}, dismissButton={TextButton(onClick={location=null;password="";repeat=""}){Text("Cancel")}})
}
