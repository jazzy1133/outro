package com.opus.music.cast.ap2

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.opus.music.cast.raop.RaopLogger
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diagnostic AirPlay 2 transient-pairing probe (no audio).
 *
 * Flow, with every step logged to [RaopLogger]:
 *  1. Discover `_airplay._tcp` (features TXT; bits 43/48 hint transient
 *     support); fall back to 10.0.0.148:7000 (confirmed in the 1.2.5 log).
 *  2. `POST /pair-setup` M1 (HAP SRP-6a, PIN "3939"); on rejection try
 *     `POST /pair-pin-start` first, then M1 again.
 *  3. M2 -> compute SRP -> M3 -> M4, verifying the server proof.
 *  4. HKDF-SHA-512 the control-channel keys from the SRP shared secret.
 *  5. Switch the SAME TCP connection to the encrypted framed channel and
 *     send an encrypted `OPTIONS *`; decrypt + log the response.
 *
 * Finishes with a `PROBE RESULT:` line. Never throws out of [runProbe];
 * every unexpected response is logged and ends the probe cleanly.
 */
object Ap2Probe {
    private const val TAG = "ap2probe"
    private const val FALLBACK_HOST = "10.0.0.148"
    private const val FALLBACK_PORT = 7000
    private const val DISCOVER_MS = 4000L

    private val running = AtomicBoolean(false)
    fun isRunning(): Boolean = running.get()

    fun runProbe(appContext: Context) {
        if (!running.compareAndSet(false, true)) {
            RaopLogger.log("[$TAG] probe already running — ignoring")
            return
        }
        try {
            runCatching { probe(appContext) }.onFailure { t ->
                RaopLogger.log(
                    "[$TAG] PROBE RESULT: FAILED — unexpected error: " +
                        "${t.javaClass.simpleName}: ${t.message}",
                )
            }
        } finally {
            running.set(false)
        }
    }

    // ------------------------------------------------------------------
    // Discovery (_airplay._tcp)
    // ------------------------------------------------------------------
    private data class Target(val name: String, val host: String, val port: Int, val features: String?)

