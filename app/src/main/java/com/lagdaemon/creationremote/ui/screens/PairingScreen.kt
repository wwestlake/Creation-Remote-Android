package com.lagdaemon.creationremote.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.lagdaemon.creationremote.auth.AuthSession
import com.lagdaemon.creationremote.net.RemoteApiClient
import com.lagdaemon.creationremote.net.parsePairingCode
import kotlinx.coroutines.launch

private enum class PairingStage { SIGN_IN, SCANNING, APPROVING, ERROR }

/**
 * Scan a Suite Remote Receiver's pairing QR code. Real flow: sign in via
 * the shared desktop OAuth/PKCE flow if needed, scan with CameraX + ML Kit,
 * then POST the code to /api/remote/pairings/{code}/approve.
 */
@Composable
fun PairingScreen(onPaired: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val authSession = remember { AuthSession(context.applicationContext) }

    var stage by remember { mutableStateOf(PairingStage.SIGN_IN) }
    var statusText by remember { mutableStateOf("") }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        val existing = authSession.tokenStore.currentSession()
        if (existing != null) {
            stage = PairingStage.SCANNING
            if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (stage) {
            PairingStage.SIGN_IN -> {
                Text("Sign in to pair a Creation Remote Receiver")
                Button(
                    modifier = Modifier.padding(top = 16.dp),
                    onClick = {
                        scope.launch {
                            statusText = "Signing in..."
                            runCatching { authSession.signIn() }
                                .onSuccess {
                                    stage = PairingStage.SCANNING
                                    if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                                .onFailure {
                                    statusText = it.message ?: "Sign-in failed"
                                    stage = PairingStage.ERROR
                                }
                        }
                    }
                ) { Text("Sign In") }
            }

            PairingStage.SCANNING -> {
                if (!hasCameraPermission) {
                    Text("Camera permission is required to scan a pairing code.")
                    Button(modifier = Modifier.padding(top = 16.dp), onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                } else {
                    Text("Point the camera at the receiver's pairing QR code")
                    QrScannerPreview(
                        modifier = Modifier.fillMaxSize().padding(top = 16.dp),
                        onCodeScanned = { payload ->
                            val code = parsePairingCode(payload) ?: return@QrScannerPreview
                            stage = PairingStage.APPROVING
                            scope.launch {
                                val session = authSession.tokenStore.currentSession()
                                if (session == null) {
                                    statusText = "Not signed in"
                                    stage = PairingStage.ERROR
                                    return@launch
                                }
                                val client = RemoteApiClient(session.token)
                                client.approvePairing(code, Build.MODEL, "android")
                                    .onSuccess { onPaired() }
                                    .onFailure {
                                        statusText = it.message ?: "Pairing failed"
                                        stage = PairingStage.ERROR
                                    }
                            }
                        }
                    )
                }
            }

            PairingStage.APPROVING -> {
                CircularProgressIndicator()
                Text("Pairing...", modifier = Modifier.padding(top = 16.dp))
            }

            PairingStage.ERROR -> {
                Text(statusText)
                Button(modifier = Modifier.padding(top = 16.dp), onClick = { stage = PairingStage.SCANNING }) {
                    Text("Try Again")
                }
            }
        }
    }
}

@Composable
private fun QrScannerPreview(modifier: Modifier = Modifier, onCodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasScanned by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val controller = LifecycleCameraController(ctx)
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            )
            controller.setImageAnalysisAnalyzer(
                ContextCompat.getMainExecutor(ctx),
                MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, ContextCompat.getMainExecutor(ctx)) { result ->
                    if (hasScanned) return@MlKitAnalyzer
                    val barcodes = result?.getValue(scanner) ?: return@MlKitAnalyzer
                    val payload = barcodes.firstOrNull()?.rawValue ?: return@MlKitAnalyzer
                    hasScanned = true
                    onCodeScanned(payload)
                }
            )
            controller.bindToLifecycle(lifecycleOwner)
            previewView.controller = controller
            previewView
        }
    )
}
