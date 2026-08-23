package com.lagdaemon.creationremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lagdaemon.creationremote.ui.CreationRemoteApp
import com.lagdaemon.creationremote.ui.theme.CreationRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CreationRemoteTheme {
                CreationRemoteApp()
            }
        }
    }
}
