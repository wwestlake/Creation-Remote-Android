package com.lagdaemon.creationremote.gimbal

import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

class OsmoDiagnosticsModel(application:Application):AndroidViewModel(application) {
    var snapshot by mutableStateOf(AutomaticOsmoRuntime.Snapshot());private set
    val runtime=AutomaticOsmoRuntime(application){snapshot=it}
    var requestedPermission=0
    override fun onCleared(){runtime.close()}
}

@Composable
fun BleDiagnosticsScreen(model:OsmoDiagnosticsModel=viewModel(),startOnOpen:Boolean=false,onBack:()->Unit) {
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current
    val s=model.snapshot;val runtime=model.runtime
    var details by remember{mutableStateOf(false)}
    LaunchedEffect(startOnOpen){if(startOnOpen)runtime.start()}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){runtime.permissionResult()}
    val destination=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)runtime.authorizeDestination(uri)}
    LaunchedEffect(s.permissionRequest){if(s.permissionRequest>model.requestedPermission){
        model.requestedPermission=s.permissionRequest
        val missing=BleDiagnostics.requiredPermissions().filter{ContextCompat.checkSelfPermission(context,it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isEmpty())runtime.permissionResult()else permission.launch(missing.toTypedArray())
    }}
    DisposableEffect(owner,runtime){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)runtime.background()}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);runtime.cancel()}
    }
    BackHandler{runtime.cancel();onBack()}
    Surface(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Osmo Diagnostics",style=MaterialTheme.typography.headlineMedium)
            Text("Keep your Osmo powered on, stationary and disconnected from the existing phone Bluetooth session, as in the successful scan. Run authorizes the complete read-only test. No writes or movement commands are sent.")
            Button(enabled=s.canRun,onClick={runtime.start()}){Text("RUN OSMO DIAGNOSTIC")}
            Text(s.status,style=MaterialTheme.typography.bodyLarge)
            if(s.active){LinearProgressIndicator(Modifier.fillMaxWidth());OutlinedButton(enabled=s.phase!=AutomaticOsmoDiagnostic.Phase.SAVING,onClick={runtime.cancel()}){Text("Cancel Diagnostic")}}
            for((index,c) in s.choices.withIndex())OutlinedButton(onClick={runtime.choose(c.alias)}){Text("Select ${c.localName.ifBlank{"nearby OM6 ${index+1}"}} (${c.alias})")}
            if(s.phase==AutomaticOsmoDiagnostic.Phase.RESULT){
                Text("Device found: ${if(s.deviceFound)"Yes" else "No"}\nConnected: ${if(s.connected)"Yes" else "No"}\nServices examined: ${if(s.examined)"Yes" else "No"}\nReport saved on phone: ${if(s.saved)"Yes" else "No / not confirmed"}")
                if(s.report!=null){
                    Button(enabled=!s.copyBusy,onClick={if(runtime.hasDestination())runtime.saveToDestination()else destination.launch("Djehuti-Osmo-Latest-Diagnostic.json")}){Text("Save Report to Osmo Research")}
                    Text("First time: choose Google Drive → Djehuti Movie → Osmo research and save. If Android grants reusable access, future runs update this same latest-report file automatically. Each run also has its own local report. Provider writes do not verify cloud synchronization.")
                    TextButton(onClick={destination.launch("Djehuti-Osmo-Latest-Diagnostic.json")}){Text("Choose Report Destination")}
                    TextButton(onClick={details=!details}){Text(if(details)"Hide Technical Details" else "View Technical Details")}
                    if(details)Text(s.report,style=MaterialTheme.typography.bodySmall)
                }
            }
            if(s.copyStatus.isNotBlank())Text(s.copyStatus)
            OutlinedButton(onClick={runtime.cancel();onBack()}){Text("Close")}
        }
    }
}
