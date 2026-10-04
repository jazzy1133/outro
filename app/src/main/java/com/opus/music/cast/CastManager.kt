package com.opus.music.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.media3.common.Player
import com.opus.music.Session
import com.opus.music.cast.raop.RaopTransport
import com.opus.music.network.Song
import com.opus.music.player.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Speaker families Outro can cast to. */
enum class SpeakerKind(val label: String) {
    SONOS("Sonos"),
    CHROMECAST("Chromecast"),
    AIRPLAY("AirPlay"),
}

/** A discovered speaker. */
sealed interface SpeakerDevice {
    val id: String
    val name: String
    val kind: SpeakerKind
}

data class SonosSpeaker(
    override val id: String,
    override val name: String,
    val avTransportUrl: String,
) : SpeakerDevice {
    override val kind: SpeakerKind = SpeakerKind.SONOS
}

/** A discovered AirPlay/RAOP speaker (HomePod, Apple TV, AirPort Express …). */
data class AirPlaySpeaker(
    override val id: String,
    override val name: String,
    val host: String,
    val port: Int,
    /**
     * Raw `et` TXT-record value (e.g. "0,3,5"): the encryption types the
     * speaker advertises — 0 = clear, 1 = classic RSA key exchange
     * (a=rsaaeskey), 3/5 = FairPlay (needs Apple's keys; unsupported).
     * The ANNOUNCE must offer one of these, or the speaker rejects it.
     */
    val et: String? = null,
) : SpeakerDevice {
    override val kind: SpeakerKind = SpeakerKind.AIRPLAY
}

/** Transport that plays a stream URL on a speaker.
 *
 * URL-pull speakers (Sonos, Chromecast) fetch the URL themselves; the
 * phone keeps the queue and sends transport commands. Push transports
 * (AirPlay/RAOP) pull the URL on the phone, decode it and stream audio
 * to the speaker instead. Both shapes fit this interface.
 */
interface UrlSpeakerTransport {
    val kind: SpeakerKind
    fun discover(timeoutMs: Int): List<SpeakerDevice>
    fun play(device: SpeakerDevice, url: String, title: String, artist: String)
    fun pause(device: SpeakerDevice)
    fun stop(device: SpeakerDevice)

    /**
     * Push transports (AirPlay) invoke [listener] when a track ends
     * NATURALLY (stream exhausted), never on a user stop/pause.
     * URL-pull transports ignore this — [CastManager] polls them via
     * [CastStateProbe] instead. Pass null to disarm.
     */
    fun setOnTrackEndedListener(listener: (() -> Unit)?) {}

    /**
     * Optional: a live session died unexpectedly (receiver closed the
     * connection, network loss…). [CastManager] clears the cast state
     * when this fires — without it the UI kept claiming "casting" to a
     * dead session and every control (seek included) was swallowed.
     */
    fun setOnSessionFailedListener(listener: ((String) -> Unit)?) {}
}

/** Optional: resume a paused speaker in place (no track restart). */
interface ResumableCastTransport {
    fun resume(device: SpeakerDevice)
}

/** Optional: seek within the track playing on the speaker. */
interface SeekableCastTransport {
    fun seek(device: SpeakerDevice, positionMs: Long)
}

/** Coarse playback state of a URL-pull speaker, for track-end polling. */
enum class CastProbeState { PLAYING, PAUSED, ENDED, TRANSITIONING, UNKNOWN }

/** Optional: report the speaker's playback state (URL-pull transports). */
interface CastStateProbe {
    fun probe(device: SpeakerDevice): CastProbeState
}

private class SonosTransport : UrlSpeakerTransport, ResumableCastTransport,
    SeekableCastTransport, CastStateProbe {
    override val kind = SpeakerKind.SONOS
    override fun discover(timeoutMs: Int): List<SpeakerDevice> =
        SonosDlna.discover(timeoutMs).map {
            SonosSpeaker(it.udn, it.name, it.avTransportUrl)
        }

    private fun sonosOf(device: SpeakerDevice): SonosDlna.SonosDevice {
        val s = device as? SonosSpeaker
            ?: throw IllegalArgumentException("not a Sonos device")
        return SonosDlna.SonosDevice(s.id, s.name, s.avTransportUrl)
    }

    override fun play(device: SpeakerDevice, url: String, title: String, artist: String) =
        SonosDlna.play(sonosOf(device), url, title, artist)

    override fun pause(device: SpeakerDevice) =
        SonosDlna.pause(sonosOf(device))

    override fun stop(device: SpeakerDevice) =
        SonosDlna.stop(sonosOf(device))

    override fun resume(device: SpeakerDevice) =
        SonosDlna.resume(sonosOf(device))

    override fun seek(device: SpeakerDevice, positionMs: Long) =
        SonosDlna.seek(sonosOf(device), positionMs)

    override fun probe(device: SpeakerDevice): CastProbeState =
        when (SonosDlna.transportState(sonosOf(device))) {
            "PLAYING" -> CastProbeState.PLAYING
            "PAUSED_PLAYBACK", "PAUSED" -> CastProbeState.PAUSED
            "STOPPED", "NO_MEDIA_PRESENT" -> CastProbeState.ENDED
            "TRANSITIONING" -> CastProbeState.TRANSITIONING
            else -> CastProbeState.UNKNOWN
        }
}

