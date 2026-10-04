package com.opus.music.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.opus.music.network.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Holder so PlayerManager can reach the engine created in PlayerService. */
object EngineHolder {
    @Volatile var crossfade: CrossfadeEngine? = null
    @Volatile var eq: EqualizerEngine.Controller? = null
    @Volatile var boost: VolumeBoostController? = null
    @Volatile var compressor: CompressorController? = null
    @Volatile var exoPlayer: ExoPlayer? = null
}

/**
 * True overlap crossfade for ExoPlayer.
 *
 * The main player keeps the full native queue (gapless, shuffle, repeat all
 * keep working exactly as before). A second "helper" player — with audio
 * focus handling disabled so it never steals focus from the main player —
 * starts the next track early and the two volumes are ramped against each
 * other. When the main player naturally advances, it is seeked to the
 * helper's position for a seamless handoff.
 *
 * Also owns the smart sleep fade (volume ramp before pausing).
 */
class CrossfadeEngine(
    private val appContext: Context,
    private val main: ExoPlayer,
    private val songLookup: (String) -> Song?,
    private val mediaItemFor: (Song) -> MediaItem
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Crossfade length in seconds; 0 = off (pure native playback). */
    @Volatile var fadeSec: Int = 0

    private var helper: ExoPlayer? = null
    private var monitorJob: Job? = null
    private var xfadeJob: Job? = null
    private var sleepJob: Job? = null

    @Volatile private var xfading = false
    @Volatile private var xfadeGainMain = 1f
    @Volatile private var helperT = 0f
    @Volatile private var sleepGain = 1f

    init {
        main.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                if (playing) startMonitor() else { stopMonitor(); abortXfade() }
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (xfading && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    handoff()
                } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    // A manual seek (not from quickSwitch) invalidates the overlap.
                    if (xfading) abortXfade()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (!xfading) return
                when (reason) {
                    // Auto-advance (incl. repeat-one restart): complete the overlap.
                    Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> handoff()
                    // A manual seek (not from quickSwitch) invalidates the overlap.
                    Player.DISCONTINUITY_REASON_SEEK -> abortXfade()
                }
            }
        })
    }

    // ---------------- helper player ----------------

    private fun ensureHelper(): ExoPlayer {
        helper?.let { return it }
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(appContext)
            .setAudioAttributes(attrs, /* handleAudioFocus= */ false)
            .setHandleAudioBecomingNoisy(false)
            .build()
            .also { helper = it }
    }

    private fun applyVolumes() {
        try { main.volume = (xfadeGainMain * sleepGain).coerceIn(0f, 1f) } catch (_: Exception) { }
        try { helper?.volume = (helperT * sleepGain).coerceIn(0f, 1f) } catch (_: Exception) { }
    }

    // ---------------- monitor & trigger ----------------

    private fun startMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                delay(250)
                try { checkTrigger() } catch (_: Exception) { }
            }
        }
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun checkTrigger() {
        val fadeMs = fadeSec * 1000L
        if (fadeMs <= 0 || xfading || !main.isPlaying) return
        val dur = main.duration
        if (dur <= 0 || dur == C.TIME_UNSET) return
        val remaining = dur - main.currentPosition
        if (remaining <= 0) return
        // Never consume more than two thirds of a (short) track.
        val useFade = minOf(fadeMs, dur * 2 / 3)
        if (useFade < 1000) return
        if (remaining > useFade || remaining < 1200) return

        var nextIdx = main.nextMediaItemIndex
        if (nextIdx == C.INDEX_UNSET) {
            if (main.repeatMode == Player.REPEAT_MODE_ONE) {
                nextIdx = main.currentMediaItemIndex
            } else return
        }
        val nextId = try { main.getMediaItemAt(nextIdx).mediaId } catch (_: Exception) { return }
        val song = songLookup(nextId) ?: return
        beginXfade(song, useFade)
    }

    private fun beginXfade(song: Song, fadeMs: Long) {
        abortXfade(silent = true)
        xfading = true
        val h = ensureHelper()
        try {
            h.setMediaItem(mediaItemFor(song))
            h.prepare()
            h.volume = 0f
            h.play()
        } catch (_: Exception) {
            xfading = false
            return
        }
        xfadeJob = scope.launch {
            // Wait briefly for the helper to actually start, so the overlap
            // lines up; give up if it can't buffer in time.
            if (!waitForReady(h, maxWaitMs = 2000)) {
                abortXfade()
                return@launch
            }
            val steps = 24
            val stepMs = (fadeMs / steps).coerceAtLeast(20)
            repeat(steps) { i ->
                if (!xfading || !isActive) return@launch
                val t = (i + 1) / steps.toFloat()
                xfadeGainMain = 1f - t
                helperT = t
                applyVolumes()
                delay(stepMs)
            }
            // Ramp done; handoff() completes the switch when the main
            // player auto-advances at the track end.
        }
    }

    /** Wait up to [maxWaitMs] for the helper to reach READY; false on timeout. */
    private suspend fun waitForReady(h: ExoPlayer, maxWaitMs: Long): Boolean {
        var waited = 0L
        while (waited < maxWaitMs) {
            if (!xfading) return false
            val st = try { h.playbackState } catch (_: Exception) { Player.STATE_IDLE }
            if (st == Player.STATE_READY) return true
            delay(100)
            waited += 100
        }
        return try { h.playbackState == Player.STATE_READY } catch (_: Exception) { false }
    }

    /** Main player auto-advanced while overlapping: sync it to the helper. */
    private fun handoff() {
        if (!xfading) return
        val h = helper
        try {
            val pos = (h?.currentPosition ?: 0L).coerceAtLeast(0L)
            main.seekTo(main.currentMediaItemIndex, pos)
        } catch (_: Exception) { }
        xfadeGainMain = 1f
        helperT = 0f
        xfading = false
        xfadeJob?.cancel()
        xfadeJob = null
        applyVolumes()
        try { h?.pause() } catch (_: Exception) { }
    }

    /** Cancel any in-flight overlap and restore full volume. */
    fun abortXfade(silent: Boolean = false) {
        if (!xfading && xfadeJob == null && !silent) return
        xfading = false
        xfadeJob?.cancel()
        xfadeJob = null
        xfadeGainMain = 1f
        helperT = 0f
        applyVolumes()
        try { helper?.pause() } catch (_: Exception) { }
    }

    // ---------------- manual transport (via PlayerManager) ----------------

    /**
     * Skip to [index] with a short crossfade when enabled.
     * Returns true if the engine handled it.
     */
    fun skipTo(index: Int): Boolean {
        abortXfade()
        if (fadeSec <= 0) return false
        val songId = try { main.getMediaItemAt(index).mediaId } catch (_: Exception) { return false }
        val song = songLookup(songId) ?: return false
        return quickSwitch(index, song)
    }

    /** Next button with a short crossfade when enabled. Returns true if handled. */
    fun next(): Boolean {
        abortXfade()
        if (fadeSec <= 0) return false
        var idx = main.nextMediaItemIndex
        if (idx == C.INDEX_UNSET) {
            if (main.repeatMode == Player.REPEAT_MODE_ONE) idx = main.currentMediaItemIndex
            else return false
        }
        val songId = try { main.getMediaItemAt(idx).mediaId } catch (_: Exception) { return false }
        val song = songLookup(songId) ?: return false
        return quickSwitch(idx, song)
    }

    /** Previous button: restart if >3s in, else short crossfade. Returns true if handled. */
    fun previous(): Boolean {
        abortXfade()
        return try {
            if (main.currentPosition > 3000) {
                main.seekTo(0)
                true
            } else if (fadeSec > 0) {
                val idx = main.previousMediaItemIndex
                if (idx == C.INDEX_UNSET) return false
                val songId = main.getMediaItemAt(idx).mediaId
                val song = songLookup(songId) ?: return false
                quickSwitch(idx, song)
            } else false
        } catch (_: Exception) { false }
    }

    private fun quickSwitch(index: Int, song: Song): Boolean {
        return try {
            val h = ensureHelper()
            h.setMediaItem(mediaItemFor(song))
            h.prepare()
            h.volume = 0f
            h.play()
            xfading = true
            xfadeJob = scope.launch {
                if (!waitForReady(h, maxWaitMs = 1500)) {
                    // Helper couldn't buffer: plain native skip instead of
                    // leaving the user staring at the old track.
                    xfading = false
                    xfadeGainMain = 1f
                    helperT = 0f
                    applyVolumes()
                    try { h.pause() } catch (_: Exception) { }
                    try {
                        main.seekTo(index, 0L)
                        if (!main.isPlaying) main.play()
                    } catch (_: Exception) { }
                    return@launch
                }
                val steps = 10
                repeat(steps) { i ->
                    if (!xfading || !isActive) return@launch
                    val t = (i + 1) / steps.toFloat()
                    xfadeGainMain = 1f - t
                    helperT = t
                    applyVolumes()
                    delay(40)
                }
                try {
                    val pos = h.currentPosition.coerceAtLeast(0L)
                    main.seekTo(index, pos)
                    if (!main.isPlaying) main.play()
                } catch (_: Exception) { }
                xfadeGainMain = 1f
                helperT = 0f
                xfading = false
                applyVolumes()
                try { h.pause() } catch (_: Exception) { }
            }
            true
        } catch (_: Exception) {
            xfading = false
            false
        }
    }

    // ---------------- smart sleep fade ----------------

    /** Fade volume to silence over [fadeMs], then pause. Total wall time [totalMs]. */
    fun startSleepFade(totalMs: Long, fadeMs: Long) {
        cancelSleep()
        sleepJob = scope.launch {
            val waitMs = (totalMs - fadeMs).coerceAtLeast(0)
            if (waitMs > 0) delay(waitMs)
            val steps = 24
            val stepMs = (fadeMs / steps).coerceAtLeast(25)
            repeat(steps) { i ->
                if (!isActive) return@launch
                sleepGain = 1f - (i + 1) / steps.toFloat()
                applyVolumes()
                delay(stepMs)
            }
            sleepGain = 0f
            applyVolumes()
            try { main.pause() } catch (_: Exception) { }
            try { helper?.pause() } catch (_: Exception) { }
            abortXfade(silent = true)
            sleepGain = 1f
            applyVolumes()
        }
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        if (sleepGain != 1f) {
            sleepGain = 1f
            applyVolumes()
        }
    }

    fun release() {
        stopMonitor()
        cancelSleep()
        abortXfade(silent = true)
        try { helper?.release() } catch (_: Exception) { }
        helper = null
        scope.cancel()
    }
}
