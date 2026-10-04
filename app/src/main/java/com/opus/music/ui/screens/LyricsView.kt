package com.opus.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opus.music.Graph
import com.opus.music.lyrics.LrcParser
import com.opus.music.lyrics.LyricsData
import com.opus.music.network.Song
import com.opus.music.player.PlayerManager

/**
 * Synced-lyrics section for the full player. Auto-scrolls to the current
 * line; tapping a line seeks. Falls back to plain lyrics text, or a quiet
 * "no lyrics" note when nothing is found.
 */
@Composable
fun LyricsSection(song: Song, positionMs: Long, modifier: Modifier = Modifier) {
    var data by remember(song.id) { mutableStateOf<LyricsData?>(null) }
    var loading by remember(song.id) { mutableStateOf(true) }
    var expanded by remember(song.id) { mutableStateOf(true) }

    LaunchedEffect(song.id) {
        loading = true
        data = try { Graph.lyrics.getLyrics(song) } catch (_: Exception) { null }
        loading = false
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Lyrics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (data?.synced == true) {
                    Text(
                        "synced",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        if (expanded) "Collapse lyrics" else "Expand lyrics"
                    )
                }
            }
        }

        if (!expanded) return@Column

        when {
            loading -> {
                Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            data == null || (!data!!.synced && data!!.plainText.isNullOrBlank()) -> {
                Text(
                    "No lyrics found for this song.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            data!!.synced -> SyncedLyricsView(data!!.lines, positionMs)
            else -> {
                Text(
                    data!!.plainText.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SyncedLyricsView(
    lines: List<com.opus.music.lyrics.LyricLine>,
    positionMs: Long
) {
    val current = LrcParser.lineAt(lines, positionMs)
    val listState = rememberLazyListState()

    LaunchedEffect(current) {
        if (current >= 0) {
            try { listState.animateScrollToItem((current - 2).coerceAtLeast(0)) }
            catch (_: Exception) { }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(lines, key = { i, l -> "$i-${l.timeMs}" }) { i, line ->
            val active = i == current
            val past = i < current
            Text(
                line.text,
                style = if (active)
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                else
                    MaterialTheme.typography.bodyLarge,
                color = when {
                    active -> MaterialTheme.colorScheme.primary
                    past -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
                    .clickable { PlayerManager.seekTo(line.timeMs) }
                    .padding(vertical = 2.dp)
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
