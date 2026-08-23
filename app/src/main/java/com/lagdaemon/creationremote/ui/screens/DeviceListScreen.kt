package com.lagdaemon.creationremote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lagdaemon.creationremote.auth.AuthSession
import com.lagdaemon.creationremote.net.PairedDevice
import com.lagdaemon.creationremote.net.RemoteApiClient

/**
 * The account's paired machines, switchable -- one marked active as the
 * current send target, not locked to a single device. Real data from
 * GET /api/remote/devices.
 */
@Composable
fun DeviceListScreen(onDeviceSelected: (PairedDevice) -> Unit, onPairNewDevice: () -> Unit) {
    val context = LocalContext.current
    val authSession = remember { AuthSession(context.applicationContext) }
    var devices by remember { mutableStateOf<List<PairedDevice>?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val session = authSession.tokenStore.currentSession()
        if (session == null) {
            errorText = "Not signed in"
            return@LaunchedEffect
        }
        RemoteApiClient(session.token).listDevices()
            .onSuccess { devices = it }
            .onFailure { errorText = it.message ?: "Could not load paired devices" }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Paired devices")

        when {
            errorText != null -> Text(errorText!!, modifier = Modifier.padding(top = 16.dp))
            devices == null -> CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            devices!!.isEmpty() -> Text("No paired devices yet.", modifier = Modifier.padding(top = 16.dp))
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                items(devices!!) { device ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(device.deviceName ?: device.productSlug)
                            Text(device.presenceState)
                            Button(modifier = Modifier.padding(top = 8.dp), onClick = { onDeviceSelected(device) }) {
                                Text("Use this device")
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(modifier = Modifier.padding(top = 16.dp), onClick = onPairNewDevice) {
            Text("Pair a new device")
        }
    }
}
