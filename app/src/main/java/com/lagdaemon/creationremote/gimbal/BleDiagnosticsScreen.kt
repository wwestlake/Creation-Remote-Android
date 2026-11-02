package com.lagdaemon.creationremote.gimbal

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BleDiagnosticsScreen(onBack:()->Unit) {
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current;val scope=rememberCoroutineScope()
    var snapshot by remember{mutableStateOf(BleDiagnostics.Snapshot())}
    var exportStatus by remember{mutableStateOf("")}
    val diagnostic=remember(context){BleDiagnostics(context){snapshot=it}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ grants ->
        if(grants.isNotEmpty()&&grants.values.all{it})diagnostic.scan()
        else exportStatus="Nearby device permission denied. Allow it in Android settings and retry Scan."
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri ->
        if(uri!=null){val text=diagnostic.export();scope.launch{
            exportStatus=withContext(Dispatchers.IO){try{context.contentResolver.openOutputStream(uri)?.use{it.write(text.toByteArray(Charsets.UTF_8))} ?: error("No output");"Redacted diagnostic log saved."}catch(_:Exception){"Export failed. Choose another writable location and retry."}}
        }}
    }
    DisposableEffect(owner,diagnostic){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)diagnostic.disconnect()}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);diagnostic.close()}
    }
    Surface(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Gimbal BLE diagnostics",style=MaterialTheme.typography.headlineMedium)
            Text("Investigation only. No motor commands, firmware operations or characteristic writes. Stop WebCam streaming before using this screen.")
            Text(snapshot.status)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={permission.launch(BleDiagnostics.requiredPermissions())}){Text("Scan BLE")}
                OutlinedButton(onClick={diagnostic.disconnect()}){Text("Disconnect")}
            }
            OutlinedButton(onClick={export.launch("Djehuti-Osmo-diagnostic.json")}){Text("Export redacted log")}
            if(exportStatus.isNotEmpty())Text(exportStatus)
            Text("Names and advertisements below are visible only on this phone. Export omits device addresses/names, raw values, payloads and pairing material.")
            for(device in snapshot.devices){
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(10.dp)){
                        Text("Device ${device.id}: ${device.name} • ${device.rssi} dBm")
                        Text(device.advertisement,style=MaterialTheme.typography.bodySmall)
                        Button(onClick={diagnostic.connect(device.id)}){Text("Connect device ${device.id}")}
                    }
                }
            }
            for(service in snapshot.services)Text("Service: $service",style=MaterialTheme.typography.bodySmall)
            for(c in snapshot.characteristics){
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(10.dp)){
                        Text("Service ${c.service}\nCharacteristic ${c.characteristic}",style=MaterialTheme.typography.bodySmall)
                        Text(BleDiagnostics.propertyText(c.properties))
                        if(c.properties and (16 or 32)!=0)OutlinedButton(enabled=!c.subscribed,onClick={diagnostic.subscribe(c.id)}){Text(if(c.subscribed)"Subscribed" else "Subscribe notifications")}
                    }
                }
            }
            Text("For the first OM6 test, subscribe to FFF4 if it actually appears. Other-model UUIDs are hints, not OM6 compatibility claims. Standard CCCD enable writes are the only outgoing data writes.")
            OutlinedButton(onClick={diagnostic.close();onBack()}){Text("Close diagnostics")}
        }
    }
}
