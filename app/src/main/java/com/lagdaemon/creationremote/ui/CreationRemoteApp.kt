package com.lagdaemon.creationremote.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lagdaemon.creationremote.ui.screens.CaptureScreen
import com.lagdaemon.creationremote.ui.screens.DeviceListScreen
import com.lagdaemon.creationremote.ui.screens.PairingScreen
import com.lagdaemon.creationremote.ui.screens.ProjectPickerScreen
import com.lagdaemon.creationremote.ui.screens.SessionMetadataScreen

/**
 * Screen flow: Pair -> device list (switch active target) -> project picker
 * -> session metadata -> capture. Pairing (sign-in, QR scan, device list)
 * is real -- see auth/AuthSession.kt and net/RemoteApiClient.kt. Project
 * picker, session metadata, and capture-to-send are still stubs (CR-M5
 * follow-on work: needs the receiver's project list and WebRTC signaling).
 */
object CreationRemoteDestinations {
    const val PAIRING = "pairing"
    const val DEVICE_LIST = "device_list"
    const val PROJECT_PICKER = "project_picker"
    const val SESSION_METADATA = "session_metadata"
    const val CAPTURE = "capture"
}

@Composable
fun CreationRemoteApp(navController: NavHostController = rememberNavController()) {
    Scaffold { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = CreationRemoteDestinations.PAIRING,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(CreationRemoteDestinations.PAIRING) {
                PairingScreen(
                    onPaired = { navController.navigate(CreationRemoteDestinations.DEVICE_LIST) }
                )
            }
            composable(CreationRemoteDestinations.DEVICE_LIST) {
                DeviceListScreen(
                    onDeviceSelected = { navController.navigate(CreationRemoteDestinations.PROJECT_PICKER) },
                    onPairNewDevice = { navController.navigate(CreationRemoteDestinations.PAIRING) }
                )
            }
            composable(CreationRemoteDestinations.PROJECT_PICKER) {
                ProjectPickerScreen(
                    onProjectSelected = { navController.navigate(CreationRemoteDestinations.SESSION_METADATA) }
                )
            }
            composable(CreationRemoteDestinations.SESSION_METADATA) {
                SessionMetadataScreen(
                    onSessionStarted = { navController.navigate(CreationRemoteDestinations.CAPTURE) }
                )
            }
            composable(CreationRemoteDestinations.CAPTURE) {
                CaptureScreen(
                    onSwitchDevice = { navController.navigate(CreationRemoteDestinations.DEVICE_LIST) }
                )
            }
        }
    }
}