    private fun discoverAirPlay(appContext: Context): List<Target> {
        val nsd = try {
            appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        } catch (e: Exception) {
            RaopLogger.log("[$TAG] discovery: NSD unavailable (${e.message})")
            return emptyList()
        }
        val found = mutableMapOf<String, Target>()
        val lock = Any()
        val done = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
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
                            val name = si.serviceName ?: return
                            val host = si.host?.hostAddress ?: return
                            val txt = si.attributes.mapValues { (_, v) ->
                                runCatching { String(v, Charsets.UTF_8) }.getOrNull() ?: "<binary>"
                            }
                            val features = txt["features"]
                            val bits = featuresBits(features)
                            RaopLogger.log(
                                "[$TAG] discover: '$name' @ $host:${si.port} " +
                                    "features=${features ?: "?"} bits=$bits",
                            )
                            synchronized(lock) {
                                found[name] = Target(name, host, si.port, features)
                            }
                        }
                    })
                } catch (_: Exception) {
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
        }
        try {
            nsd.discoverServices("_airplay._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            RaopLogger.log("[$TAG] discovery: start failed (${e.message})")
            return emptyList()
        }
        try {
            done.await(DISCOVER_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
        try {
            Thread.sleep(600)
        } catch (_: InterruptedException) {
        }
        synchronized(lock) { return found.values.toList() }
    }

    /** Set feature-bit numbers (LSB = bit 0), or "?" when unparseable. */
    private fun featuresBits(features: String?): String {
        if (features == null) return "?"
        return try {
            val v = BigInteger(features.removePrefix("0x").removePrefix("0X"), 16)
            (0..63).filter { v.testBit(it) }.joinToString(",")
        } catch (_: Exception) {
            "?"
        }
    }

    private fun transientCapable(features: String?): Boolean {
        if (features == null) return false
        return try {
            val v = BigInteger(features.removePrefix("0x").removePrefix("0X"), 16)
            v.testBit(43) || v.testBit(48)
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Probe
    // ------------------------------------------------------------------
    private class ProbeFailed(step: String, reason: String) : Exception("$step: $reason")

    private fun probe(appContext: Context) {
        RaopLogger.log("[$TAG] === AirPlay 2 pairing probe start (no audio) ===")
        val targets = discoverAirPlay(appContext)
        val target = targets.firstOrNull { transientCapable(it.features) }
            ?: targets.firstOrNull()
        val host: String
        val port: Int
        if (target != null) {
            host = target.host
            port = target.port
            RaopLogger.log(
                "[$TAG] target: '${target.name}' @ $host:$port " +
                    "(transientBit=${transientCapable(target.features)})",
            )
        } else {
            host = FALLBACK_HOST
            port = FALLBACK_PORT
            RaopLogger.log("[$TAG] discovery found nothing — fallback to $host:$port")
        }

        // -- TCP -------------------------------------------------------
        RaopLogger.log("[$TAG] step 1: TCP connect $host:$port")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 8000)
            socket.soTimeout = 12000
        } catch (e: Exception) {
            throw ProbeFailed("step 1 (TCP connect)", e.message ?: e.javaClass.simpleName)
        }
        RaopLogger.log("[$TAG] step 1: connected (local ${socket.localAddress.hostAddress})")
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        try {
            // -- M1 ----------------------------------------------------
            RaopLogger.log("[$TAG] step 2: POST /pair-setup M1")
            var resp = postPairSetup(host, port, input, output, m1Body(), "M1")
            var entries = handlePairResponse(resp, "M1")
            if (entries == null) {
                // Direct M1 rejected — try /pair-pin-start, then M1 again.
                RaopLogger.log("[$TAG] M1 rejected — trying POST /pair-pin-start, then M1 again")
                val pinResp = postRaw(
                    host, input, output,
                    "POST /pair-pin-start HTTP/1.1",
                    ByteArray(0),
                )
                RaopLogger.log(
                    "[$TAG] /pair-pin-start -> ${pinResp.statusLine} " +
                        "(body ${pinResp.body.size}B)",
                )
                resp = postPairSetup(host, port, input, output, m1Body(), "M1-retry")
                entries = handlePairResponse(resp, "M1-retry")
                if (entries == null) {
                    throw ProbeFailed("step 2 (M1)", "rejected even after /pair-pin-start")
                }
            }
            val salt = Tlv8.first(entries, Tlv8.SALT)
                ?: throw ProbeFailed("step 2 (M2)", "no salt TLV (0x02)")
            val bBytes = Tlv8.first(entries, Tlv8.PUBLIC_KEY)
                ?: throw ProbeFailed("step 2 (M2)", "no pubkey TLV (0x03)")
            RaopLogger.log(
                "[$TAG] M2: salt=${salt.size}B B=${bBytes.size}B " +
                    "A-prefix=${Tlv8.hex(bBytes.copyOfRange(0, minOf(8, bBytes.size)))}…",
            )
            if (salt.size != 16 || bBytes.size != 384) {
                throw ProbeFailed(
                    "step 2 (M2)",
                    "unexpected sizes salt=${salt.size} B=${bBytes.size} (want 16/384)",
                )
            }

            // -- M3 ----------------------------------------------------
            RaopLogger.log("[$TAG] step 3: SRP compute + POST /pair-setup M3")
            val eph = SrpClient.newEphemeral()
            val keys = SrpClient.compute(salt, bBytes, eph.a)
            RaopLogger.log(
                "[$TAG] SRP: A=${eph.aPub.size}B K=${keys.k64.size}B " +
                    "M1proof=${keys.m1.size}B",
            )
            val m3 = Tlv8.encode(
                listOf(
                    Tlv8.STATE to byteArrayOf(0x03),
                    Tlv8.PUBLIC_KEY to keys.aPub,
                    Tlv8.PROOF to keys.m1,
                ),
            )
            resp = postPairSetup(host, port, input, output, m3, "M3")
            entries = handlePairResponse(resp, "M3")
                ?: throw ProbeFailed("step 3 (M3)", "rejected (see log)")
            val serverProof = Tlv8.first(entries, Tlv8.PROOF)
                ?: throw ProbeFailed("step 3 (M4)", "no proof TLV (0x04)")
            val ok = SrpClient.verifyServerProof(keys.aPub, keys.m1, keys.k64, serverProof)
            RaopLogger.log("[$TAG] M4: server proof ${if (ok) "VERIFIED ✓" else "MISMATCH ✗"}")
            if (!ok) throw ProbeFailed("step 3 (M4)", "server proof mismatch")

            // -- keys --------------------------------------------------
            RaopLogger.log("[$TAG] step 4: HKDF-SHA-512 control keys")
            val writeKey = Hkdf.derive(
                keys.k64,
                "Control-Salt".toByteArray(),
                "Control-Write-Encryption-Key".toByteArray(),
                32,
            )
            val readKey = Hkdf.derive(
                keys.k64,
                "Control-Salt".toByteArray(),
                "Control-Read-Encryption-Key".toByteArray(),
                32,
            )
            RaopLogger.log(
                "[$TAG] writeKey=${Tlv8.hex(writeKey.copyOfRange(0, 8))}… " +
                    "readKey=${Tlv8.hex(readKey.copyOfRange(0, 8))}…",
            )

            // -- encrypted OPTIONS --------------------------------------
            RaopLogger.log("[$TAG] step 5: encrypted OPTIONS * on the same TCP connection")
            val channel = Ap2Channel(input, output, writeKey, readKey)
            val req =
                "OPTIONS * RTSP/1.0\r\n" +
                    "CSeq: 0\r\n" +
                    "Apple-Challenge: SdX9kFJVxgKVMFof/Znj4Q\r\n" +
                    "\r\n"
            channel.sendFrame(req.toByteArray(Charsets.UTF_8))
            RaopLogger.log("[$TAG] encrypted OPTIONS sent (${req.length}B plaintext)")
            val plain = channel.readFrame()
            val text = String(plain, Charsets.UTF_8)
            val statusLine = text.lineSequence().firstOrNull() ?: "<empty>"
            RaopLogger.log("[$TAG] encrypted response: $statusLine")
            RaopLogger.logBlock("[$TAG] response headers", text.lines().take(12).joinToString("\n"))
            RaopLogger.log("[$TAG] PROBE RESULT: SUCCESS — paired + encrypted OPTIONS answered")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun m1Body(): ByteArray = Tlv8.encode(
        listOf(
            Tlv8.METHOD to byteArrayOf(0x00),
            Tlv8.STATE to byteArrayOf(0x01),
            Tlv8.FLAGS to byteArrayOf(0x00),
        ),
    )

    private data class HttpResp(val statusLine: String, val status: Int, val body: ByteArray)

    private fun postPairSetup(
        host: String,
        port: Int,
        input: java.io.InputStream,
        output: java.io.OutputStream,
        body: ByteArray,
        label: String,
    ): HttpResp {
        val resp = postRaw(host, input, output, "POST /pair-setup HTTP/1.1", body)
        RaopLogger.log(
            "[$TAG] $label -> ${resp.statusLine} (body ${resp.body.size}B) " +
                "TLV[${Tlv8.summary(runCatching { Tlv8.decode(resp.body) }.getOrElse { emptyList() })}]",
        )
        return resp
    }

    private fun postRaw(
        host: String,
        input: java.io.InputStream,
        output: java.io.OutputStream,
        requestLine: String,
        body: ByteArray,
    ): HttpResp {
        val head =
            "$requestLine\r\n" +
                "Host: $host\r\n" +
                "X-Apple-HKP: 4\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
        return readHttpResponse(input)
    }

    private fun readHttpResponse(input: java.io.InputStream): HttpResp {
        val headBuf = ByteArrayOutputStream()
        val window = ByteArray(4)
        var filled = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("connection closed reading response head")
            headBuf.write(b)
            window[filled % 4] = b.toByte()
            filled++
            if (filled >= 4 &&
                window[(filled - 4) % 4] == '\r'.code.toByte() &&
                window[(filled - 3) % 4] == '\n'.code.toByte() &&
                window[(filled - 2) % 4] == '\r'.code.toByte() &&
                window[(filled - 1) % 4] == '\n'.code.toByte()
            ) {
                break
            }
            if (headBuf.size() > 65536) throw java.io.IOException("response head too large")
        }
        val head = String(headBuf.toByteArray(), Charsets.UTF_8)
        val lines = head.split("\r\n")
        val statusLine = lines.firstOrNull() ?: ""
        val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        var contentLength = -1
        for (ln in lines.drop(1)) {
            val i = ln.indexOf(':')
            if (i > 0 && ln.substring(0, i).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = ln.substring(i + 1).trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength < 0) throw java.io.IOException("no Content-Length in response ($statusLine)")
        val body = ByteArray(contentLength)
        var off = 0
        while (off < contentLength) {
            val r = input.read(body, off, contentLength - off)
            if (r < 0) throw java.io.EOFException("connection closed reading response body")
            off += r
        }
        return HttpResp(statusLine, status, body)
    }

    /**
     * Returns the decoded TLV entries, or null when the response is an
     * error/rejection (already logged).
     */
    private fun handlePairResponse(resp: HttpResp, label: String): List<Pair<Int, ByteArray>>? {
        if (resp.status != 200) {
            RaopLogger.log("[$TAG] $label: HTTP ${resp.status} — rejected")
            return null
        }
        val entries = runCatching { Tlv8.decode(resp.body) }.getOrElse {
            RaopLogger.log("[$TAG] $label: body is not TLV8 (${it.message})")
            return null
        }
        val err = Tlv8.first(entries, Tlv8.ERROR)
        if (err != null && err.isNotEmpty()) {
            val code = err[0].toInt() and 0xFF
            RaopLogger.log("[$TAG] $label: TLV error ${Tlv8.errorName(code)}")
            return null
        }
        val state = Tlv8.first(entries, Tlv8.STATE)?.firstOrNull()?.toInt()?.and(0xFF)
        if (state == null) {
            RaopLogger.log("[$TAG] $label: no state TLV")
            return null
        }
        RaopLogger.log("[$TAG] $label: state=$state")
        return entries
    }
}
