package com.lagdaemon.creationremote.webcam

import android.Manifest
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
fun WebCamApp() {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var invitation by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ready. Obtain a fresh invitation from Movie or the test endpoint.") }
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
            session=WebCamSession(context,target,parsed,rear,mic){if(generation==previewGeneration)status=it}.also{it.start()}
            invitation=""
        }catch(_:Exception){status="Invalid invitation. Paste or scan the complete fresh LAN invitation."}
    }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ result ->
        if(result.values.all{it}){ if(requestScan){requestScan=false;scanner=true}else connect() }
        else {scanner=false;status="Camera permission is required. Microphone permission is required when audio is enabled."}
    }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)stop()}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);session?.close()}
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Djehuti WebCam",style=MaterialTheme.typography.headlineMedium)
            Text("Live camera and microphone for Djehuti Movie — local network only")
            Text(status)
            if(scanner) {
                InvitationScanner({invitation=it;scanner=false},{scanner=false})
            } else {
                OutlinedTextField(invitation,{invitation=it},enabled=session==null,label={Text("Private one-use pairing invitation")},
                    modifier=Modifier.fillMaxWidth(),maxLines=4)
                Button(onClick={
                    if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)scanner=true
                    else {requestScan=true;permissions.launch(arrayOf(Manifest.permission.CAMERA))}
                },enabled=session==null){Text("Scan pairing QR code")}
                Button(onClick={if(session==null)rear=!rear else session?.switchCamera()}){Text(if(session==null){if(rear)"Rear camera selected" else "Front camera selected"}else "Switch front/rear camera")}
                Row {
                    Checkbox(mic,{mic=it;session?.setMicrophone(it)})
                    Text("Transmit microphone audio",Modifier.padding(top=12.dp))
                }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Button(enabled=session==null,onClick={
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
