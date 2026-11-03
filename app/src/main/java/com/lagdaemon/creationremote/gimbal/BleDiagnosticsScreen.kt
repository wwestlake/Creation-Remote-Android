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
    var snapshot by mutableStateOf(BleDiagnostics.Snapshot());private set
    val diagnostic=BleDiagnostics(application){snapshot=it}
    var requestedPermission=0
    override fun onCleared(){diagnostic.close()}
}

@Composable
fun BleDiagnosticsScreen(model:OsmoDiagnosticsModel=viewModel(),onBack:()->Unit) {
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current;val scope=rememberCoroutineScope()
    val snapshot=model.snapshot;val diagnostic=model.diagnostic
    var shareStatus by remember{mutableStateOf("")}
    var sharing by remember{mutableStateOf(false)}
    var details by remember{mutableStateOf(false)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){diagnostic.permissionsResolved()}
    LaunchedEffect(snapshot.permissionRequest){
        if(snapshot.phase==OsmoDiagnosticRun.Phase.PERMISSION&&snapshot.permissionRequest>model.requestedPermission){
            model.requestedPermission=snapshot.permissionRequest
            val missing=BleDiagnostics.requiredPermissions().filter{ContextCompat.checkSelfPermission(context,it)!=PackageManager.PERMISSION_GRANTED}
            if(missing.isEmpty())diagnostic.permissionsResolved()else permission.launch(missing.toTypedArray())
        }
    }
    DisposableEffect(owner,diagnostic){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)diagnostic.background()}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);diagnostic.cancel()}
    }
    BackHandler{diagnostic.cancel();onBack()}
    Surface(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Osmo Diagnostics",style=MaterialTheme.typography.headlineMedium)
            Text("Power on your gimbal, keep it nearby, and close DJI Mimo. This test observes communication and never requests movement.")
            Text(snapshot.status,style=MaterialTheme.typography.bodyLarge)
            if(snapshot.active)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(!snapshot.active)Button(enabled=!sharing,onClick={shareStatus="";diagnostic.start()}){Text("Start Diagnostics")}
            for(device in snapshot.devices){
                OutlinedButton(onClick={diagnostic.choose(device.id)},modifier=Modifier.fillMaxWidth()){
                    Text("${device.name} · nearby device ${device.id}")
                }
            }
            if(snapshot.active)OutlinedButton(onClick={diagnostic.cancel()}){Text("Cancel Diagnostics")}
            if(snapshot.report!=null){
                Button(enabled=!sharing,onClick={
                    val report=snapshot.report ?: return@Button
                    sharing=true;shareStatus="Preparing report for sharing…"
                    scope.launch{
                        try{
                            val uri=withContext(Dispatchers.IO){
                                val directory=File(context.cacheDir,"osmo-diagnostics").apply{mkdirs()}
                                val digest=java.security.MessageDigest.getInstance("SHA-256").digest(report.toByteArray()).take(8).joinToString(""){"%02x".format(it)}
                                val file=File(directory,"Djehuti-Osmo-diagnostic-$digest.json");file.writeText(report)
                                FileProvider.getUriForFile(context,"${context.packageName}.diagnostic-reports",file)
                            }
                            val intent=Intent(Intent.ACTION_SEND).apply{
                                type="application/json";putExtra(Intent.EXTRA_STREAM,uri)
                                clipData=ClipData.newRawUri("Osmo Diagnostic Report",uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent,"Share Diagnostic Report"))
                            shareStatus="Report ready. Choose where to send it in Android's sharing menu."
                        }catch(_:Exception){shareStatus="Could not open sharing. Make sure a sharing app is installed, then try again."}
                        finally{sharing=false}
                    }
                }){Text("Share Diagnostic Report")}
                Text("The report omits device addresses, names, serial numbers, pairing secrets, and raw message contents.")
                TextButton(onClick={details=!details}){Text(if(details)"Hide technical details" else "Show technical details (optional)")}
                if(details)Text(snapshot.report,style=MaterialTheme.typography.bodySmall)
            }
            if(shareStatus.isNotEmpty())Text(shareStatus)
            OutlinedButton(onClick={diagnostic.cancel();onBack()}){Text("Close")}
        }
    }
}
