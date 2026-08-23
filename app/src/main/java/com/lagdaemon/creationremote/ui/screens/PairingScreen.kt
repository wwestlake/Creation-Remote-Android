package com.lagdaemon.creationremote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Scan a QR code shown by a Suite Remote Receiver to pair this phone with it.
 * QR scanning and the account/token exchange against lagdaemon.com (CR-M2/M4)
 * are not implemented yet -- this is a navigable placeholder.
 */
@Composable
fun PairingScreen(onPaired: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Scan a Creation Remote Receiver's pairing QR code")
        Button(onClick = onPaired, modifier = Modifier.padding(top = 16.dp)) {
            Text("Simulate paired (stub)")
        }
    }
}
