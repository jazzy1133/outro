import com.opus.music.cast.raop.RaopConnection
import kotlin.system.exitProcess

/**
 * Verifies Outro's RTSP handshake serialization against the lox-airplay-sender /
 * pyatv reference behavior (pure — no sockets, the sandbox blocks TCP):
 *  - every request carries User-Agent / DACP-ID / Active-Remote / Client-Instance
 *  - OPTIONS carries the static Apple-Challenge
 *  - ANNOUNCE/SETUP carry NO Session header; the receiver assigns the session
 *    id in its SETUP response and every later request must echo it
 *  - ANNOUNCE SDP keeps a=rsaaeskey (never fpaeskey)
 *  - session URIs use the sender's local IP
 *  - the local IP is refreshed from the RTSP socket's real local address
 *    (the constructor value may be 0.0.0.0 on Android 10+ without location
 *    permission — an ANNOUNCE to rtsp://0.0.0.0/... gets a 406)
 */
var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun parseHeaders(request: String): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    for (line in request.split("\r\n").drop(1)) {
        if (line.isEmpty()) break
        val i = line.indexOf(':')
        if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
    }
    return headers
}

fun checkSenderHeaders(stage: String, h: Map<String, String>) {
    check("$stage has iTunes User-Agent", (h["user-agent"] ?: "").startsWith("iTunes/"))
    check("$stage has DACP-ID (16 hex)", (h["dacp-id"] ?: "").matches(Regex("[0-9A-F]{16}")))
    check("$stage has Active-Remote", (h["active-remote"] ?: "").isNotEmpty())
    check(
        "$stage Client-Instance == DACP-ID",
        h["client-instance"] == h["dacp-id"] && h["dacp-id"] != null,
    )
}

fun bodyOf(request: String): String {
    val i = request.indexOf("\r\n\r\n")
    return if (i < 0) "" else request.substring(i + 4)
}

