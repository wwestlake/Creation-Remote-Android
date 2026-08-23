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
import com.lagdaemon.creationremote.net.RemoteApiClient
import com.lagdaemon.creationremote.net.RemoteProject

/**
 * Lists the active paired receiver's projects (GET
 * /api/remote/host-sessions/{id}/projects) -- sourced from that receiver's
 * local ProjectRegistry, relayed via its check-in heartbeat.
 */
@Composable
fun ProjectPickerScreen(hostSessionId: String, onProjectSelected: (RemoteProject) -> Unit) {
    val context = LocalContext.current
    val authSession = remember { AuthSession(context.applicationContext) }
    var projects by remember { mutableStateOf<List<RemoteProject>?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hostSessionId) {
        val session = authSession.tokenStore.currentSession()
        if (session == null) {
            errorText = "Not signed in"
            return@LaunchedEffect
        }
        RemoteApiClient(session.token).listProjects(hostSessionId)
            .onSuccess { projects = it }
            .onFailure { errorText = it.message ?: "Could not load projects" }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Pick a project")

        when {
            errorText != null -> Text(errorText!!, modifier = Modifier.padding(top = 16.dp))
            projects == null -> CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            projects!!.isEmpty() -> Text("No projects found on this device.", modifier = Modifier.padding(top = 16.dp))
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                items(projects!!) { project ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Button(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            onClick = { onProjectSelected(project) }
                        ) { Text(project.displayName) }
                    }
                }
            }
        }
    }
}
