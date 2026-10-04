package com.opus.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opus.music.Graph
import com.opus.music.network.Song
import com.opus.music.player.PlayerManager
import com.opus.music.ui.components.OpusTextField
import com.opus.music.ui.formatDurationMs
import kotlinx.coroutines.launch

/**
 * Audiobook controls shown in the full player when the current track is a
 * spoken-word / long-form item: playback speed cycler and a bookmark button.
 */
@Composable
fun AudiobookControls(song: Song, modifier: Modifier = Modifier) {
    val speed by PlayerManager.playbackSpeed.collectAsState()
    val scope = rememberCoroutineScope()
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkComment by remember { mutableStateOf("") }
    var bookmarkSaved by remember { mutableStateOf<Boolean?>(null) }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = { PlayerManager.cycleAudiobookSpeed() }) {
            Icon(Icons.Filled.Speed, "Playback speed", modifier = Modifier.padding(end = 4.dp))
            Text(formatSpeed(speed))
        }
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = {
            bookmarkComment = ""
            bookmarkSaved = null
            showBookmarkDialog = true
        }) {
            Icon(Icons.Filled.BookmarkAdd, "Add bookmark")
        }
    }

    if (showBookmarkDialog) {
        AlertDialog(
            onDismissRequest = { showBookmarkDialog = false },
            title = { Text("Add bookmark") },
            text = {
                Column {
                    Text(
                        "Saved at ${formatDurationMs(PlayerManager.controller.value?.currentPosition ?: 0L)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OpusTextField(
                        value = bookmarkComment,
                        onValueChange = { bookmarkComment = it },
                        label = "Note (optional)",
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (bookmarkSaved == false) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Couldn't save — check your connection.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val pos = try {
                            PlayerManager.controller.value?.currentPosition ?: 0L
                        } catch (_: Exception) { 0L }
                        val ok = try {
                            Graph.audiobooks.addBookmark(song.id, pos, bookmarkComment)
                        } catch (_: Exception) { false }
                        bookmarkSaved = ok
                        if (ok) showBookmarkDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showBookmarkDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun formatSpeed(speed: Float): String {
    val s = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
    return "${s}x"
}
