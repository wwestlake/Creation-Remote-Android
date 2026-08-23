package com.lagdaemon.creationremote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class CaptureMode { PHOTO, VIDEO, AUDIO }

/**
 * Discrete capture-then-send, never live streaming. Photo auto-sends on
 * snap; Video/Audio are start/stop record then send. CameraX preview,
 * MediaRecorder wiring, and the actual P2P/WorkManager-queued send are not
 * implemented yet -- this establishes the mode-switching UI shape only.
 */
@Composable
fun CaptureScreen(onSwitchDevice: () -> Unit) {
    var mode by remember { mutableStateOf(CaptureMode.PHOTO) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        SingleChoiceSegmentedButtonRow {
            CaptureMode.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = mode == entry,
                    onClick = { mode = entry },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = CaptureMode.entries.size)
                ) {
                    Text(entry.name)
                }
            }
        }
        Text(
            when (mode) {
                CaptureMode.PHOTO -> "Photo -- auto-sends after each snap (stub)"
                CaptureMode.VIDEO -> "Video -- start/stop record, then send (stub)"
                CaptureMode.AUDIO -> "Audio -- mic-only, start/stop record, then send (stub)"
            },
            modifier = Modifier.padding(top = 24.dp)
        )
        Row(modifier = Modifier.padding(top = 24.dp)) {
            OutlinedButton(onClick = onSwitchDevice) {
                Text("Switch device")
            }
        }
    }
}
