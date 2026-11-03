package com.lagdaemon.creationremote.gimbal

import android.app.Application
import android.content.ClipData
import android.content.Intent
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
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OsmoDiagnosticsModel(application:Application):AndroidViewModel(application) {
    var snapshot by mutableStateOf(BluetoothDiscovery.Snapshot());private set
    val diagnostic=BluetoothDiscovery(application){snapshot=it}
    var inspection by mutableStateOf(AndroidOsmoInspection.Snapshot());private set
    val inspector=AndroidOsmoInspection(application){inspection=it}
    var requestedPermission=0
    override fun onCleared(){diagnostic.close();inspector.close()}
}

@Composable
fun BleDiagnosticsScreen(model:OsmoDiagnosticsModel=viewModel(),onBack:()->Unit) {
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current;val scope=rememberCoroutineScope()
    val snapshot=model.snapshot;val diagnostic=model.diagnostic;val inspection=model.inspection;val inspector=model.inspector
    val exportReport=inspection.report ?: snapshot.report
    var shareStatus by remember{mutableStateOf("")}
    var sharing by remember{mutableStateOf(false)}
    var details by remember{mutableStateOf(false)}
    var condition by remember{mutableStateOf("osmo_powered_on")}
    var selected by remember{mutableStateOf("")}
    var selectionExplicit by remember{mutableStateOf(false)}
    var allowReads by remember{mutableStateOf(false)}
    var showServices by remember{mutableStateOf(false)}
    LaunchedEffect(snapshot.permissionRequest,snapshot.candidates.map{it.alias}){selected=CandidateSelection.update(selected,snapshot.candidates.map{it.alias},selectionExplicit)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){diagnostic.permissionsResolved()}
    LaunchedEffect(snapshot.permissionRequest){
        if(snapshot.phase==DiscoveryRun.Phase.PERMISSION&&snapshot.permissionRequest>model.requestedPermission){
            model.requestedPermission=snapshot.permissionRequest
            val missing=BleDiagnostics.requiredPermissions().filter{ContextCompat.checkSelfPermission(context,it)!=PackageManager.PERMISSION_GRANTED}
            if(missing.isEmpty())diagnostic.permissionsResolved()else permission.launch(missing.toTypedArray())
        }
    }
    DisposableEffect(owner,diagnostic){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP){diagnostic.background();inspector.background()}}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);diagnostic.cancel();inspector.background()}
    }
    BackHandler{diagnostic.cancel();inspector.disconnect();onBack()}
    Surface(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Osmo Diagnostics — Connection and Services",style=MaterialTheme.typography.headlineMedium)
            Text("Mount the phone and keep the gimbal stationary. Keep its existing phone Bluetooth session disconnected, as in the successful scan. Scanning alone never connects. No movement requests or writes are made. Stop if unexpected movement occurs.")
            if(!snapshot.active&&!inspection.active){
                Text("Which test are you running?")
                for((value,label) in DiscoveryEvidence.conditions.zip(listOf("Nearby Bluetooth check","Osmo powered off","Osmo powered on","Osmo normal pairing condition"))){
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){ RadioButton(selected=condition==value,onClick={condition=value});TextButton(onClick={condition=value}){Text(label)} }
                }
            }
            Text(snapshot.status,style=MaterialTheme.typography.bodyLarge)
            if(snapshot.active)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(!snapshot.active&&!inspection.active)Button(enabled=!sharing,onClick={shareStatus="";selected="";selectionExplicit=false;inspector.reset();diagnostic.start(condition)}){Text("Scan for Osmo")}
            Text("Elapsed scan time: ${snapshot.elapsedSeconds} seconds")
            Text("Distinct device aliases: ${snapshot.distinct} • Advertisement observations: ${snapshot.advertisements} • Possible DJI devices: ${snapshot.possible}")
            if(snapshot.rows.isNotEmpty()){
                TextButton(onClick={details=!details}){Text(if(details)"Hide device observations" else "Show device observations") }
                if(details)for(row in snapshot.rows)Text(row,style=MaterialTheme.typography.bodySmall)
            }
            if(snapshot.active)OutlinedButton(onClick={diagnostic.cancel()}){Text("Stop Scan")}
            if(snapshot.candidates.isNotEmpty()){
                Text("Device discovered. Advertising hints are not authenticated identity.")
                if(snapshot.candidates.size>1)Text("More than one candidate was found. Select your gimbal before connecting.")
                for(c in snapshot.candidates){
                    if(snapshot.candidates.size>1)OutlinedButton(enabled=!inspection.active,onClick={selected=c.alias;selectionExplicit=true}){Text("${if(selected==c.alias)"Selected: " else "Select: "}${c.alias} · ${if(c.om6Hint)"OM6 name hint" else "Osmo service hint"}")}
                    else Text("${c.alias}: ${if(c.om6Hint)"OM6 name hint" else "Osmo service hint"}; ${c.observations} observations")
                }
                Button(enabled=snapshot.canConnect&&!inspection.active&&!sharing&&selected.isNotEmpty()&&(snapshot.candidates.size==1||selectionExplicit),onClick={
                    if(snapshot.candidates.size>1&&!selectionExplicit)return@Button
                    val candidate=snapshot.candidates.firstOrNull{it.alias==selected} ?: return@Button
                    val device=diagnostic.takeCandidateForConnection(selected) ?: run{shareStatus="Scanning could not be stopped safely for this candidate. Check Bluetooth permissions and scan again.";return@Button}
                    allowReads=false;inspector.connect(true,candidate,device)
                }){Text("Connect to Osmo")}
            }
            Text(inspection.status)
            if(inspection.active)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(inspection.phase==OsmoServiceSession.Phase.CONNECTED){
                Row{Checkbox(checked=allowReads,onCheckedChange={allowReads=it});Text("Optional: read standard battery, appearance and HID information only")}
                Text("Discover Services collects metadata, performs only the opted-in standard reads when supported, then disconnects automatically.")
                Button(onClick={inspector.discover(true,allowReads)}){Text("Discover Services")}
            }
            OutlinedButton(enabled=inspection.active,onClick={inspector.disconnect()}){Text("Disconnect")}
            if(inspection.inventory.isNotEmpty()){
                TextButton(onClick={showServices=!showServices}){Text(if(showServices)"Hide service inspection results" else "Show service inspection results")}
                if(showServices)for(line in inspection.inventory)Text(line,style=MaterialTheme.typography.bodySmall)
            }
            if(exportReport!=null&&!inspection.active){
                Button(enabled=!sharing,onClick={
                    val report=exportReport ?: return@Button
                    sharing=true;shareStatus="Preparing report for sharing…"
                    scope.launch{
                        try{
                            val uri=withContext(Dispatchers.IO){
                                val directory=File(context.cacheDir,"osmo-diagnostics").apply{mkdirs()}
                                val digest=java.security.MessageDigest.getInstance("SHA-256").digest(report.toByteArray()).take(8).joinToString(""){"%02x".format(it)}
                                val file=File(directory,"Djehuti-Osmo-inspection-$digest.json");file.writeText(report)
                                FileProvider.getUriForFile(context,"${context.packageName}.diagnostic-reports",file)
                            }
                            val intent=Intent(Intent.ACTION_SEND).apply{
                                type="application/json";putExtra(Intent.EXTRA_STREAM,uri)
                                clipData=ClipData.newRawUri("Osmo Diagnostic Report",uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent,"Export Diagnostic Report"))
                            shareStatus="Report ready. Choose where to send it in Android's sharing menu."
                        }catch(_:Exception){shareStatus="Could not open sharing. Make sure a sharing app is installed, then try again."}
                        finally{sharing=false}
                    }
                }){Text("Export Diagnostic Report")}
                Text("The report omits device addresses, names, serial numbers, pairing secrets, and raw message contents.")

            }
            if(shareStatus.isNotEmpty())Text(shareStatus)
            OutlinedButton(onClick={diagnostic.cancel();inspector.disconnect();onBack()}){Text("Close")}
        }
    }
}
