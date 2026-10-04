package com.opus.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.opus.music.Graph
import com.opus.music.Session
import com.opus.music.data.SwipeActions
import com.opus.music.network.Album
import com.opus.music.network.Artist
import com.opus.music.network.Playlist
import com.opus.music.network.Song
import com.opus.music.player.PlayerManager
import com.opus.music.ui.vm.PlayerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Bumped whenever the offline set changes so rows refresh their download state. */
object DownloadEvents {
    val version = MutableStateFlow(0)
    fun bump() { version.value += 1 }
}

@Composable
fun rememberIsDownloaded(songId: String): Boolean {
    val version by DownloadEvents.version.collectAsState()
    val downloaded by produceState(initialValue = false, songId, version) {
        value = Graph.downloads.isDownloaded(songId)
    }
    return downloaded
}

fun formatDuration(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}

fun formatDurationMs(ms: Long): String = formatDuration((ms / 1000).toInt())

@Composable
fun CoverArt(
    url: String?,
    size: Dp,
    corner: Dp = 10.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Icon(
            Icons.Filled.MusicNote, null,
            modifier = Modifier.align(Alignment.Center).size(size * 0.45f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onAction).padding(4.dp)
            )
        }
    }
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun EmptyBox(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Song row with configurable horizontal swipe actions (settings → Swipe).
 * Same public signature as before; the inner content is unchanged.
 */
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onGoAlbum: (() -> Unit)? = null,
    onGoArtist: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val client = Session.client
    val downloaded = rememberIsDownloaded(song.id)
    var offsetX by remember(song.id) { mutableStateOf(0f) }
    val maxSwipe = with(density) { 120.dp.toPx() }
    val threshold = with(density) { 64.dp.toPx() }
    val leftAction = Graph.settings.getSwipeAction(SwipeActions.KEY_SONG_LEFT)
    val rightAction = Graph.settings.getSwipeAction(SwipeActions.KEY_SONG_RIGHT)

    fun fire(action: String) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        when (action) {
            SwipeActions.PLAY_NEXT -> PlayerManager.playNext(song, Graph.downloads)
            SwipeActions.ADD_TO_QUEUE -> PlayerManager.addToQueue(song, Graph.downloads)
            SwipeActions.DOWNLOAD -> scope.launch {
                try {
                    if (downloaded) Graph.downloads.delete(song.id)
                    else client?.let { Graph.downloads.download(song, it.streamUrl(song.id)) }
                    DownloadEvents.bump()
                } catch (_: Exception) {
                }
            }
        }
    }

    Box(modifier.fillMaxWidth()) {
        // Reveal layer: shows the pending action while dragging.
        if (offsetX != 0f) {
            val pending = if (offsetX < 0f) leftAction else rightAction
            val armed = abs(offsetX) >= threshold && pending != SwipeActions.NONE
            Box(
                Modifier.matchParentSize()
                    .background(
                        if (armed) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = 24.dp),
                contentAlignment = if (offsetX < 0f) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Text(
                    SwipeActions.label(pending),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (armed) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(song.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val key =
                                if (offsetX < 0f) SwipeActions.KEY_SONG_LEFT
                                else SwipeActions.KEY_SONG_RIGHT
                            val action = Graph.settings.getSwipeAction(key)
                            if (abs(offsetX) >= threshold && action != SwipeActions.NONE) {
                                fire(action)
                            }
                            offsetX = 0f
                        },
                        onDragCancel = { offsetX = 0f }
                    ) { change, dragAmount ->
                        offsetX = (offsetX + dragAmount).coerceIn(-maxSwipe, maxSwipe)
                        change.consume()
                    }
                }
        ) {
            SongRowContent(song, onClick, onGoAlbum, onGoArtist)
        }
    }
}

@Composable
private fun SongRowContent(
    song: Song,
    onClick: () -> Unit,
    onGoAlbum: (() -> Unit)? = null,
    onGoArtist: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val downloaded = rememberIsDownloaded(song.id)
    val client = Session.client

    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(client?.coverArtUrl(song.coverArt, 200), 52.dp, 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(song.artist, formatDuration(song.duration)).joinToString(" • "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall
            )
        }
        if (downloaded) {
            Icon(Icons.Filled.Check, "Downloaded", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play next") }, onClick = {
                    menu = false
                    PlayerManager.playNext(song, Graph.downloads)
                })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = {
                    menu = false
                    PlayerManager.addToQueue(song, Graph.downloads)
                })
                DropdownMenuItem(text = { Text(if (downloaded) "Remove download" else "Download") }, onClick = {
                    menu = false
                    scope.launch {
                        if (downloaded) Graph.downloads.delete(song.id)
                        else client?.let { Graph.downloads.download(song, it.streamUrl(song.id)) }
                        DownloadEvents.bump()
                    }
                })
                onGoAlbum?.let {
                    DropdownMenuItem(text = { Text("Go to album") }, onClick = { menu = false; it() })
                }
                onGoArtist?.let {
                    DropdownMenuItem(text = { Text("Go to artist") }, onClick = { menu = false; it() })
                }
            }
        }
    }
}

@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(148.dp).clickable(onClick = onClick)) {
        CoverArt(Session.client?.coverArtUrl(album.coverArt, 400), 148.dp, 12.dp)
        Spacer(Modifier.height(8.dp))
        Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(
            album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun ArtistRow(artist: Artist, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val url = Session.client?.coverArtUrl(artist.coverArt, 200)
            if (url != null) {
                AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(artist.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${artist.albumCount} albums", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PlaylistRow(playlist: Playlist, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(Session.client?.coverArtUrl(playlist.coverArt, 200), 52.dp, 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${playlist.songCount} songs", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MiniPlayer(onTap: () -> Unit) {
    val vm: PlayerViewModel = viewModel()
    val ui by vm.uiState.collectAsState()
    if (!ui.connected || ui.title.isEmpty()) return

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val threshold = with(density) { 48.dp.toPx() }

    fun fireMini(action: String) {
        when (action) {
            SwipeActions.OPEN_PLAYER -> onTap()
            SwipeActions.PLAY_PAUSE -> PlayerManager.togglePlayPause()
            SwipeActions.NEXT -> PlayerManager.next()
            SwipeActions.PREVIOUS -> PlayerManager.previous()
        }
    }

    // Tap still opens the player (Surface onClick); drags fire the
    // configured swipe actions without triggering the tap.
    Surface(
        onClick = onTap,
        tonalElevation = 6.dp,
        modifier = Modifier.pointerInput(Unit) {
            var total = Offset.Zero
            detectDragGestures(
                onDragStart = { total = Offset.Zero },
                onDragEnd = {
                    val dx = total.x
                    val dy = total.y
                    val key = when {
                        abs(dy) >= threshold && abs(dy) > abs(dx) ->
                            if (dy < 0) SwipeActions.KEY_MINI_UP else SwipeActions.KEY_MINI_DOWN
                        abs(dx) >= threshold && abs(dx) > abs(dy) ->
                            if (dx < 0) SwipeActions.KEY_MINI_LEFT else SwipeActions.KEY_MINI_RIGHT
                        else -> null
                    }
                    if (key != null) {
                        val action = Graph.settings.getSwipeAction(key)
                        if (action != SwipeActions.NONE) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            fireMini(action)
                        }
                    }
                },
                onDragCancel = { }
            ) { change, dragAmount ->
                change.consume()
                total += dragAmount
            }
        }
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverArt(ui.artworkUrl, 44.dp, 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(ui.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { PlayerManager.togglePlayPause() }) {
                    Icon(if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause")
                }
            }
            val progress = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
    }
}
