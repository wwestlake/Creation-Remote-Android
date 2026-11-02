package com.lagdaemon.creationremote

import android.os.Bundle
import androidx.compose.runtime.*
import com.lagdaemon.creationremote.gimbal.BleDiagnosticsScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lagdaemon.creationremote.webcam.WebCamApp
import com.lagdaemon.creationremote.ui.theme.CreationRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CreationRemoteTheme {
                run {
                    var diagnostics by remember { mutableStateOf(false) }
                    if(diagnostics) BleDiagnosticsScreen{diagnostics=false}
                    else WebCamApp(onDiagnostics={diagnostics=true})
                }
            }
        }
    }
}
