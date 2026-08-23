package com.lagdaemon.creationremote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Free text entered once per session, used as part of the filename and/or
 * asset description for everything captured under this session. Persisting
 * this alongside the picked project (so multiple captures reuse it without
 * re-entry) is part of the real capture-flow implementation, not this stub.
 */
@Composable
fun SessionMetadataScreen(onSessionStarted: () -> Unit) {
    var sessionNote by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Session notes")
        OutlinedTextField(
            value = sessionNote,
            onValueChange = { sessionNote = it },
            label = { Text("Used in filename / asset description") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        Button(onClick = onSessionStarted, modifier = Modifier.padding(top = 16.dp)) {
            Text("Start capturing")
        }
    }
}