/** Cast state shared with the UI. */
sealed interface CastState {
    data object Idle : CastState
    data object Scanning : CastState
    data class Casting(val device: SpeakerDevice, val songTitle: String) : CastState
    data class Error(val message: String) : CastState
}

/** What the speaker is playing right now (drives the player UI while casting). */
data class CastPlayback(
    val device: SpeakerDevice,
    val song: Song,
    val index: Int,
    val isPlaying: Boolean,
)

/**
 * Routes playback to a network speaker. While casting, this object owns
 * the queue and the playing/paused state: PlayerManager forwards every
 * transport command here, the local player stays paused, and track-end
 * on the speaker advances the cast queue exactly like local playback
 * would advance its own.
 */
object CastManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val transports = mutableListOf<UrlSpeakerTransport>(SonosTransport())

    /** Registered by later phases (Chromecast, AirPlay URL transports). */
    fun registerTransport(t: UrlSpeakerTransport) {
        if (transports.none { it.kind == t.kind }) transports.add(t)
    }

    private val _devices = MutableStateFlow<List<SpeakerDevice>>(emptyList())
    val devices: StateFlow<List<SpeakerDevice>> = _devices.asStateFlow()

    private val _state = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> = _state.asStateFlow()

    private val _playback = MutableStateFlow<CastPlayback?>(null)
    val playback: StateFlow<CastPlayback?> = _playback.asStateFlow()

    val isCasting: Boolean get() = _state.value is CastState.Casting
    val castingDevice: SpeakerDevice?
        get() = (_state.value as? CastState.Casting)?.device

    // The queue being cast (mirrors PlayerManager's queue at cast start).
    private var castQueue: List<Song> = emptyList()
    private var castIndex: Int = -1

    /** Bumped on every cast start/stop so stale callbacks and monitors die. */
    @Volatile private var generation = 0
    private var monitorJob: Job? = null
    @Volatile private var monitorToken = 0

    // Approximate cast position (no transport reports it continuously):
    // a base offset plus wall-clock elapsed while playing.
    @Volatile private var posBaseMs = 0L
    @Volatile private var posStampMs = 0L

    private var transportsReady = false
    private var appContext: Context? = null

    /** Lazily add context-dependent transports (Chromecast needs the Cast SDK). */
    private fun ensureTransports(context: Context) {
        appContext = context.applicationContext
        if (transportsReady) return
        transportsReady = true
        // Remote-volume session follows the cast state (lock-screen keys).
        CastVolumeKeys.init(context.applicationContext)
        scope.launch {
            _state.collect { CastVolumeKeys.setCasting(it is CastState.Casting) }
        }
        try {
            registerTransport(ChromecastTransport(context.applicationContext))
        } catch (t: Throwable) {
            // Cast SDK unavailable (no Play services, missing classes, …):
            // Chromecast simply won't be listed.
        }
        try {
            registerTransport(Ap2Transport(context.applicationContext))
        } catch (t: Throwable) {
            // NSD unavailable: AirPlay 2 simply won't be listed.
        }
        try {
            registerTransport(RaopTransport(context.applicationContext))
        } catch (t: Throwable) {
            // Skipped when Ap2Transport already claimed AIRPLAY (it falls
            // back to RAOP internally for AP1-only receivers).
        }
    }

    private fun transportFor(kind: SpeakerKind): UrlSpeakerTransport? =
        transports.firstOrNull { it.kind == kind }

    /** Scan the LAN for speakers. Never disturbs an active cast session. */
    fun scan(context: Context) {
        if (_state.value is CastState.Scanning) return
        ensureTransports(context)
        val casting = _state.value as? CastState.Casting
        if (casting == null) _state.value = CastState.Scanning
        scope.launch {
            val lock = try {
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                wm.createMulticastLock("outro-ssdp").apply {
                    setReferenceCounted(true)
                    acquire()
                }
            } catch (_: Exception) { null }
            try {
                val all = mutableListOf<SpeakerDevice>()
                for (t in transports) {
                    try {
                        all += t.discover(3500)
                    } catch (th: Throwable) {
                        // One broken transport must not kill the whole scan.
                    }
                }
                _devices.value = all.distinctBy { it.kind to it.id }
                // Only a scan that owns the state may clear it; a scan run
                // during casting must not clobber the Casting state (that
                // silently un-routed every player control to the phone).
                if (casting == null && _state.value is CastState.Scanning) {
                    _state.value = CastState.Idle
                }
            } catch (e: Exception) {
                if (casting == null) {
                    _state.value = CastState.Error("Scan failed: ${e.message}")
                }
            } finally {
                try { lock?.release() } catch (_: Exception) {}
            }
        }
    }

    fun streamUrlFor(song: Song): String? {
        // Internet-radio items carry their direct URL in the id (see
        // PlayerManager.playUrl); every transport can fetch it as-is.
        if (song.id.startsWith("radio:")) return song.id.removePrefix("radio:")
        val client = Session.client ?: return null
        return try {
            client.streamUrl(song.id, 0)
        } catch (_: Exception) { null }
    }

    /**
     * Start casting [queue] at [index] to [device]. The phone goes
     * silent first; the speaker takes over the queue from here.
     */
    fun castTo(device: SpeakerDevice, queue: List<Song>, index: Int) {
        appContext?.let { ensureTransports(it) }
        val transport = transportFor(device.kind) ?: return
        val song = queue.getOrNull(index) ?: return
        val url = streamUrlFor(song) ?: return
        // Silence the phone BEFORE the speaker connects (the Cast SDK
        // guidance is the same: local playback stops when casting
        // starts) — otherwise both play during the connect window,
        // and a failed connect leaves the song playing on the phone
        // while the UI claims to cast.
        PlayerManager.pauseLocal()
        // Switching speakers mid-cast: stop the old session first.
        (_state.value as? CastState.Casting)?.let { old ->
            if (old.device.id != device.id) {
                try { transportFor(old.device.kind)?.stop(old.device) } catch (_: Exception) {}
            }
        }
        val gen = ++generation
        castQueue = queue
        castIndex = index
        _state.value = CastState.Casting(device, song.title)
        _playback.value = CastPlayback(device, song, index, isPlaying = true)
        resetPositionTicker()
        armTrackEnd(transport, device, gen)
        scope.launch {
            try {
                transport.play(device, url, song.title, song.artist ?: "")
                applyCastVolume()
            } catch (e: Exception) {
                if (gen == generation) {
                    _state.value = CastState.Error("Couldn't cast: ${e.message}")
                    _playback.value = null
                }
            }
        }
    }

    /** Push the current cast-queue song (after next/previous/track-end). */
    private fun pushCurrent() {
        val st = _state.value as? CastState.Casting ?: return
        val transport = transportFor(st.device.kind) ?: return
        val song = castQueue.getOrNull(castIndex) ?: return
        val url = streamUrlFor(song) ?: return
        val gen = generation
        _playback.value = CastPlayback(st.device, song, castIndex, isPlaying = true)
        _state.value = CastState.Casting(st.device, song.title)
        resetPositionTicker()
        armTrackEnd(transport, st.device, gen)
        scope.launch {
            try {
                transport.play(st.device, url, song.title, song.artist ?: "")
                applyCastVolume()
            } catch (e: Exception) {
                if (gen == generation) {
                    _state.value = CastState.Error("Speaker error: ${e.message}")
                    _playback.value = null
                }
            }
        }
    }

    /**
     * Keep the cast queue in sync with PlayerManager's queue after an
     * edit (add / remove / reorder…). The currently-cast song is
     * re-located by id so the speaker never loses its place.
     */
    fun updateQueue(songs: List<Song>) {
        if (!isCasting) return
        val currentId = _playback.value?.song?.id
        castQueue = songs
        val idx = songs.indexOfFirst { it.id == currentId }
        castIndex = if (idx >= 0) idx
        else castIndex.coerceIn(0, maxOf(0, songs.size - 1))
        _playback.value?.let { _playback.value = it.copy(index = castIndex) }
    }

    // ------------------------------------------------------------------
    // Transport controls (routed from PlayerManager while casting)
    // ------------------------------------------------------------------

    fun togglePlayPause() {
        val pb = _playback.value ?: return
        if (pb.isPlaying) pause() else resume()
    }

    fun pause() {
        val pb = _playback.value ?: return
        val transport = transportFor(pb.device.kind) ?: return
        freezePositionTicker()
        _playback.value = pb.copy(isPlaying = false)
        scope.launch {
            try { transport.pause(pb.device) } catch (_: Exception) {}
        }
    }

    fun resume() {
        val pb = _playback.value ?: return
        val transport = transportFor(pb.device.kind) ?: return
        resumePositionTicker()
        _playback.value = pb.copy(isPlaying = true)
        scope.launch {
            try {
                if (transport is ResumableCastTransport) {
                    transport.resume(pb.device)
                } else {
                    // Push transports restart the session (and the track):
                    // that IS their resume (AP2 v1 semantics). The track
                    // restarts at 0, so the position ticker restarts too.
                    val url = streamUrlFor(pb.song) ?: return@launch
                    transport.play(pb.device, url, pb.song.title, pb.song.artist ?: "")
                    resetPositionTicker()
                    applyCastVolume()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun next() {
        if (castQueue.isEmpty()) return
        castIndex = (castIndex + 1) % castQueue.size
        pushCurrent()
    }

    fun previous() {
        if (castQueue.isEmpty()) return
        castIndex = (castIndex - 1 + castQueue.size) % castQueue.size
        pushCurrent()
    }

    fun skipTo(index: Int) {
        if (index !in castQueue.indices) return
        castIndex = index
        pushCurrent()
    }

    /** Seek the speaker (transports without seek support ignore this). */
    fun seekTo(positionMs: Long) {
        val pb = _playback.value ?: return
        val transport = transportFor(pb.device.kind) as? SeekableCastTransport ?: return
        seekPositionTicker(positionMs)
        scope.launch {
            try { transport.seek(pb.device, positionMs) } catch (_: Exception) {}
        }
    }

    /** Approximate position of the cast playback, for seek ± and the UI. */
    fun positionEstimateMs(): Long {
        val pb = _playback.value ?: return 0L
        return if (pb.isPlaying) {
            posBaseMs + (SystemClock.elapsedRealtime() - posStampMs)
        } else {
            posBaseMs
        }
    }

    /** Stop casting; the speaker stops and local playback stays paused. */
    fun stop() {
        val st = _state.value as? CastState.Casting ?: return
        generation++
        monitorJob?.cancel()
        val transport = transportFor(st.device.kind)
        scope.launch {
            try { transport?.setOnTrackEndedListener(null) } catch (_: Exception) {}
            try { transport?.setOnSessionFailedListener(null) } catch (_: Exception) {}
            try { transport?.stop(st.device) } catch (_: Exception) {}
            _state.value = CastState.Idle
        }
        _playback.value = null
        castQueue = emptyList()
        castIndex = -1
    }

    // ------------------------------------------------------------------
    // Track-end detection: push transports call back; URL-pull
    // transports are polled (the DLNA/Cast control-point pattern).
    // ------------------------------------------------------------------

    private fun armTrackEnd(transport: UrlSpeakerTransport, device: SpeakerDevice, gen: Int) {
        try {
            transport.setOnTrackEndedListener { handleTrackEnded(gen) }
        } catch (_: Exception) {}
        try {
            transport.setOnSessionFailedListener { msg -> handleSessionFailed(gen, msg) }
        } catch (_: Exception) {}
        if (transport is CastStateProbe) {
            startProbeMonitor(transport, device, gen)
        } else {
            monitorJob?.cancel()
        }
    }

    private fun startProbeMonitor(probe: CastStateProbe, device: SpeakerDevice, gen: Int) {
        monitorJob?.cancel()
        val token = ++monitorToken
        monitorJob = scope.launch {
            var sawPlaying = false
            var pushedAt = SystemClock.elapsedRealtime()
            var retried = false
            while (gen == generation && token == monitorToken) {
                delay(2000)
                val pb = _playback.value ?: break
                if (pb.device.id != device.id) break
                if (!pb.isPlaying) {
                    // Paused by the user: a STOPPED report here is the
                    // pause, not a track end. Re-arm the watchdogs.
                    sawPlaying = false
                    pushedAt = SystemClock.elapsedRealtime()
                    retried = false
                    continue
                }
                val st = try {
                    probe.probe(device)
                } catch (_: Exception) {
                    CastProbeState.UNKNOWN
                }
                when (st) {
                    CastProbeState.PLAYING -> {
                        sawPlaying = true
                        retried = false
                    }
                    CastProbeState.ENDED -> {
                        if (sawPlaying) {
                            handleTrackEnded(gen)
                            break
                        }
                        // Never started: re-push once (a dropped
                        // SetAVTransportURI/LOAD otherwise stalls the
                        // queue forever), then give the track up.
                        val idleFor = SystemClock.elapsedRealtime() - pushedAt
                        if (!retried && idleFor > 20_000) {
                            retried = true
                            pushedAt = SystemClock.elapsedRealtime()
                            retryPush(device, gen)
                        } else if (retried && idleFor > 25_000) {
                            handleTrackEnded(gen)
                            break
                        }
                    }
                    CastProbeState.TRANSITIONING -> {
                        if (!sawPlaying &&
                            SystemClock.elapsedRealtime() - pushedAt > 45_000
                        ) {
                            handleTrackEnded(gen)
                            break
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /** Re-issue the current track once after a stalled start (no monitor reset). */
    private fun retryPush(device: SpeakerDevice, gen: Int) {
        val transport = transportFor(device.kind) ?: return
        val song = castQueue.getOrNull(castIndex) ?: return
        val url = streamUrlFor(song) ?: return
        scope.launch {
            try {
                if (gen == generation) {
                    transport.play(device, url, song.title, song.artist ?: "")
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * The speaker session died mid-playback: clear the cast state so
     * the player UI and controls return to the phone instead of
     * routing into a dead session forever.
     */
    private fun handleSessionFailed(gen: Int, message: String) {
        if (gen != generation) return
        if (_playback.value == null) return
        generation++
        monitorJob?.cancel()
        _playback.value = null
        castQueue = emptyList()
        castIndex = -1
        _state.value = CastState.Error(message)
    }

    /** The speaker finished the current track: advance like local playback. */
    private fun handleTrackEnded(gen: Int) {
        if (gen != generation) return
        val pb = _playback.value ?: return
        if (!pb.isPlaying) return
        val repeat = try {
            PlayerManager.repeatMode()
        } catch (_: Exception) {
            Player.REPEAT_MODE_OFF
        }
        when (castEndAction(castIndex, castQueue.size, repeat)) {
            CastEndAction.REPLAY -> pushCurrent()
            CastEndAction.NEXT -> {
                castIndex++
                pushCurrent()
            }
            CastEndAction.WRAP -> {
                castIndex = 0
                pushCurrent()
            }
            CastEndAction.FINISH -> finishCastQueue()
        }
    }

    /** Queue exhausted: the speaker already stopped; wind the cast down. */
    private fun finishCastQueue() {
        val st = _state.value as? CastState.Casting ?: return
        generation++
        monitorJob?.cancel()
        val transport = transportFor(st.device.kind)
        scope.launch {
            try { transport?.setOnTrackEndedListener(null) } catch (_: Exception) {}
            try { transport?.setOnSessionFailedListener(null) } catch (_: Exception) {}
        }
        _playback.value = null
        castQueue = emptyList()
        castIndex = -1
        _state.value = CastState.Idle
    }

    // ------------------------------------------------------------------
    // Position ticker
    // ------------------------------------------------------------------

    private fun resetPositionTicker() {
        posBaseMs = 0L
        posStampMs = SystemClock.elapsedRealtime()
    }

    private fun freezePositionTicker() {
        posBaseMs = positionEstimateMs()
        posStampMs = SystemClock.elapsedRealtime()
    }

    private fun resumePositionTicker() {
        posStampMs = SystemClock.elapsedRealtime()
    }

    private fun seekPositionTicker(ms: Long) {
        posBaseMs = ms.coerceAtLeast(0)
        posStampMs = SystemClock.elapsedRealtime()
    }

    // ------------------------------------------------------------------
    // Cast volume (drives the speaker, not the phone)
    // ------------------------------------------------------------------

    private val _castVolume = MutableStateFlow(100)
    val castVolume: StateFlow<Int> = _castVolume.asStateFlow()

    /** Set the casting speaker's volume (0..100). No-op when not casting. */
    fun setCastVolume(percent: Int) {
        val p = percent.coerceIn(0, 100)
        _castVolume.value = p
        CastVolumeKeys.updateVolume(p)
        val st = _state.value as? CastState.Casting ?: return
        val transport = transportFor(st.device.kind) ?: return
        scope.launch {
            try {
                when (transport) {
                    is Ap2Transport -> transport.setVolume(p)
                    is RaopTransport -> transport.setVolume(p)
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Nudge the casting speaker's volume by [delta] percent. */
    fun adjustCastVolume(delta: Int) {
        setCastVolume(_castVolume.value + delta)
    }

    /** Re-apply the stored cast volume after a (re)start of playback. */
    private fun applyCastVolume() {
        if (_castVolume.value != 100) setCastVolume(_castVolume.value)
    }
}
