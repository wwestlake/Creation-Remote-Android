package com.lagdaemon.creationremote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The account's paired machines (desktop, laptop, ...) with one marked as
 * the active send target -- switchable, not locked to a single device.
 * Backed by nothing real yet; the registry lives server-side (CR-M2/M4).
 */
@Composable
fun DeviceListScreen(onDeviceSelected: () -> Unit, onPairNewDevice: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Paired devices")
        Button(onClick = onDeviceSelected, modifier = Modifier.padding(top = 16.dp)) {
            Text("Use this device (stub)")
        }
        OutlinedButton(onClick = onPairNewDevice, modifier = Modifier.padding(top = 8.dp)) {
            Text("Pair a new device")
        }
    }
}
