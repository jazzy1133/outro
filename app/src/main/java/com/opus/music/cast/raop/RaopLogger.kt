package com.opus.music.cast.raop

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostic log for the AirPlay/RAOP connection task only.
 *
 * The HomePod rejects our ANNOUNCE with RTSP 406 and we cannot reproduce it
 * here, so the app records exactly what it sends (request line, headers, SDP)
 * and what the speaker replies (status, headers, body) plus discovery details
 * (resolved host/port, TXT attributes like et/am/pw). The user opens
 * Settings → Speakers → "AirPlay connection log" to read, copy or share it.
 *
 * In-memory ring buffer (last 400 lines); nothing leaves the device unless
 * the user explicitly copies/shares it. Pure JVM — no Android or coroutines
 * APIs, so the JVM unit tests can compile and exercise it. UI layers observe
 * via addListener/removeListener.
 */
object RaopLogger {
    private const val MAX_LINES = 400
    private val lock = Any()
    private val lines = ArrayList<String>(MAX_LINES)
    private val listeners = LinkedHashSet<() -> Unit>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun addListener(listener: () -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    private fun notifyLocked(): List<() -> Unit> =
        synchronized(lock) { listeners.toList() }

    fun log(message: String) {
        val stamped = "${timeFmt.format(Date())} $message"
        synchronized(lock) {
            lines.add(stamped)
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
        // println keeps JVM unit tests (and any logcat-less run) able to see
        // the handshake; on Android the in-app viewer is the primary surface.
        println("[Raop] $message")
        for (l in notifyLocked()) {
            runCatching { l() }
        }
    }

    /** Log a multi-line block (e.g. a full RTSP request) as one entry. */
    fun logBlock(header: String, block: String) {
        log(header)
        for (line in block.split("\n")) {
            log("    $line")
        }
    }

    /** Plain-text snapshot for display and the Share sheet. */
    fun snapshot(): String = synchronized(lock) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lock) { lines.clear() }
        for (l in notifyLocked()) {
            runCatching { l() }
        }
    }
}
