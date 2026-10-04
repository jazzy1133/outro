package com.opus.music.player

import android.content.ComponentName
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.opus.music.Graph
import com.opus.music.Session
import com.opus.music.data.DownloadRepository
import com.opus.music.network.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-wide playback controller. Binds to [PlayerService] once and exposes
 * simple queue operations to the UI layer.
 */
object PlayerManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private val _currentSongId = MutableStateFlow<String?>(null)
    val currentSongId: StateFlow<String?> = _currentSongId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Full Song objects for the current queue (for artwork ids, download, star...). */
    private val _queueSongs = MutableStateFlow<List<Song>>(emptyList())
    val queueSongs: StateFlow<List<Song>> = _queueSongs.asStateFlow()

    /** Current playback speed. 1x normally; audiobooks restore their saved speed. */
    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private var bound = false

    // Last known-good audiobook position. onMediaItemTransition fires after
    // the player has already moved to the new item, so a live
    // currentPosition read there belongs to the wrong track; the poller
    // below snapshots it every 10 s while an audiobook plays.
    private var lastAudiobookSongId: String? = null
    private var lastAudiobookPosMs: Long = 0L
    private var positionPoller: kotlinx.coroutines.Job? = null

    fun connect(context: Context) {
        if (bound) return
        bound = true
        positionPoller?.cancel()
        positionPoller = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000L)
                try {
                    val song = currentSong()
                    if (song != null && _isPlaying.value &&
                        com.opus.music.audiobook.AudiobookManager.isAudiobook(song)
                    ) {
                        lastAudiobookSongId = song.id
                        lastAudiobookPosMs = _controller.value?.currentPosition ?: 0L
                    }
                } catch (_: Exception) { }
            }
        }
        try {
            val token = SessionToken(context, ComponentName(context, PlayerService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({
                try {
                    val c = future.get()
                    _controller.value = c
                    c.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            _isPlaying.value = playing
                            // Audio session id is 0 until audio actually flows;
                            // retry the EQ attach whenever playback starts.
                            if (playing) applyEqSettings()
                        }
                        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                            val oldId = _currentSongId.value
                            _currentSongId.value = item?.mediaId
                            handleAudiobookTransition(oldId, item?.mediaId)
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            // A finished audiobook shouldn't auto-resume next time.
                            if (state == Player.STATE_ENDED) {
                                val song = currentSong()
                                if (song != null) {
                                    try {
                                        if (com.opus.music.audiobook.AudiobookManager.isAudiobook(song)) {
                                            scope.launch(Dispatchers.IO) {
                                                Graph.audiobooks.clearResumePosition(song.id)
                                            }
                                        }
                                    } catch (_: Exception) { }
                                }
                            }
                        }
                    })
                    _isPlaying.value = c.isPlaying
                    _currentSongId.value = c.currentMediaItem?.mediaId
                    applyEqSettings()
                } catch (e: Exception) {
                    bound = false
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            bound = false
        }
    }

    /** Public builder used by the crossfade engine (uses the shared download repo). */
    fun mediaItemFor(song: Song): MediaItem {
        val downloads = try { Graph.downloads } catch (_: Exception) { null }
        return if (downloads != null) mediaItemFor(song, downloads)
        else {
            // Fallback: stream URL only.
            val client = Session.client
            val bitrate = try { Graph.settings.getStreamBitrate() } catch (_: Exception) { 0 }
            val uri = client?.streamUrl(song.id, bitrate) ?: ""
            MediaItem.Builder().setMediaId(song.id).setUri(uri).build()
        }
    }

    private fun mediaItemFor(song: Song, downloads: DownloadRepository): MediaItem {
        val client = Session.client
        val local = downloads.localUri(song.id)
        val bitrate = try { Graph.settings.getStreamBitrate() } catch (_: Exception) { 0 }
        val uri = local?.toString()
            ?: client?.streamUrl(song.id, bitrate)
            ?: ""
        val art = client?.coverArtUrl(song.coverArt, 500)?.toUri()
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(art)
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    fun playSongs(songs: List<Song>, index: Int, downloads: DownloadRepository) {
        playSongs(songs, index, downloads, startPositionMs = 0L)
    }

    /** Play a queue, optionally starting [index] at [startPositionMs]. */
    fun playSongs(
        songs: List<Song>,
        index: Int,
        downloads: DownloadRepository,
        startPositionMs: Long
    ) {
        if (songs.isEmpty()) return
        if (com.opus.music.cast.CastManager.isCasting) {
            // A new selection while casting goes to the SPEAKER. The
            // local player stays paused and untouched — before this,
            // picking a song mid-cast played it on the phone while the
            // speaker kept the first cast track.
            _queueSongs.value = songs
            val device = com.opus.music.cast.CastManager.castingDevice ?: return
            com.opus.music.cast.CastManager.castTo(
                device, songs, index.coerceIn(songs.indices)
            )
            return
        }
        val c = _controller.value ?: return
        EngineHolder.crossfade?.abortXfade()
        val items = songs.map { mediaItemFor(it, downloads) }
        _queueSongs.value = songs
        scope.launch(Dispatchers.Main) {
            c.setMediaItems(items, index.coerceIn(items.indices), startPositionMs)
            c.prepare()
            c.play()
        }
    }

    fun playSingle(song: Song, downloads: DownloadRepository) =
        playSongs(listOf(song), 0, downloads)

    /** Play a raw stream URL (internet radio). Shows [title]/[artist] as metadata. */
    fun playUrl(url: String, title: String, artist: String? = null) {
        if (url.isBlank()) return
        if (com.opus.music.cast.CastManager.isCasting) {
            // Radio while casting: hand the direct URL to the speaker
            // (CastManager.streamUrlFor unwraps the "radio:" id).
            _queueSongs.value = emptyList()
            val device = com.opus.music.cast.CastManager.castingDevice ?: return
            val radioSong = Song(id = "radio:$url", title = title, artist = artist)
            com.opus.music.cast.CastManager.castTo(device, listOf(radioSong), 0)
            return
        }
        val c = _controller.value ?: return
        EngineHolder.crossfade?.abortXfade()
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .build()
        val item = MediaItem.Builder()
            .setMediaId("radio:$url")
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
        _queueSongs.value = emptyList()
        scope.launch(Dispatchers.Main) {
            c.setMediaItem(item)
            c.prepare()
            c.play()
        }
    }

    private fun queueIndexOfCurrent(): Int {
        val id = _currentSongId.value ?: return -1
        return _queueSongs.value.indexOfFirst { it.id == id }
    }

    /** Current queue position, or -1. Used when handing the queue to a speaker. */
    fun queueIndex(): Int = queueIndexOfCurrent()

    /** Insert right after the currently playing song. */
    fun playNext(song: Song, downloads: DownloadRepository) {
        if (com.opus.music.cast.CastManager.isCasting) {
            // Queue-only edit while casting; the speaker's queue follows.
            val at = (com.opus.music.cast.CastManager.playback.value?.index ?: -1) + 1
            val list = _queueSongs.value.toMutableList()
            list.add(at.coerceIn(0, list.size), song)
            _queueSongs.value = list
            com.opus.music.cast.CastManager.updateQueue(list)
            return
        }
        val c = _controller.value ?: return
        val item = mediaItemFor(song, downloads)
        EngineHolder.crossfade?.abortXfade()
        scope.launch(Dispatchers.Main) {
            if (c.mediaItemCount == 0) {
                _queueSongs.value = listOf(song)
                c.setMediaItem(item)
                c.prepare()
                c.play()
            } else {
                val at = (queueIndexOfCurrent() + 1).coerceIn(0, _queueSongs.value.size)
                val list = _queueSongs.value.toMutableList()
                list.add(at, song)
                _queueSongs.value = list
                c.addMediaItem(c.currentMediaItemIndex + 1, item)
            }
        }
    }

    /** Append to the current queue, or start playing if the queue is empty. */
    fun addToQueue(song: Song, downloads: DownloadRepository) {
        if (com.opus.music.cast.CastManager.isCasting) {
            val list = _queueSongs.value + song
            _queueSongs.value = list
            com.opus.music.cast.CastManager.updateQueue(list)
            return
        }
        val c = _controller.value ?: return
        val item = mediaItemFor(song, downloads)
        EngineHolder.crossfade?.abortXfade()
        scope.launch(Dispatchers.Main) {
            if (c.mediaItemCount == 0) {
                _queueSongs.value = listOf(song)
                c.setMediaItem(item)
                c.prepare()
                c.play()
            } else {
                _queueSongs.value = _queueSongs.value + song
                c.addMediaItem(item)
            }
        }
    }

    fun togglePlayPause() {
        if (com.opus.music.cast.CastManager.isCasting) {
            com.opus.music.cast.CastManager.togglePlayPause()
            return
        }
        val c = _controller.value ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun pause() {
        if (com.opus.music.cast.CastManager.isCasting) {
            com.opus.music.cast.CastManager.pause()
            return
        }
        pauseLocal()
    }

    /** Pause local playback even while casting (used by CastManager). */
    fun pauseLocal() {
        try {
            saveAudiobookResume()
            _controller.value?.pause()
        } catch (_: Exception) {}
    }

    fun next() {
        if (com.opus.music.cast.CastManager.isCasting) {
            com.opus.music.cast.CastManager.next()
            return
        }
        if (EngineHolder.crossfade?.next() == true) return
        _controller.value?.seekToNextMediaItem()
    }

    fun previous() {
        if (com.opus.music.cast.CastManager.isCasting) {
            com.opus.music.cast.CastManager.previous()
            return
        }
        if (EngineHolder.crossfade?.previous() == true) return
        _controller.value?.seekToPreviousMediaItem()
    }

    fun skipTo(index: Int) {
        if (com.opus.music.cast.CastManager.isCasting) {
            com.opus.music.cast.CastManager.skipTo(index)
            return
        }
        if (EngineHolder.crossfade?.skipTo(index) == true) return
        _controller.value?.let {
            if (index in 0 until it.mediaItemCount) {
                it.seekTo(index, 0L)
                it.play()
            }
        }
    }
    fun seekTo(ms: Long) {
        if (com.opus.music.cast.CastManager.isCasting) {
            com.opus.music.cast.CastManager.seekTo(ms)
            return
        }
        val c = _controller.value
        if (c != null) {
            c.seekTo(ms.coerceAtLeast(0))
        } else {
            // Controller not (re)bound yet: drive the session player
            // directly rather than silently dropping the seek (the
            // widget controls already use this path).
            try { EngineHolder.exoPlayer?.seekTo(ms.coerceAtLeast(0)) } catch (_: Exception) {}
        }
    }

    fun seekForward() {
        if (com.opus.music.cast.CastManager.isCasting) {
            val cm = com.opus.music.cast.CastManager
            cm.seekTo(cm.positionEstimateMs() + 10_000)
            return
        }
        _controller.value?.let {
            val dur = it.duration
            val target = it.currentPosition + 10_000
            // duration is C.TIME_UNSET (negative) until the stream is
            // prepared; clamping against coerceAtLeast(0) then would
            // slam every +10s seek back to 0.
            it.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
        }
    }

    fun seekBack() {
        if (com.opus.music.cast.CastManager.isCasting) {
            val cm = com.opus.music.cast.CastManager
            cm.seekTo((cm.positionEstimateMs() - 10_000).coerceAtLeast(0))
            return
        }
        _controller.value?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
    }

    /** Local repeat mode (Player.REPEAT_MODE_*); read by CastManager at track end. */
    fun repeatMode(): Int =
        try { _controller.value?.repeatMode } catch (_: Exception) { null }
            ?: Player.REPEAT_MODE_OFF

    // ---- Audiobook mode ----

    private fun currentSong(): Song? {
        val id = _currentSongId.value ?: return null
        return _queueSongs.value.firstOrNull { it.id == id }
    }

    /** True when the currently playing track is an audiobook / spoken-word item. */
    fun isCurrentAudiobook(): Boolean {
        val song = currentSong() ?: return false
        return try { com.opus.music.audiobook.AudiobookManager.isAudiobook(song) } catch (_: Exception) { false }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        try { _controller.value?.setPlaybackSpeed(speed) } catch (_: Exception) { }
        val song = currentSong()
        if (song != null) {
            try { Graph.audiobooks.setSpeed(song, speed) } catch (_: Exception) { }
        }
    }

    /** Advance to the next audiobook speed step (0.5x → 3x, wraps). */
    fun cycleAudiobookSpeed() {
        try {
            setPlaybackSpeed(Graph.audiobooks.cycleSpeed(_playbackSpeed.value))
        } catch (_: Exception) { }
    }

    private fun handleAudiobookTransition(oldId: String?, newId: String?) {
        val ab = try { Graph.audiobooks } catch (_: Exception) { null } ?: return
        val songs = _queueSongs.value
        val oldSong = songs.firstOrNull { it.id == oldId }
        val newSong = songs.firstOrNull { it.id == newId }
        val c = _controller.value
        // Save a resume point for the audiobook we're leaving. Use the
        // poller's snapshot: a live currentPosition here already belongs
        // to the new track.
        if (oldSong != null && com.opus.music.audiobook.AudiobookManager.isAudiobook(oldSong)) {
            val pos = if (lastAudiobookSongId == oldId) lastAudiobookPosMs
            else try { c?.currentPosition } catch (_: Exception) { null } ?: 0L
            scope.launch(Dispatchers.IO) { ab.saveResumePosition(oldSong.id, pos) }
        }
        if (newSong != null && com.opus.music.audiobook.AudiobookManager.isAudiobook(newSong)) {
            // Restore the book's saved speed.
            val speed = ab.getSpeed(newSong)
            _playbackSpeed.value = speed
            try { c?.setPlaybackSpeed(speed) } catch (_: Exception) { }
            // Auto-resume from the saved position (fresh track starts at 0).
            scope.launch(Dispatchers.IO) {
                val resume = ab.getResumePosition(newSong.id)
                if (resume > 10_000) {
                    val cur = try { c?.currentPosition } catch (_: Exception) { null } ?: 0L
                    if (cur < 5_000) {
                        scope.launch(Dispatchers.Main) {
                            try { c?.seekTo(resume) } catch (_: Exception) { }
                        }
                    }
                }
            }
        } else if (_playbackSpeed.value != 1f) {
            _playbackSpeed.value = 1f
            try { c?.setPlaybackSpeed(1f) } catch (_: Exception) { }
        }
    }

    /** Save the audiobook resume point for the current track (call on pause). */
    fun saveAudiobookResume() {
        val song = currentSong() ?: return
        try {
            if (!com.opus.music.audiobook.AudiobookManager.isAudiobook(song)) return
            val pos = try { _controller.value?.currentPosition } catch (_: Exception) { null } ?: 0L
            lastAudiobookSongId = song.id
            lastAudiobookPosMs = pos
            scope.launch(Dispatchers.IO) { Graph.audiobooks.saveResumePosition(song.id, pos) }
        } catch (_: Exception) { }
    }

    fun toggleShuffle() {
        _controller.value?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        _controller.value?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun removeAt(index: Int) {
        if (com.opus.music.cast.CastManager.isCasting) {
            val list = _queueSongs.value.toMutableList()
            if (index in list.indices) {
                list.removeAt(index)
                _queueSongs.value = list
                com.opus.music.cast.CastManager.updateQueue(list)
            }
            return
        }
        EngineHolder.crossfade?.abortXfade()
        _controller.value?.removeMediaItem(index)
        val list = _queueSongs.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _queueSongs.value = list
        }
    }

    /** Drag-to-reorder support: move a queue entry within the player timeline. */
    fun moveQueueItem(from: Int, to: Int) {
        if (from == to) return
        if (com.opus.music.cast.CastManager.isCasting) {
            val list = _queueSongs.value.toMutableList()
            if (from in list.indices && to in 0..list.size) {
                val s = list.removeAt(from)
                list.add(to.coerceAtMost(list.size), s)
                _queueSongs.value = list
                com.opus.music.cast.CastManager.updateQueue(list)
            }
            return
        }
        val c = _controller.value ?: return
        EngineHolder.crossfade?.abortXfade()
        scope.launch(Dispatchers.Main) {
            try {
                c.moveMediaItem(from, to)
                val list = _queueSongs.value.toMutableList()
                if (from in list.indices && to in 0..list.size) {
                    val s = list.removeAt(from)
                    list.add(to.coerceAtMost(list.size), s)
                    _queueSongs.value = list
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Reorder the upcoming queue (songs after the current one) to [ids].
     * Used by Party Queue voting so the most-voted song plays next.
     */
    fun reorderUpcoming(ids: List<String>) {
        if (com.opus.music.cast.CastManager.isCasting) {
            val pb = com.opus.music.cast.CastManager.playback.value ?: return
            val songs = _queueSongs.value
            val cur = pb.index
            if (cur !in songs.indices) return
            val byId = songs.associateBy { it.id }
            val newTail = ids.mapNotNull { byId[it] }
            val newSongs = songs.subList(0, cur + 1) + newTail
            if (newSongs.size != songs.size) return
            _queueSongs.value = newSongs
            com.opus.music.cast.CastManager.updateQueue(newSongs)
            return
        }
        val c = _controller.value ?: return
        EngineHolder.crossfade?.abortXfade()
        scope.launch(Dispatchers.Main) {
            try {
                val cur = c.currentMediaItemIndex
                if (cur < 0) return@launch
                val songs = _queueSongs.value
                if (cur >= songs.size) return@launch
                val byId = songs.associateBy { it.id }
                val newTail = ids.mapNotNull { byId[it] }
                val newSongs = songs.subList(0, cur + 1) + newTail
                if (newSongs.size != songs.size) return@launch
                val items = newSongs.map { mediaItemFor(it) }
                val wasPlaying = c.isPlaying
                val pos = c.currentPosition
                c.setMediaItems(items, cur, pos)
                c.prepare()
                if (wasPlaying) c.play()
                _queueSongs.value = newSongs
            } catch (_: Exception) { }
        }
    }

    /** Push a changed crossfade length into the engine. */
    fun refreshCrossfade() {
        try {
            EngineHolder.crossfade?.fadeSec = Graph.settings.getCrossfadeSec()
        } catch (_: Exception) { }
    }

    // --- Sleep timer (smart fade) ---
    private var sleepJob: kotlinx.coroutines.Job? = null
    private val _sleepEndsAt = MutableStateFlow<Long?>(null)
    val sleepEndsAt: StateFlow<Long?> = _sleepEndsAt.asStateFlow()
    private val _sleepMinutes = MutableStateFlow<Int?>(null)
    val sleepMinutes: StateFlow<Int?> = _sleepMinutes.asStateFlow()

    /**
     * Start a sleep timer; playback pauses after [minutes].
     * The volume fades out over the last [fadeMinutes], and when
     * [endOfTrack] is true the timer instead ends at the end of the
     * current track. Pass 0/null to cancel.
     */
    fun setSleepTimer(minutes: Int?, fadeMinutes: Int = 5, endOfTrack: Boolean = false) {
        sleepJob?.cancel()
        sleepJob = null
        try { EngineHolder.crossfade?.cancelSleep() } catch (_: Exception) { }
        if (minutes == null || minutes <= 0) {
            _sleepEndsAt.value = null
            _sleepMinutes.value = null
            return
        }
        val totalMs: Long
        val fadeMs: Long
        if (endOfTrack) {
            val c = _controller.value
            val dur = try { c?.duration ?: -1L } catch (_: Exception) { -1L }
            val pos = try { c?.currentPosition ?: 0L } catch (_: Exception) { 0L }
            totalMs = if (dur > 0) (dur - pos).coerceAtLeast(30_000L) else minutes * 60_000L
            fadeMs = minOf(fadeMinutes * 60_000L, totalMs / 2)
        } else {
            totalMs = minutes * 60_000L
            fadeMs = minOf(fadeMinutes * 60_000L, totalMs / 2)
        }
        _sleepEndsAt.value = System.currentTimeMillis() + totalMs
        _sleepMinutes.value = minutes
        val engine = EngineHolder.crossfade
        if (engine != null) {
            engine.startSleepFade(totalMs, fadeMs)
            // Watchdog: clear the UI state once the fade is done.
            sleepJob = scope.launch {
                kotlinx.coroutines.delay(totalMs + 5_000L)
                _sleepEndsAt.value = null
                _sleepMinutes.value = null
            }
        } else {
            // Legacy fallback if the engine isn't up (service not bound).
            sleepJob = scope.launch {
                kotlinx.coroutines.delay(totalMs)
                try {
                    _controller.value?.pause()
                } catch (_: Exception) {}
                _sleepEndsAt.value = null
                _sleepMinutes.value = null
            }
        }
    }

    // --- Built-in 5-band equalizer ---

    /** Re-apply the saved sound state to the live controllers (no-op when unavailable). */
    fun applyEqSettings() {
        try {
            val s = Graph.settings
            EngineHolder.eq?.let { eq ->
                eq.setEnabled(s.isEqEnabled())
                eq.setPreamp(s.getEqPreamp())
                eq.setBands(s.getEqBands())
                eq.apply()
            }
            EngineHolder.boost?.setGain(s.getVolumeBoost())
            EngineHolder.boost?.apply()
            EngineHolder.compressor?.setPreset(s.getCompressor())
            EngineHolder.compressor?.apply()
            EngineHolder.exoPlayer?.setSkipSilenceEnabled(s.isSkipSilence())
        } catch (_: Exception) {
        }
    }

    fun setEqEnabled(on: Boolean) {
        try { Graph.settings.setEqEnabled(on) } catch (_: Exception) {}
        try {
            EngineHolder.eq?.setEnabled(on)
            EngineHolder.eq?.apply()
        } catch (_: Exception) {
        }
    }

    fun setEqPreset(index: Int) {
        try { Graph.settings.setEqPreset(index) } catch (_: Exception) {}
        try {
            EngineHolder.eq?.setBands(Graph.settings.getEqBands())
        } catch (_: Exception) {
        }
    }

    fun setEqBands(bands: List<Int>) {
        try { Graph.settings.setEqBands(bands) } catch (_: Exception) {}
        try {
            EngineHolder.eq?.setBands(Graph.settings.getEqBands())
        } catch (_: Exception) {
        }
    }

    fun setEqPreamp(mb: Int) {
        try { Graph.settings.setEqPreamp(mb) } catch (_: Exception) {}
        try {
            EngineHolder.eq?.setPreamp(Graph.settings.getEqPreamp())
        } catch (_: Exception) {
        }
    }

    /** Volume boost in millibels, 0..1000 (+10 dB max). */
    fun setVolumeBoost(mb: Int) {
        try { Graph.settings.setVolumeBoost(mb) } catch (_: Exception) {}
        try {
            EngineHolder.boost?.setGain(Graph.settings.getVolumeBoost())
        } catch (_: Exception) {
        }
    }

    fun setSkipSilence(on: Boolean) {
        try { Graph.settings.setSkipSilence(on) } catch (_: Exception) {}
        try {
            EngineHolder.exoPlayer?.setSkipSilenceEnabled(on)
        } catch (_: Exception) {
        }
    }

    /** Compressor preset: 0 off, 1 gentle, 2 firm. */
    fun setCompressor(preset: Int) {
        try { Graph.settings.setCompressor(preset) } catch (_: Exception) {}
        try {
            EngineHolder.compressor?.setPreset(Graph.settings.getCompressor())
        } catch (_: Exception) {
        }
    }

    /** Re-apply every sound setting to the live controllers (no-op when unavailable). */
    fun applySoundSettings() {
        applyEqSettings()
        try {
            val s = Graph.settings
            EngineHolder.eq?.setPreamp(s.getEqPreamp())
            EngineHolder.boost?.setGain(s.getVolumeBoost())
            EngineHolder.compressor?.setPreset(s.getCompressor())
            EngineHolder.exoPlayer?.setSkipSilenceEnabled(s.isSkipSilence())
        } catch (_: Exception) {
        }
    }
}
