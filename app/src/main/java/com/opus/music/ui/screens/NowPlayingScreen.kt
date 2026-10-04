package com.opus.music.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import coil.compose.AsyncImage
import com.opus.music.Graph
import com.opus.music.Session
import com.opus.music.player.PlayerManager
import com.opus.music.ui.CoverArt
import com.opus.music.ui.DownloadEvents
import com.opus.music.ui.formatDurationMs
import com.opus.music.ui.rememberIsDownloaded
import com.opus.music.ui.vm.PlayerViewModel
import kotlinx.coroutines.launch
import androidx.media3.common.Player as M3Player

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(nav: NavController) {
    val vm: PlayerViewModel = viewModel()
    val ui by vm.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var sliderOverride by remember { mutableStateOf<Float?>(null) }
    var favorite by remember(ui.songId) { mutableStateOf(ui.currentSong?.starred != null) }
    val downloaded = rememberIsDownloaded(ui.songId ?: "")
    // Sheet drag state: dragPx follows the finger live during the gesture;
    // on release the settle effect below springs it back or slides it
    // smoothly off-screen before collapsing. The state *objects* (not their
    // values) are passed to the gesture modifier so its pointer-input block
    // never restarts mid-drag on recomposition.
    val density = LocalDensity.current
    val screenHpx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // Starts off-screen so the enter animation (below) slides the sheet up
    // with no first-frame flash at resting position.
    val dragPx = remember(screenHpx) { mutableFloatStateOf(screenHpx) }
    val dragActive = remember { mutableStateOf(false) }
    val settleTick = remember { mutableIntStateOf(0) }
    val dismissThresholdPx = 180f
    val haptic = LocalHapticFeedback.current

    // Queue drag-to-reorder state. Rows are a fixed height so the drop target
    // is pure arithmetic: target = from + round(dragOffset / rowHeight).
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val rowHpx = with(density) { 64.dp.toPx() }
    val queueSize = ui.queue.size
    val dragTo = if (dragFrom >= 0 && queueSize > 0)
        (dragFrom + (dragOffsetY / rowHpx).roundToInt()).coerceIn(0, queueSize - 1)
    else -1

    // "classic" (art card + details below) or "immersive" (full-width art
    // with the title overlaid). Read when the screen opens.
    val playerLayout = remember { Graph.settings.getPlayerLayout() }

    // Dismiss: slide the sheet smoothly off the bottom, then close. Used by
    // the top-bar collapse button, the swipe-down settle, and (via the
    // dialog) the system back gesture is handled by nav itself.
    fun dismiss() {
        scope.launch {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            animate(
                dragPx.floatValue, screenHpx,
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
            ) { v, _ -> dragPx.floatValue = v }
            nav.popBackStack()
        }
    }

    LaunchedEffect(settleTick.intValue, dragActive.value) {
        if (settleTick.intValue == 0 || dragActive.value) return@LaunchedEffect
        if (dragPx.floatValue >= dismissThresholdPx) {
            dismiss()
        } else {
            animate(
                dragPx.floatValue, 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) { v, _ -> dragPx.floatValue = v }
        }
    }

    // Enter animation: the sheet slides up on open. (The player is a dialog
    // destination, so the nav enterTransition can't do this for us.)
    // dragPx starts at screenHpx (see above), so this settles to resting.
    LaunchedEffect(Unit) {
        animate(
            dragPx.floatValue, 0f,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
        ) { v, _ -> dragPx.floatValue = v }
    }

    // Prevent Screen Lock: "when playing" / "always" keeps display on here.
    // The player is a dialog destination, so the flag must go on the dialog's
    // window (the visible one), not the activity window behind it. The dialog
    // must also not dim the screen behind it — swiping down should reveal
    // the previous screen at full brightness.
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        dialogWindow?.let {
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.setDimAmount(0f)
        }
        val target = dialogWindow ?: (context as? Activity)?.window
        val mode = try { Graph.settings.getPreventScreenLock() } catch (_: Exception) { "never" }
        if (mode == "always" || mode == "playing") {
            target?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (mode != "always") {
                target?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // The drag offset is applied as a render-thread layer transform rather
    // than a layout offset, so the swipe-down gesture never triggers
    // remeasurement and stays at full frame rate.
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dragPx.floatValue }
    ) {
    // Artwork-driven ambient background: the current cover art, heavily
    // blurred, crossfading on track change, under a readability scrim that
    // melts into the theme background toward the bottom. An opaque base
    // first: the player is a dialog now, and the window behind it is the
    // real previous screen, which must only show during the swipe-down.
    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(bg))
    Crossfade(targetState = ui.artworkUrl, label = "artwork-background") { url ->
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(110.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.55f
            )
        }
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0.0f to bg.copy(alpha = 0.45f),
                0.5f to bg.copy(alpha = 0.80f),
                1.0f to bg
            )
        )
    )
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column {
                // Swipe-down handle: drag down here to collapse the full
                // player back to the mini player.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .collapseDrag(dragPx, dragActive, settleTick)
                        .padding(top = 8.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    )
                }
                TopAppBar(
                    title = { Text("Now Playing", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = { dismiss() }) { Icon(Icons.Filled.KeyboardArrowDown, "Collapse") }
                    },
                    actions = { SpeakerButton(nav); PartyButton(nav) },
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    modifier = Modifier.collapseDrag(dragPx, dragActive, settleTick)
                )
            }
        }
    ) { padding ->
        if (ui.title.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Nothing playing", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pick something from your library and it will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return@Scaffold
        }

        val listState = rememberLazyListState()
        val showAudiobookControls = remember(ui.songId) {
            PlayerManager.isCurrentAudiobook()
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            state = listState,
            // While a queue row is being drag-reordered, the list itself must
            // not scroll — the finger owns the vertical axis.
            userScrollEnabled = dragFrom < 0,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                if (playerLayout == "immersive") {
                    // Immersive: full-width artwork with the title/artist
                    // overlaid on a bottom scrim. Still a swipe target.
                    Box(
                        Modifier.fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .collapseDrag(dragPx, dragActive, settleTick)
                    ) {
                        if (ui.artworkUrl != null) {
                            AsyncImage(
                                ui.artworkUrl, null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                Modifier.fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.MusicNote, null,
                                    modifier = Modifier.size(96.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.5f to Color.Transparent,
                                    1.0f to Color.Black.copy(alpha = 0.72f)
                                )
                            )
                        )
                        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                            Text(
                                ui.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                ui.artist,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.88f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (ui.album.isNotBlank()) {
                                Text(
                                    ui.album,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                } else {
                    // Classic: artwork card with the details below it.
                    // The album art is a first-class swipe target: a downward drag
                    // starting here collapses the player. Upward drags are left
                    // unconsumed so the queue underneath still scrolls.
                    Box(Modifier.collapseDrag(dragPx, dragActive, settleTick)) {
                        CoverArt(ui.artworkUrl, 300.dp, 20.dp)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        ui.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ui.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    ui.album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))

                // Favorite + download
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = {
                        vm.toggleFavorite { starring -> favorite = starring }
                    }) {
                        Icon(
                            if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            "Favorite",
                            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        val song = ui.currentSong ?: return@IconButton
                        scope.launch {
                            val client = Session.client ?: return@launch
                            if (downloaded) Graph.downloads.delete(song.id)
                            else Graph.downloads.download(song, client.streamUrl(song.id))
                            DownloadEvents.bump()
                        }
                    }) {
                        Icon(
                            if (downloaded) Icons.Filled.Download else Icons.Filled.Download,
                            if (downloaded) "Downloaded" else "Download",
                            tint = if (downloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Seek bar
                val duration = ui.durationMs.coerceAtLeast(1)
                Slider(
                    value = sliderOverride ?: ui.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { sliderOverride = it },
                    onValueChangeFinished = {
                        sliderOverride?.let { PlayerManager.seekTo(it.toLong()) }
                        sliderOverride = null
                    },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDurationMs(sliderOverride?.toLong() ?: ui.positionMs), style = MaterialTheme.typography.bodySmall)
                    Text(formatDurationMs(ui.durationMs), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))

                // Main controls
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { PlayerManager.toggleShuffle() }) {
                        Icon(
                            Icons.Filled.Shuffle, "Shuffle",
                            tint = if (ui.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        PlayerManager.previous()
                    }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp))
                    }
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            PlayerManager.togglePlayPause()
                        },
                        modifier = Modifier.size(76.dp)
                    ) {
                        Icon(
                            if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            "Play/Pause",
                            Modifier.size(40.dp)
                        )
                    }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        PlayerManager.next()
                    }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp))
                    }
                    IconButton(onClick = { PlayerManager.cycleRepeat() }) {
                        val (icon, active) = when (ui.repeatMode) {
                            M3Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne to true
                            M3Player.REPEAT_MODE_ALL -> Icons.Filled.Repeat to true
                            else -> Icons.Filled.Repeat to false
                        }
                        Icon(icon, "Repeat", tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // Audiobook mode: speed cycler + bookmark button.
            if (ui.currentSong != null && showAudiobookControls) {
                item {
                    AudiobookControls(ui.currentSong!!)
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Synced lyrics (tap a line to seek).
            if (ui.currentSong != null) {
                item {
                    LyricsSection(ui.currentSong!!, ui.positionMs)
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (ui.queue.isNotEmpty()) {
                item {
                    Text(
                        "Up next",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                }
                itemsIndexed(ui.queue) { index, entry ->
                    val isCurrent = index == ui.currentIndex
                    val isDragging = dragFrom == index
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { v ->
                            if (v == SwipeToDismissBoxValue.EndToStart) {
                                PlayerManager.removeAt(index)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                true
                            } else false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(
                                    Icons.Filled.Delete, "Remove",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            // Drop-target indicator: a thin line above the row the
                            // dragged item will land on.
                            if (dragFrom >= 0 && dragTo == index && dragTo != dragFrom) {
                                Box(
                                    Modifier.fillMaxWidth().height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth()
                                    .height(64.dp)
                                    .offset { IntOffset(0, if (isDragging) dragOffsetY.toInt() else 0) }
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                                        else Color.Transparent
                                    )
                                    .clickable { PlayerManager.skipTo(index) }
                                    .pointerInput(index) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                dragFrom = index
                                                dragOffsetY = 0f
                                            },
                                            onDragEnd = {
                                                // Read the live offset (state delegate, not the
                                                // stale composition value) to find the target.
                                                val to = (index + (dragOffsetY / rowHpx).roundToInt())
                                                    .coerceIn(0, queueSize - 1)
                                                dragFrom = -1
                                                dragOffsetY = 0f
                                                if (to != index && queueSize > 0) {
                                                    PlayerManager.moveQueueItem(index, to)
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            },
                                            onDragCancel = {
                                                dragFrom = -1
                                                dragOffsetY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffsetY = (dragOffsetY + dragAmount.y).coerceIn(
                                                    -(index * rowHpx),
                                                    ((queueSize - 1 - index) * rowHpx)
                                                )
                                            }
                                        )
                                    }
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (entry.artwork != null) {
                                    AsyncImage(
                                        entry.artwork, null,
                                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    CoverArt(null, 44.dp, 8.dp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        entry.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                // Long-press anywhere on the row to drag it.
                                Icon(
                                    Icons.Filled.DragHandle, "Reorder",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
    } // end swipe-dismiss Box
}

/**
 * Pull-down-to-collapse gesture shared by the full player's drag handle, top
 * bar, and album artwork.
 *
 * Only downward drags are claimed: an upward drag is deliberately left
 * unconsumed so a scrolling parent (the queue list) keeps working when the
 * gesture starts on the artwork. The sheet offset is driven live through
 * [dragPx]; [dragActive] guards the settle effect while a finger is down and
 * [settleTick] triggers it on release. The state *objects* are used as
 * pointer-input keys (stable identities), so the gesture block never
 * restarts mid-drag when their values change on recomposition.
 */
private fun Modifier.collapseDrag(
    dragPx: MutableFloatState,
    dragActive: MutableState<Boolean>,
    settleTick: MutableIntState,
    maxPx: Float = 800f,
): Modifier = pointerInput(dragPx, dragActive, settleTick) {
    awaitEachGesture {
        val down = awaitFirstDown()
        var dragging = false
        awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
            if (overSlop > 0f) {
                change.consume()
                dragging = true
            }
        }
        if (!dragging) return@awaitEachGesture
        dragActive.value = true
        try {
            val pid = down.id
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pid } ?: break
                if (!change.pressed) break
                val dy = change.positionChange().y
                if (dy != 0f) {
                    change.consume()
                    dragPx.floatValue = (dragPx.floatValue + dy).coerceIn(0f, maxPx)
                }
                if (event.changes.all { !it.pressed }) break
            }
        } finally {
            dragActive.value = false
            settleTick.intValue++
        }
    }
}

/**
 * Now Playing entry point for speaker casting. Jumps straight to the
 * Speakers settings section (Sonos / Chromecast / AirPlay discovery) so the
 * cast control is one tap away from the player. Tinted primary while a
 * cast session is active.
 */
@Composable
private fun SpeakerButton(nav: NavController) {
    val castState by com.opus.music.cast.CastManager.state.collectAsState()
    val active = castState is com.opus.music.cast.CastState.Casting
    IconButton(onClick = {
        nav.popBackStack()
        nav.navigate("settings/speakers")
    }) {
        Icon(
            Icons.Filled.Speaker, "Speakers",
            tint = if (active) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
