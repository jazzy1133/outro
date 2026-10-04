package com.opus.music.cast

import android.content.Context
import com.opus.music.cast.ap2.AlacFrame
import com.opus.music.cast.ap2.Ap2AudioSession
import com.opus.music.cast.ap2.Ap2Pairing
import com.opus.music.cast.raop.RaopLogger
import com.opus.music.cast.raop.RaopPcmPipeline
import com.opus.music.cast.raop.RaopTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * AirPlay 2 transport for HomePod-class receivers: transient HAP pairing,
 * encrypted RTSP control channel, and realtime encrypted-ALAC RTP fed by
 * [RaopPcmPipeline] — the sender proven on-device by the standalone
 * AirPlay2 Probe on 2026-10-03.
 *
 * Registered for [SpeakerKind.AIRPLAY] ahead of the classic RAOP transport.
 * Receivers that reject the AirPlay 2 handshake (e.g. AirPort Express)
 * fall back to [RaopTransport] automatically. Discovery is shared with
 * RAOP (NSD `_raop._tcp`).
 *
 * v1 semantics: pause/resume restart the AirPlay 2 session (and the track)
 * rather than resuming mid-stream; stop tears the session down.
 */
class Ap2Transport(private val appContext: Context) : UrlSpeakerTransport, SeekableCastTransport {
    override val kind: SpeakerKind = SpeakerKind.AIRPLAY

    private val raop = RaopTransport(appContext)
    private val lock = Any()
    private var session: Session? = null

    @Volatile
    private var trackEndedListener: (() -> Unit)? = null

    @Volatile
    private var sessionFailedListener: ((String) -> Unit)? = null

    override fun setOnTrackEndedListener(listener: (() -> Unit)?) {
        trackEndedListener = listener
        // When this transport has fallen back to RAOP, the end signal
        // comes from the RAOP session instead — forward the listener.
        raop.setOnTrackEndedListener(listener)
    }

    override fun setOnSessionFailedListener(listener: ((String) -> Unit)?) {
        sessionFailedListener = listener
    }

    /**
     * Seek within the live session: the decode pipeline jumps to
     * [positionMs] and the session keeps streaming — the RTP timeline
     * is continuous, only the audio content jumps (the receiver hears
     * the seek land like a local player). With a starved pipeline the
     * AP2 loop pads silence until new-position frames arrive.
     */
    override fun seek(device: SpeakerDevice, positionMs: Long) {
        val cur = synchronized(lock) { session } ?: return
        if (cur.fallback) {
            raop.seek(device, positionMs)
            return
        }
        try {
            cur.pipeline?.seekTo(positionMs)
            RaopLogger.log("[ap2cast] seek to ${positionMs}ms")
        } catch (_: Exception) {
        }
    }

    private class Session(
        val device: AirPlaySpeaker,
        val url: String,
    ) {
        @Volatile var pipeline: RaopPcmPipeline? = null
        @Volatile var established: Ap2Pairing.Established? = null
        @Volatile var audioSession: Ap2AudioSession? = null
        @Volatile var thread: Thread? = null
        @Volatile var fallback: Boolean = false
        /** Set by closeSession: any later session-thread exit is user-driven. */
        @Volatile var stopRequested: Boolean = false
        /** True once audio actually started streaming. */
        @Volatile var streamingBegan: Boolean = false
    }

    override fun discover(timeoutMs: Int): List<SpeakerDevice> = raop.discover(timeoutMs)

