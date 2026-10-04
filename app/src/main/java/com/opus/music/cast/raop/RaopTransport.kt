package com.opus.music.cast.raop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.opus.music.cast.AirPlaySpeaker
import com.opus.music.cast.SpeakerDevice
import com.opus.music.cast.SpeakerKind
import com.opus.music.cast.UrlSpeakerTransport
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * RAOP transport: the phone pulls the Subsonic stream URL itself, decodes
 * to PCM and pushes encrypted ALAC over the air to the speaker.
 *
 * Fits [UrlSpeakerTransport] so [com.opus.music.cast.CastManager] can treat
 * it like the other speaker families: [play] starts a session,
 * [pause]/[stop] map to FLUSH/TEARDOWN, and a second [play] for the same
 * device+URL resumes a paused session.
 */
class RaopTransport(private val appContext: Context) : UrlSpeakerTransport,
    com.opus.music.cast.SeekableCastTransport {
    override val kind: SpeakerKind = SpeakerKind.AIRPLAY

    private val lock = Any()
    private var session: Session? = null

    @Volatile
    private var trackEndedListener: (() -> Unit)? = null

    override fun setOnTrackEndedListener(listener: (() -> Unit)?) {
        trackEndedListener = listener
    }

    private class Session(
        val device: AirPlaySpeaker,
        val url: String,
        val connection: RaopConnection,
        val pipeline: RaopPcmPipeline,
    ) {
        @Volatile var paused = false
    }

    // ------------------------------------------------------------------
    // Discovery via NsdManager (_raop._tcp)
    // ------------------------------------------------------------------

    override fun discover(timeoutMs: Int): List<SpeakerDevice> {
        val nsd = try {
            appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        } catch (e: Exception) {
            return emptyList()
        }
        val found = mutableMapOf<String, AirPlaySpeaker>()
        val foundLock = Any()
        val done = CountDownLatch(1)

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                done.countDown()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                try {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val rawName = si.serviceName ?: return
                            // RAOP names look like "<device-id>@<name>".
                            val name = rawName.substringAfter("@", rawName).trim()
                                .ifEmpty { rawName }
                            val hostAddr = si.host?.hostAddress ?: return
                            // TXT record tells us the receiver flavour: et=5 is
                            // classic RAOP (HomePod mini), am= model, pw= password
                            // required. Log it — it shapes what ANNOUNCE may carry.
                            val txt = si.attributes.mapValues { (_, v) ->
                                runCatching { String(v, Charsets.UTF_8) }.getOrNull() ?: "<binary>"
                            }
                            RaopLogger.log(
                                "discover: '$name' @ $hostAddr:${si.port} " +
                                    "et=${txt["et"]} am=${txt["am"]} pw=${txt["pw"]} " +
                                    "md=${txt["md"]} vn=${txt["vn"]}",
                            )
                            val sp = AirPlaySpeaker(
                                id = "raop:$rawName",
                                name = name,
                                host = hostAddr,
                                port = si.port,
                                et = txt["et"],
                            )
                            synchronized(foundLock) { found[sp.id] = sp }
                        }
                    })
                } catch (_: Exception) {
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
        }

        try {
            nsd.discoverServices("_raop._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            return emptyList()
        }
        try {
            done.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        try {
            nsd.stopServiceDiscovery(discoveryListener)
        } catch (_: Exception) {
        }
        // Give in-flight resolutions a beat to land.
        try {
            Thread.sleep(600)
        } catch (_: InterruptedException) {
        }
        synchronized(foundLock) { return found.values.toList() }
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private fun localIp(): String {
        return try {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip == 0) "0.0.0.0" else "%d.%d.%d.%d".format(
                ip and 0xFF, (ip shr 8) and 0xFF, (ip shr 16) and 0xFF, (ip shr 24) and 0xFF,
            )
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    override fun play(device: SpeakerDevice, url: String, title: String, artist: String) {
        val sp = device as? AirPlaySpeaker ?: throw IllegalArgumentException("not an AirPlay device")
        synchronized(lock) {
            val cur = session
            if (cur != null && cur.device.id == sp.id && cur.url == url) {
                if (cur.paused) {
                    // Resume the paused session in place.
                    cur.paused = false
                    cur.pipeline.start()
                    cur.connection.pcmSource = pipelineSource(cur.pipeline)
                    cur.connection.resume()
                }
                return
            }
            cur?.let { closeSession(it) }
            // Encryption mode follows what the speaker advertises in its TXT
            // `et`: 1 = classic RSA key exchange (a=rsaaeskey + AES RTP),
            // 0 = clear (no key lines, plain ALAC RTP). 3/5 need Apple's
            // FairPlay keys, which we don't have. Offering an unadvertised
            // mode gets the ANNOUNCE rejected (seen as RTSP 406 on a
            // HomePod mini advertising et=0,3,5).
            val etVals = sp.et?.split(',')?.map { it.trim() }?.toSet() ?: emptySet()
            val encryptAudio = when {
                "1" in etVals -> true
                "0" in etVals -> false
                else -> throw IOException(
                    "Speaker requires FairPlay encryption (et=${sp.et}); not supported",
                )
            }
            RaopLogger.log(
                "crypto mode: " +
                    if (encryptAudio) "RSA (et=1 advertised)"
                    else "CLEAR (no et=1; et=${sp.et})",
            )
            val connection = RaopConnection(sp.host, sp.port, localIp(), encryptAudio)
            val pipeline = RaopPcmPipeline(url)
            try {
                connection.connect() // throws on handshake failure
            } catch (e: Exception) {
                RaopLogger.log("connect FAILED: ${e.message}")
                try {
                    connection.teardown()
                } catch (_: Exception) {
                }
                throw e
            }
            pipeline.start()
            connection.pcmSource = pipelineSource(pipeline)
            connection.startSender()
            val s = Session(sp, url, connection, pipeline)
            connection.onStreamEnded = {
                // Natural track end on the live session only; pause and
                // teardown stop the sender before EOS can be reported.
                val cb = synchronized(lock) {
                    if (session === s && !s.paused) trackEndedListener else null
                }
                cb?.invoke()
            }
            session = s
        }
    }

    override fun pause(device: SpeakerDevice) {
        synchronized(lock) {
            val cur = session ?: return
            if (cur.paused) return
            cur.paused = true
            try {
                cur.connection.pause() // FLUSH + stop sender
            } catch (_: Exception) {
            }
            cur.pipeline.release()
        }
    }

    override fun stop(device: SpeakerDevice) {
        synchronized(lock) {
            val cur = session ?: return
            session = null
            closeSession(cur)
        }
    }

    /** Stop the transport entirely (e.g. app shutdown). */
    fun shutdown() {
        synchronized(lock) {
            val cur = session
            session = null
            if (cur != null) closeSession(cur)
        }
    }

    fun setVolume(percent: Int) {
        synchronized(lock) { session?.connection?.setVolume(percent) }
    }

    /**
     * Seek within the live session: the decode pipeline jumps to
     * [positionMs]; the sender keeps its RTP timeline and pulls
     * new-position frames next (see [RaopPcmPipeline.seekTo]).
     */
    override fun seek(device: SpeakerDevice, positionMs: Long) {
        val cur = synchronized(lock) { session } ?: return
        try {
            cur.pipeline.seekTo(positionMs)
            RaopLogger.log("[raopcast] seek to ${positionMs}ms")
        } catch (_: Exception) {
        }
    }

    private fun closeSession(s: Session) {
        try {
            s.connection.teardown()
        } catch (_: Exception) {
        }
        try {
            s.pipeline.release()
        } catch (_: Exception) {
        }
    }

    private fun pipelineSource(pipeline: RaopPcmPipeline): RaopConnection.PcmSource =
        object : RaopConnection.PcmSource {
            override fun readFrame(l: ShortArray, r: ShortArray): Int {
                val f = pipeline.takeFrame(250) ?: return -2 // no data yet
                if (pipeline.isEos(f)) return -1
                f.left.copyInto(l, 0, 0, f.count)
                f.right.copyInto(r, 0, 0, f.count)
                return f.count
            }
        }
}
