package com.lagdaemon.creationremote.webcam

import android.Manifest
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import org.webrtc.SurfaceViewRenderer

@Composable
fun WebCamApp(onDiagnostics:()->Unit = {}) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var showNetworkDetails by remember { mutableStateOf(false) }
    var manualEntry by remember { mutableStateOf(false) }
    var autoConnect by remember { mutableStateOf(false) }
    var networkAddresses by remember { mutableStateOf("Checking local network addresses…") }
    LaunchedEffect(context,showNetworkDetails) {
        while (showNetworkDetails) {
            networkAddresses = withContext(Dispatchers.IO) { phoneNetworkAddresses(context) }
            delay(3000)
        }
    }
    var invitation by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Waiting: Tap Pair Phone in Movie, then scan its QR code.") }
    var rear by remember { mutableStateOf(true) }
    var mic by remember { mutableStateOf(true) }
    var scanner by remember { mutableStateOf(false) }
    var requestScan by remember { mutableStateOf(false) }
    var previewGeneration by remember { mutableStateOf(0) }
    var session by remember { mutableStateOf<WebCamSession?>(null) }
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    fun stop() {session?.close();session=null;renderer=null;previewGeneration++;status="Stopped — releasing camera, microphone and connection. Use a fresh invitation to reconnect."}
    fun connect() {
        if(session!=null)return
        try {
            val parsed=PairingInvitation(invitation)
            val target=renderer ?: error("Preview is not ready")
            val generation=previewGeneration
            session=WebCamSession(context,target,parsed,rear,mic,
                {if(generation==previewGeneration)status=it},
                {if(generation==previewGeneration){session=null;renderer=null;previewGeneration++}}).also{it.start()}
            invitation=""
        }catch(_:Exception){status="Failed: Invalid invitation. Scan a fresh Movie QR code or use manual entry."}
    }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ result ->
        if(result.values.all{it}){ if(requestScan){requestScan=false;renderer=null;scanner=true}else connect() }
        else {requestScan=false;scanner=false;status="Camera permission is required. Microphone permission is required when audio is enabled."}
    }
    LaunchedEffect(autoConnect,scanner,renderer) {
        if(autoConnect && !scanner && renderer!=null && session==null) {
            autoConnect=false;requestScan=false
            val required=mutableListOf(Manifest.permission.CAMERA)
            if(mic)required.add(Manifest.permission.RECORD_AUDIO)
            if(required.all{ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED})connect()
            else permissions.launch(required.toTypedArray())
        }
    }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)stop()}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);session?.close()}
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Djehuti WebCam",style=MaterialTheme.typography.headlineMedium)
            OutlinedButton(enabled=session==null&&!scanner,onClick=onDiagnostics){Text("Gimbal BLE diagnostics")}
            Text("Live camera and microphone for Djehuti Movie — local network only")
            TextButton(onClick={showNetworkDetails=!showNetworkDetails}){Text(if(showNetworkDetails)"Hide connection details" else "Connection details")}
            if(showNetworkDetails) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Phone network addresses", style=MaterialTheme.typography.titleMedium)
                    Text(networkAddresses)
                    Text("Movie chooses local endpoints automatically. The devices need a reachable local path; address similarity alone does not guarantee access.", style=MaterialTheme.typography.bodySmall)
                }
            }
            Text(status)
            if(scanner) {
                InvitationScanner({invitation=it;scanner=false;autoConnect=true},{scanner=false})
            } else {
                if(manualEntry) OutlinedTextField(invitation,{invitation=it},enabled=session==null,label={Text("Private one-use pairing invitation")},
                    modifier=Modifier.fillMaxWidth(),maxLines=4)
                Button(onClick={
                    if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){renderer=null;scanner=true}
                    else {requestScan=true;permissions.launch(arrayOf(Manifest.permission.CAMERA))}
                },enabled=session==null){Text("Scan pairing QR code")}
                TextButton(onClick={manualEntry=!manualEntry},enabled=session==null){Text(if(manualEntry)"Hide manual entry" else "Enter invitation manually (optional)")}
                Button(onClick={if(session==null)rear=!rear else session?.switchCamera()}){Text(if(session==null){if(rear)"Rear camera selected" else "Front camera selected"}else "Switch front/rear camera")}
                Row {
                    Checkbox(mic,{mic=it;session?.setMicrophone(it)})
                    Text("Transmit microphone audio",Modifier.padding(top=12.dp))
                }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(manualEntry) Button(enabled=session==null,onClick={
                        requestScan=false
                        val required=mutableListOf(Manifest.permission.CAMERA)
                        if(mic)required.add(Manifest.permission.RECORD_AUDIO)
                        if(required.all{ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED})connect()
                        else permissions.launch(required.toTypedArray())
                    }){Text("Connect and stream")}
                    OutlinedButton(enabled=session!=null,onClick={stop()}){Text("Stop / disconnect")}
                }
                Text("Local preview • requested 1280 × 720 / 30 fps; actual format depends on camera")
                key(previewGeneration){AndroidView(factory={SurfaceViewRenderer(it).also{view->renderer=view}},modifier=Modifier.fillMaxWidth().height(260.dp))}
                Text("Keep this screen open. Backgrounding stops capture. Reconnect with a fresh invitation; website sign-in is not used.")
            }
        }
    }
}

@Composable
private fun InvitationScanner(onResult:(String)->Unit,onCancel:()->Unit) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val controller=remember{LifecycleCameraController(context)}
    val scanner=remember{BarcodeScanning.getClient()}
    var scanned by remember{mutableStateOf(false)}
    DisposableEffect(owner) {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) {
            controller.setImageAnalysisAnalyzer(ContextCompat.getMainExecutor(context),
                MlKitAnalyzer(listOf(scanner),ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,ContextCompat.getMainExecutor(context)){result->
                    val text=result?.getValue(scanner)?.firstOrNull()?.rawValue
                    if(!scanned && text!=null){scanned=true;controller.unbind();onResult(text)}
                })
            controller.bindToLifecycle(owner)
        }
        onDispose{controller.clearImageAnalysisAnalyzer();controller.unbind();scanner.close()}
    }
    AndroidView(factory={PreviewView(it).also{view->view.controller=controller}},modifier=Modifier.fillMaxWidth().height(300.dp))
    OutlinedButton(onClick=onCancel){Text("Cancel QR scanning")}
}

private fun phoneNetworkAddresses(context: android.content.Context): String = try {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val primary = manager.activeNetwork
    val rows = manager.allNetworks.mapNotNull { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
        val kind = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Wired / Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> return@mapNotNull null
        }
        val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
        val addresses = properties.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.mapNotNull { it.hostAddress }
        if (addresses.isEmpty()) null
        else "$kind (${properties.interfaceName ?: "network"})${if (network == primary) " • default" else ""}: ${addresses.joinToString(", ")}"
    }.distinct()
    if (rows.isEmpty()) "No Wi-Fi or wired IPv4 address found. Connect to your PC's local network."
    else rows.joinToString("\n")
} catch (_: Exception) {
    "Unable to read local network addresses. Check the phone's network settings."
}