fun main() {
    val conn = RaopConnection("192.168.1.99", 5000, "192.168.1.5")

    // 1. OPTIONS ---------------------------------------------------------
    run {
        val req = conn.buildRtspRequest("OPTIONS", "*", mapOf("Apple-Challenge" to RaopConnection.APPLE_CHALLENGE))
        check("OPTIONS request line", req.startsWith("OPTIONS * RTSP/1.0\r\n"))
        val h = parseHeaders(req)
        checkSenderHeaders("OPTIONS", h)
        check("OPTIONS static Apple-Challenge", h["apple-challenge"] == "SdX9kFJVxgKVMFof/Znj4Q")
        check("OPTIONS has no Session header", !h.containsKey("session"))
        check("OPTIONS has CSeq", (h["cseq"] ?: "").isNotEmpty())
    }

    // 2. ANNOUNCE --------------------------------------------------------
    run {
        val sdp = conn.buildSdp()
        val req = conn.buildRtspRequest("ANNOUNCE", conn.sessionUri(), body = sdp, contentType = "application/sdp")
        check(
            "ANNOUNCE uri uses sender local ip",
            req.startsWith("ANNOUNCE rtsp://192.168.1.5/") && req.contains(" RTSP/1.0\r\n"),
        )
        check("ANNOUNCE uri does not use receiver ip", "rtsp://192.168.1.99/" !in req)
        val h = parseHeaders(req)
        checkSenderHeaders("ANNOUNCE", h)
        check("ANNOUNCE has no Session header", !h.containsKey("session"))
        check("ANNOUNCE has no Apple-Challenge", !h.containsKey("apple-challenge"))
        check("ANNOUNCE content-type application/sdp", h["content-type"] == "application/sdp")
        val body = bodyOf(req)
        check("ANNOUNCE body == buildSdp()", body == sdp)
        check("ANNOUNCE sdp has rsaaeskey", "a=rsaaeskey:" in body)
        check("ANNOUNCE sdp has no fpaeskey", "a=fpaeskey:" !in body)
        check("ANNOUNCE sdp has aesiv", "a=aesiv:" in body)
        check("ANNOUNCE sdp is AppleLossless", "a=rtpmap:96 AppleLossless" in body)
        check("ANNOUNCE sdp o= line uses local ip", "o=iTunes " in body && "IN IP4 192.168.1.5" in body)
        // Fixed crypto triple: 256-byte RSA blob -> 344 b64 chars; fixed 16-byte IV.
        val blob = body.lineSequence().first { it.startsWith("a=rsaaeskey:") }.removePrefix("a=rsaaeskey:")
        val ivb64 = body.lineSequence().first { it.startsWith("a=aesiv:") }.removePrefix("a=aesiv:")
        check("ANNOUNCE rsaaeskey blob 344 chars (256-byte ciphertext)", blob.length == 344)
        check("ANNOUNCE rsaaeskey blob decodes to 256 bytes", java.util.Base64.getDecoder().decode(blob).size == 256)
        check("ANNOUNCE aesiv is the fixed IV", ivb64 == "ePRBLI0XN5ArFaaz7ncNZw")
        val declared = h["content-length"]?.toIntOrNull() ?: -1
        check(
            "ANNOUNCE Content-Length matches body bytes",
            declared == body.toByteArray(Charsets.UTF_8).size,
        )
    }

    // 2b. ANNOUNCE clear mode (et=0, no et=1 advertised) -------------------
    // A speaker that doesn't advertise et=1 rejects a=rsaaeskey with 406
    // (seen on a HomePod mini with et=0,3,5), so clear mode omits BOTH key
    // lines — "exactly one present" is a 456 on strict receivers.
    run {
        val clear = RaopConnection("192.168.1.99", 5000, "192.168.1.5", encryptAudio = false)
        val sdp = clear.buildSdp()
        check("clear SDP has no rsaaeskey", "a=rsaaeskey:" !in sdp)
        check("clear SDP has no aesiv", "a=aesiv:" !in sdp)
        check("clear SDP has no fpaeskey", "a=fpaeskey:" !in sdp)
        check("clear SDP keeps AppleLossless", "a=rtpmap:96 AppleLossless" in sdp)
        check("clear SDP keeps fmtp", "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100" in sdp)
        check("clear SDP o= line uses local ip", "IN IP4 192.168.1.5" in sdp)
        check("clear SDP ends after fmtp line", sdp.trimEnd().endsWith("a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100"))
        // Encrypted (default) mode is unchanged.
        check("rsa SDP still has rsaaeskey", "a=rsaaeskey:" in conn.buildSdp())
        check("rsa SDP still has aesiv", "a=aesiv:" in conn.buildSdp())
    }

    // 3. SETUP (pre-session) ---------------------------------------------
    run {
        val req = conn.buildRtspRequest(
            "SETUP", conn.sessionUri(),
            mapOf("Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=1111;timing_port=2222"),
        )
        val h = parseHeaders(req)
        checkSenderHeaders("SETUP", h)
        check("SETUP has no Session header", !h.containsKey("session"))
        check("SETUP advertises control_port", "control_port=1111" in (h["transport"] ?: ""))
        check("SETUP advertises timing_port", "timing_port=2222" in (h["transport"] ?: ""))
    }

    // 4. Session id parsing ----------------------------------------------
    run {
        check(
            "parseSessionId strips timeout param",
            conn.parseSessionId(mapOf("session" to "9876;timeout=60")) == "9876",
        )
        check(
            "parseSessionId plain id",
            conn.parseSessionId(mapOf("session" to "42")) == "42",
        )
        var threw = false
        try {
            conn.parseSessionId(emptyMap())
        } catch (e: Exception) {
            threw = true
        }
        check("parseSessionId throws when missing", threw)
    }

    // 5. Post-SETUP requests echo the assigned session --------------------
    run {
        conn.rtspSession = "9876"
        val record = conn.buildRtspRequest(
            "RECORD", conn.sessionUri(),
            mapOf("Range" to "npt=0-", "RTP-Info" to "seq=1;rtptime=88200"),
        )
        val rh = parseHeaders(record)
        checkSenderHeaders("RECORD", rh)
        check("RECORD echoes server-assigned Session", rh["session"] == "9876")

        val teardown = conn.buildRtspRequest("TEARDOWN", conn.sessionUri())
        check(
            "TEARDOWN echoes server-assigned Session",
            parseHeaders(teardown)["session"] == "9876",
        )

        val heartbeat = conn.buildRtspRequest("OPTIONS", conn.sessionUri())
        check(
            "heartbeat OPTIONS echoes server-assigned Session",
            parseHeaders(heartbeat)["session"] == "9876",
        )

        val vol = conn.buildRtspRequest(
            "SET_PARAMETER", conn.sessionUri(),
            body = "volume: -10.000000", contentType = "text/parameters",
        )
        check(
            "SET_PARAMETER echoes server-assigned Session",
            parseHeaders(vol)["session"] == "9876",
        )
    }

    // 6. Local IP resolution (the 1.2.3 ANNOUNCE 406 fix) --------------------
    run {
        // Simulate the Android 10+ WifiManager failure: constructor gets 0.0.0.0.
        val c2 = RaopConnection("192.168.1.99", 5000, "0.0.0.0")
        check("constructor 0.0.0.0 is visible before refresh", c2.sessionUri().startsWith("rtsp://0.0.0.0/"))
        // A real IPv4 from the socket replaces 0.0.0.0.
        c2.effectiveLocalIp = c2.resolveEffectiveIp("192.168.1.5")
        check("ipv4 replaces 0.0.0.0", c2.effectiveLocalIp == "192.168.1.5")
        check("sessionUri uses resolved ip", c2.sessionUri().startsWith("rtsp://192.168.1.5/"))
        check("sdp o= line uses resolved ip", "IN IP4 192.168.1.5" in c2.buildSdp())
        // 0.0.0.0, blank, null and IPv6 are all rejected (keeps current).
        val c3 = RaopConnection("192.168.1.99", 5000, "192.168.1.7")
        check("rejects 0.0.0.0", c3.resolveEffectiveIp("0.0.0.0") == "192.168.1.7")
        check("rejects blank", c3.resolveEffectiveIp("") == "192.168.1.7")
        check("rejects null", c3.resolveEffectiveIp(null) == "192.168.1.7")
        check("rejects ipv6", c3.resolveEffectiveIp("fd8b:4f84:7d32:99::2") == "192.168.1.7")
        // Without a socket, refreshLocalIpFromSocket keeps the constructor value.
        c3.refreshLocalIpFromSocket()
        check("refresh without socket keeps constructor ip", c3.effectiveLocalIp == "192.168.1.7")
    }

    if (failures > 0) {
        println("$failures FAILURES")
        exitProcess(1)
    }
    println("ALL RAOP HANDSHAKE CHECKS PASSED")
}
