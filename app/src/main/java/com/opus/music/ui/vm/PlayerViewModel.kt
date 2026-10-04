package com.opus.music.ui.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import com.opus.music.Graph
import com.opus.music.Session
import com.opus.music.data.SongMeta
import com.opus.music.network.Song
import com.opus.music.player.PlayerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class QueueEntry(
    val id: String,
    val title: String,
    val artist: String?,
    val artwork: Uri?
)

data class PlayerUiState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val songId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val currentSong: Song? = null
)

class PlayerViewModel : ViewModel() {
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(400)
        }
    }

    // The 400 ms ticker only drives the progress position. Everything else
    // (queue list, artwork, metadata) is referentially stable across ticks
    // thanks to distinctUntilChanged, so Compose skips recomposing the heavy
    // parts of the player 2.5x/second — this is what keeps the swipe-down
    // collapse gesture at full frame rate.
    private val staticState: StateFlow<PlayerUiState> = combine(
        PlayerManager.controller,
        PlayerManager.isPlaying,
        PlayerManager.currentSongId,
        PlayerManager.queueSongs,
        com.opus.music.cast.CastManager.playback
    ) { controller, playing, songId, songs, castPb ->
        if (controller == null) return@combine PlayerUiState()
        if (castPb != null) {
            // Casting: the SPEAKER is the playback — show its song,
            // its queue position and its play state, not the paused
            // local player's (which made the controls look dead).
            val client = Session.client
            fun artUri(cover: String?, size: Int) = try {
                client?.coverArtUrl(cover, size)?.let { android.net.Uri.parse(it) }
            } catch (_: Exception) {
                null
            }
            return@combine PlayerUiState(
                connected = true,
                isPlaying = castPb.isPlaying,
                songId = castPb.song.id,
                title = castPb.song.title,
                artist = castPb.song.artist.orEmpty(),
                album = castPb.song.album.orEmpty(),
                artworkUrl = artUri(castPb.song.coverArt, 500)?.toString(),
                shuffle = controller.shuffleModeEnabled,
                repeatMode = controller.repeatMode,
                queue = songs.map { s ->
                    QueueEntry(s.id, s.title, s.artist, artUri(s.coverArt, 200))
                },
                currentIndex = castPb.index,
                currentSong = castPb.song
            )
        }
        val meta = controller.currentMediaItem?.mediaMetadata
        val song = songs.firstOrNull { it.id == songId }
        PlayerUiState(
            connected = true,
            isPlaying = playing,
            songId = songId,
            title = meta?.title?.toString().orEmpty(),
            artist = meta?.artist?.toString().orEmpty(),
            album = meta?.albumTitle?.toString().orEmpty(),
            artworkUrl = meta?.artworkUri?.toString(),
            shuffle = controller.shuffleModeEnabled,
            repeatMode = controller.repeatMode,
            queue = (0 until controller.mediaItemCount).map { i ->
                val mi = controller.getMediaItemAt(i)
                QueueEntry(
                    id = mi.mediaId,
                    title = mi.mediaMetadata.title?.toString().orEmpty(),
                    artist = mi.mediaMetadata.artist?.toString(),
                    artwork = mi.mediaMetadata.artworkUri
                )
            },
            currentIndex = controller.currentMediaItemIndex,
            currentSong = song
        )
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    val uiState: StateFlow<PlayerUiState> = combine(staticState, ticker) { s, _ ->
        val castPb = com.opus.music.cast.CastManager.playback.value
        if (castPb != null && s.connected) {
            s.copy(
                positionMs = com.opus.music.cast.CastManager.positionEstimateMs()
                    .coerceAtLeast(0),
                durationMs = (castPb.song.duration * 1000L).coerceAtLeast(0)
            )
        } else {
            val controller = PlayerManager.controller.value
            if (controller == null || !s.connected) s
            else s.copy(
                positionMs = controller.currentPosition.coerceAtLeast(0),
                durationMs = controller.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0L
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    init {
        // Scrobble tracks listened for more than 30s when they change.
        viewModelScope.launch {
            var lastId: String? = null
            var startedAt = 0L
            PlayerManager.currentSongId.collect { id ->
                val now = System.currentTimeMillis()
                val prev = lastId
                if (prev != null && prev != id && now - startedAt > 30_000) {
                    try {
                        val scrobbleOn = try { Graph.settings.isScrobbleEnabled() } catch (_: Exception) { true }
                        if (scrobbleOn) Session.music?.scrobble(prev, now - startedAt)
                    } catch (_: Exception) { }
                    // Local play count for Smart Offline Mix (independent of scrobble toggle).
                    try {
                        val s = PlayerManager.queueSongs.value.firstOrNull { it.id == prev }
                        if (s != null) {
                            Graph.stats.recordPlay(
                                SongMeta(
                                    id = s.id, title = s.title, artist = s.artist,
                                    album = s.album, coverArt = s.coverArt,
                                    duration = s.duration, suffix = s.suffix
                                )
                            )
                        }
                    } catch (_: Exception) { }
                }
                lastId = id
                startedAt = now
            }
        }
    }

    fun toggleFavorite(onDone: (Boolean) -> Unit) {
        val song = uiState.value.currentSong ?: return
        viewModelScope.launch {
            try {
                val starring = song.starred == null
                if (starring) Session.music?.star(song.id) else Session.music?.unstar(song.id)
                onDone(starring)
            } catch (_: Exception) { }
        }
    }
}
