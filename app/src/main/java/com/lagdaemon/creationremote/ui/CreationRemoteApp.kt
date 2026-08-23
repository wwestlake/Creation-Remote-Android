package com.lagdaemon.creationremote.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lagdaemon.creationremote.ui.screens.CaptureScreen
import com.lagdaemon.creationremote.ui.screens.DeviceListScreen
import com.lagdaemon.creationremote.ui.screens.PairingScreen
import com.lagdaemon.creationremote.ui.screens.ProjectPickerScreen
import com.lagdaemon.creationremote.ui.screens.SessionMetadataScreen

/**
 * Screen flow: Pair -> device list (switch active target) -> project picker
 * -> session metadata -> capture. Pairing (sign-in, QR scan, device list)
 * and project listing are real -- see auth/AuthSession.kt and
 * net/RemoteApiClient.kt. Session metadata and capture-to-send are still
 * stubs (CR-M5 follow-on work: needs WebRTC signaling + the capture
 * pipeline).
 */
object CreationRemoteDestinations {
    const val PAIRING = "pairing"
    const val DEVICE_LIST = "device_list"
    const val PROJECT_PICKER = "project_picker/{hostSessionId}"
    const val SESSION_METADATA = "session_metadata"
    const val CAPTURE = "capture"

    fun projectPicker(hostSessionId: String) = "project_picker/$hostSessionId"
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
                    onDeviceSelected = { device ->
                        navController.navigate(CreationRemoteDestinations.projectPicker(device.hostSessionId))
                    },
                    onPairNewDevice = { navController.navigate(CreationRemoteDestinations.PAIRING) }
                )
            }
            composable(
                CreationRemoteDestinations.PROJECT_PICKER,
                arguments = listOf(navArgument("hostSessionId") { type = NavType.StringType })
            ) { backStackEntry ->
                val hostSessionId = backStackEntry.arguments?.getString("hostSessionId").orEmpty()
                ProjectPickerScreen(
                    hostSessionId = hostSessionId,
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
