package com.lagdaemon.creationremote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Lists the active paired receiver's known projects (sourced from CR-M2/M4's
 * project-list query, itself sourced from the receiver's local
 * ProjectRegistry). Not wired up yet.
 */
@Composable
fun ProjectPickerScreen(onProjectSelected: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Pick a project")
        Button(onClick = onProjectSelected, modifier = Modifier.padding(top = 16.dp)) {
            Text("Select project (stub)")
        }
    }
}
