package com.offerpoint.pos.ui.security

import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offerpoint.pos.domain.repository.SecurityRepository
import com.offerpoint.pos.ui.components.*
import com.offerpoint.pos.ui.screens.*
import kotlinx.coroutines.launch

@Composable
fun SecureWorkspace(model:SecurityViewModel,content: @Composable () -> Unit){
    val state by model.state.collectAsStateWithLifecycle();val holder=rememberSaveableStateHolder()
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)model.foreground()};owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
    when{state.loading->LoadingContent("Checking local security…");state.locked->PinUnlockScreen(state,model::unlock,model::deviceReset);else->holder.SaveableStateProvider("unlocked-workspace"){content()}}
}

@Composable private fun PinUnlockScreen(state:LockState,onUnlock:(CharArray)->Unit,onDeviceReset:()->Unit){
    var pin by remember{mutableStateOf("")};var resetError by remember{mutableStateOf<String?>(null)}
    val context=LocalContext.current
    val deviceUnlock=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->if(result.resultCode==android.app.Activity.RESULT_OK)onDeviceReset()}
    BackHandler{}
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.Center){
        Text("Offer Point POS",style=MaterialTheme.typography.headlineLarge);Spacer(Modifier.height(16.dp));Text("Enter your local app PIN to continue.")
        OutlinedTextField(pin,{pin=it},label={Text("App PIN")},singleLine=true,enabled=!state.busy,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth().padding(top=18.dp))
        if(state.error!=null)ErrorNotice(state.error)
        Button(onClick={val secret=pin.toCharArray();pin="";onUnlock(secret)},enabled=!state.busy&&pin.isNotEmpty(),modifier=Modifier.fillMaxWidth().padding(top=12.dp).height(54.dp)){Text(if(state.busy)"Checking…" else "Unlock")}
        TextButton(onClick={
            val manager=context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            @Suppress("DEPRECATION") val intent=manager?.createConfirmDeviceCredentialIntent("Reset Offer Point app PIN","Confirm the phone’s device credential to remove the forgotten app PIN.")
            if(intent!=null)deviceUnlock.launch(intent)else resetError="Set a secure phone screen lock to use device-credential recovery. Your encrypted backup can also be restored on a clean device."
        },enabled=!state.busy){Text("Forgot app PIN? Use phone unlock")}
        if(resetError!=null)Text(resetError!!,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable fun SecuritySettingsScreen(repository:SecurityRepository,onLock:()->Unit,onBack:()->Unit){
    val status by repository.observe().collectAsStateWithLifecycle(initialValue=com.offerpoint.pos.domain.repository.SecurityStatus())
    var current by remember{mutableStateOf("")};var pin by remember{mutableStateOf("")};var repeated by remember{mutableStateOf("")};var minutes by remember{mutableStateOf("2")}
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)};val scope=rememberCoroutineScope()
    LaunchedEffect(status.autoLockMinutes){minutes=status.autoLockMinutes.toString()}
    fun save(disable:Boolean){
        if(busy)return
        if(!disable&&pin!=repeated){message="New PINs must match.";return}
        val old=current.toCharArray();val next=if(disable)null else pin.toCharArray();val duration=minutes.toIntOrNull() ?: 0
        busy=true;current="";pin="";repeated=""
        scope.launch{try{repository.configure(old,next,duration);message=if(disable)"App PIN disabled." else "Local PIN saved. Keep your phone screen lock enabled."}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;message=e.message}finally{old.fill('\u0000');next?.fill('\u0000');busy=false}}
    }
    PosScaffold("Local app security","This device",{if(!busy)onBack()}){padding->LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{StatusCard(if(status.enabled)"PIN enabled" else "PIN optional","A local PIN locks this app after inactivity. It does not create an account. The PIN stays on this device and is not included in business backups.")}
        if(status.enabled)item{OutlinedTextField(current,{current=it},label={Text("Current PIN")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),enabled=!busy,modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(pin,{pin=it},label={Text("New PIN (6–12 digits)")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),enabled=!busy,modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(repeated,{repeated=it},label={Text("Repeat new PIN")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),enabled=!busy,modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(minutes,{minutes=it},label={Text("Auto-lock minutes (1–30)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!busy,modifier=Modifier.fillMaxWidth())}
        if(message!=null)item{StatusCard("Security status",message!!)}
        item{Button(onClick={save(false)},enabled=!busy,modifier=Modifier.fillMaxWidth().height(54.dp)){Text("Save local PIN")}}
        if(status.enabled){item{OutlinedButton(onClick={save(true)},enabled=!busy,modifier=Modifier.fillMaxWidth().height(52.dp)){Text("Disable PIN")}};item{OutlinedButton(onClick=onLock,enabled=!busy,modifier=Modifier.fillMaxWidth().height(52.dp)){Text("Lock app now")}}}
    }}
}