    override fun play(device: SpeakerDevice, url: String, title: String, artist: String) {
        val sp = device as? AirPlaySpeaker
            ?: throw IllegalArgumentException("not an AirPlay device")
        synchronized(lock) {
            val cur = session
            if (cur != null && cur.fallback) {
                // Already fell back to classic RAOP for this speaker chain.
                raop.play(device, url, title, artist)
                return
            }
            if (cur != null && cur.device.id == sp.id && cur.url == url) {
                // Already streaming exactly this on AP2.
                return
            }
            if (cur != null) closeSession(cur)
            session = null

            val s = Session(sp, url)
            val pipeline = RaopPcmPipeline(url)
            s.pipeline = pipeline
            pipeline.start()

            val started = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            val pcmSource = PipelinePcmSource(pipeline)
            val thread = Thread({
                try {
                    val est = Ap2Pairing.connect(sp.host, sp.port)
                    s.established = est
                    val audio = Ap2AudioSession(
                        sp.host, est.localIp, est.channel, est.k64, pcmSource,
                        onStreaming = {
                            s.streamingBegan = true
                            started.countDown()
                        },
                    )
                    s.audioSession = audio
                    audio.run()
                    // Clean return after streaming = the track played
                    // out. If this session is still the live one and
                    // nobody stopped it, tell the cast queue to advance.
                    // (The pipeline is exhausted and the socket closes
                    // in finally, so there is nothing to tear down.)
                    if (s.streamingBegan) {
                        val cb = synchronized(lock) {
                            if (session === s && !s.stopRequested) {
                                session = null
                                trackEndedListener
                            } else {
                                null
                            }
                        }
                        cb?.invoke()
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                    started.countDown()
                    RaopLogger.log("[ap2cast] AP2 session ended with error: ${t.message}")
                    // A session that was LIVE and then died (e.g. the
                    // receiver closed the control channel) must not
                    // leave CastManager believing it is still casting:
                    // that zombie state swallowed every player control
                    // (seek included) in 1.3.3. Report the failure so
                    // the cast state clears. Pre-streaming failures are
                    // play()'s business (it falls back to RAOP).
                    if (s.streamingBegan) {
                        val cb = synchronized(lock) {
                            if (session === s && !s.stopRequested) {
                                session = null
                                sessionFailedListener
                            } else {
                                null
                            }
                        }
                        try {
                            cb?.invoke("AirPlay session ended: ${t.message ?: "connection lost"}")
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    try {
                        s.established?.socket?.close()
                    } catch (_: Exception) {
                    }
                }
            }, "ap2-session")
            thread.isDaemon = true
            s.thread = thread
            thread.start()

            val ok = try {
                started.await(20, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }
            if (!ok || failure.get() != null) {
                RaopLogger.log(
                    "[ap2cast] AP2 start failed (${failure.get()?.message ?: "timeout"}) — " +
                        "falling back to classic RAOP",
                )
                closeSession(s)
                raop.play(device, url, title, artist)
                s.fallback = true
                session = s
                return
            }
            RaopLogger.log("[ap2cast] AP2 streaming to '${sp.name}' (${sp.host}:${sp.port})")
            session = s
            if (volumePercent != 100) {
                val db = (-30.0 + (volumePercent / 100.0) * 30.0).toFloat()
                s.audioSession?.setVolumeDb(db)
            }
        }
    }

    override fun pause(device: SpeakerDevice) {
        synchronized(lock) {
            val cur = session ?: return
            if (cur.fallback) {
                raop.pause(device)
                return
            }
            // v1: pause ends the AP2 session; resume starts a fresh one.
            closeSession(cur)
            session = null
        }
    }

    override fun stop(device: SpeakerDevice) {
        synchronized(lock) {
            val cur = session ?: return
            session = null
            if (cur.fallback) {
                raop.stop(device)
            }
            closeSession(cur)
        }
    }

    @Volatile
    private var volumePercent: Int = 100

    /** Set the speaker volume (0..100). Mirrors the RAOP dB mapping. */
    fun setVolume(percent: Int) {
        val p = percent.coerceIn(0, 100)
        volumePercent = p
        val cur = synchronized(lock) { session }
        if (cur != null && cur.fallback) {
            raop.setVolume(p)
            return
        }
        val db = (-30.0 + (p / 100.0) * 30.0).toFloat()
        cur?.audioSession?.setVolumeDb(db)
    }

    private fun closeSession(s: Session) {
        // Mark first: the session thread must not mistake this teardown
        // for a natural track end.
        s.stopRequested = true
        try {
            s.audioSession?.requestStop()
        } catch (_: Exception) {
        }
        try {
            s.thread?.join(2500)
        } catch (_: InterruptedException) {
        }
        try {
            s.established?.socket?.close()
        } catch (_: Exception) {
        }
        try {
            s.thread?.join(1000)
        } catch (_: InterruptedException) {
        }
        try {
            s.pipeline?.release()
        } catch (_: Exception) {
        }
    }

    /** Adapts the split L/R pipeline frames to interleaved AP2 chunks. */
    private class PipelinePcmSource(
        private val pipeline: RaopPcmPipeline,
    ) : Ap2AudioSession.PcmSource {
        @Volatile
        private var fin = false

        override val finished: Boolean
            get() = fin

        override fun nextInterleaved(): ShortArray? {
            if (fin) return null
            val f = pipeline.takeFrame(30) ?: return null
            if (pipeline.isEos(f)) {
                fin = true
                return null
            }
            val out = ShortArray(AlacFrame.FRAMES_PER_PACKET * 2)
            val n = minOf(f.count, AlacFrame.FRAMES_PER_PACKET)
            for (i in 0 until n) {
                out[2 * i] = f.left[i]
                out[2 * i + 1] = f.right[i]
            }
            return out
        }
    }
}
