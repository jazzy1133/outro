package com.opus.music.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Chromecast via the official Cast SDK: MediaRouter discovery, session
 * management, and Default Media Receiver LOAD of the Subsonic stream URL.
 *
 * Everything runs off the caller's thread except MediaRouter calls, which
 * are marshalled to the main looper. All entry points are defensive —
 * any Cast SDK failure surfaces as an exception for CastManager to report.
 */
class ChromecastTransport(appContext: Context) : UrlSpeakerTransport,
    ResumableCastTransport, SeekableCastTransport, CastStateProbe {

    override val kind: SpeakerKind = SpeakerKind.CHROMECAST

    private val context = appContext.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaRouter: MediaRouter = MediaRouter.getInstance(context)

    /** Lazily fetched; throws when Play services / Cast is unavailable. */
    private fun castContext(): CastContext = CastContext.getSharedInstance(context)

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else {
            val latch = CountDownLatch(1)
            mainHandler.post {
                try { action() } finally { latch.countDown() }
            }
            latch.await(5, TimeUnit.SECONDS)
        }
    }

    override fun discover(timeoutMs: Int): List<SpeakerDevice> {
        val selector = try {
            castContext().mergedSelector
                ?: throw IllegalStateException("Cast selector unavailable")
        } catch (t: Throwable) {
            throw IllegalStateException("Cast unavailable: ${t.message}")
        }
        val found = linkedMapOf<String, SpeakerDevice>()
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                addRoute(route)
            }

            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                addRoute(route)
            }

            private fun addRoute(route: MediaRouter.RouteInfo) {
                try {
                    if (!route.isEnabled) return
                    val device = route.extras?.let {
                        com.google.android.gms.cast.CastDevice.getFromBundle(it)
                    } ?: return
                    val id = device.deviceId ?: route.id ?: return
                    val name = route.name?.toString()?.takeIf { it.isNotBlank() }
                        ?: device.friendlyName ?: "Chromecast"
                    found.putIfAbsent(
                        id,
                        ChromecastSpeaker(id, name, route.id ?: id)
                    )
                } catch (_: Exception) {
                }
            }
        }
        runOnMain {
            mediaRouter.addCallback(
                selector, callback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
            )
        }
        try {
            Thread.sleep(timeoutMs.toLong())
        } catch (_: InterruptedException) {
        } finally {
            runOnMain { mediaRouter.removeCallback(callback) }
        }
        return found.values.toList()
    }

    /** Current CastSession, or null when not connected. */
    private fun currentSession(): CastSession? = try {
        castContext().sessionManager.currentCastSession
    } catch (_: Exception) {
        null
    }

    /**
     * Select the route for [device] and wait for the CastSession to start.
     * Returns the live session or throws on timeout.
     */
    private fun ensureSession(device: SpeakerDevice): CastSession {
        currentSession()?.let { return it }
        val speaker = device as? ChromecastSpeaker
            ?: throw IllegalArgumentException("not a Chromecast device")
        val sessionRef = AtomicReference<CastSession?>()
        val latch = CountDownLatch(1)
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                sessionRef.set(session)
                latch.countDown()
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                latch.countDown()
            }

            override fun onSessionEnded(session: CastSession, error: Int) {}
            override fun onSessionEnding(session: CastSession) {}
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                sessionRef.set(session)
                latch.countDown()
            }

            override fun onSessionResumeFailed(session: CastSession, error: Int) {
                latch.countDown()
            }

            override fun onSessionStarting(session: CastSession) {}
            override fun onSessionResuming(session: CastSession, sessionId: String) {}
            override fun onSessionSuspended(session: CastSession, reason: Int) {}
        }
        val sm = castContext().sessionManager
        runOnMain { sm.addSessionManagerListener(listener, CastSession::class.java) }
        try {
            var target: MediaRouter.RouteInfo? = null
            runOnMain {
                target = mediaRouter.routes.firstOrNull { it.id == speaker.routeId }
                target?.let { mediaRouter.selectRoute(it) }
            }
            val route = target
                ?: throw IllegalStateException("Chromecast route gone")
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw IllegalStateException("Timed out connecting to ${speaker.name}")
            }
            return sessionRef.get()
                ?: throw IllegalStateException("Couldn't start cast session")
        } finally {
            runOnMain {
                try {
                    sm.removeSessionManagerListener(listener, CastSession::class.java)
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun play(device: SpeakerDevice, url: String, title: String, artist: String) {
        val session = ensureSession(device)
        val client = session.remoteMediaClient
            ?: throw IllegalStateException("No remote media client")
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            if (artist.isNotBlank()) putString(MediaMetadata.KEY_ARTIST, artist)
        }
        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("audio/mpeg")
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()
        val latch = CountDownLatch(1)
        val err = AtomicReference<Exception?>()
        mainHandler.post {
            try {
                client.load(request).setResultCallback { result ->
                    try {
                        if (!result.status.isSuccess) {
                            err.set(IllegalStateException("Load failed: ${result.status.statusCode}"))
                        }
                    } catch (e: Exception) {
                        err.set(e)
                    } finally {
                        latch.countDown()
                    }
                }
            } catch (e: Exception) {
                err.set(e)
                latch.countDown()
            }
        }
        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timed out loading media")
        }
        err.get()?.let { throw it }
    }

    override fun pause(device: SpeakerDevice) {
        val client = currentSession()?.remoteMediaClient ?: return
        mainHandler.post {
            // A real pause — NOT togglePlayback: the old toggle made
            // CastManager.pause() resume a paused receiver (and vice
            // versa), so the player controls felt inverted/dead.
            try { client.pause() } catch (_: Exception) {}
        }
    }

    override fun resume(device: SpeakerDevice) {
        val client = currentSession()?.remoteMediaClient ?: return
        mainHandler.post {
            try { client.play() } catch (_: Exception) {}
        }
    }

    override fun seek(device: SpeakerDevice, positionMs: Long) {
        val client = currentSession()?.remoteMediaClient ?: return
        mainHandler.post {
            try { client.seek(positionMs.coerceAtLeast(0)) } catch (_: Exception) {}
        }
    }

    override fun probe(device: SpeakerDevice): CastProbeState {
        val client = currentSession()?.remoteMediaClient ?: return CastProbeState.UNKNOWN
        return try {
            when (client.playerState) {
                com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING ->
                    CastProbeState.PLAYING
                com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PAUSED ->
                    CastProbeState.PAUSED
                com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING,
                com.google.android.gms.cast.MediaStatus.PLAYER_STATE_LOADING ->
                    CastProbeState.TRANSITIONING
                com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE ->
                    if (client.idleReason ==
                        com.google.android.gms.cast.MediaStatus.IDLE_REASON_FINISHED
                    ) {
                        CastProbeState.ENDED
                    } else {
                        CastProbeState.UNKNOWN
                    }
                else -> CastProbeState.UNKNOWN
            }
        } catch (_: Exception) {
            CastProbeState.UNKNOWN
        }
    }

    override fun stop(device: SpeakerDevice) {
        try {
            runOnMain {
                try {
                    castContext().sessionManager.endCurrentSession(true)
                } catch (_: Exception) {
                }
                try {
                    mediaRouter.selectRoute(mediaRouter.defaultRoute)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }
}

/** A discovered Chromecast: [routeId] is the MediaRouter route to select. */
data class ChromecastSpeaker(
    override val id: String,
    override val name: String,
    val routeId: String,
) : SpeakerDevice {
    override val kind: SpeakerKind = SpeakerKind.CHROMECAST
}
