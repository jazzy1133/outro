package com.opus.music.cast

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Sonos speakers over DLNA/UPnP: SSDP discovery plus SOAP AVTransport
 * transport control (set stream URL, play, pause, stop).
 *
 * No partnership or cloud account needed — Sonos exposes a local UPnP
 * AVTransport service that accepts any HTTP(S) audio URL, including the
 * app's Subsonic token-auth stream URLs.
 *
 * All functions do blocking network I/O; callers must use Dispatchers.IO.
 * Pure parsing helpers are unit-testable without a network.
 */
object SonosDlna {

    data class SonosDevice(
        val udn: String,
        val name: String,
        val avTransportUrl: String,
    )

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val ZONE_PLAYER_ST = "urn:schemas-upnp-org:device:ZonePlayer:1"
    private const val AVTRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"

    /**
     * Multicast M-SEARCH for Sonos ZonePlayers. Returns one entry per
     * distinct speaker (deduped by UDN).
     */
    fun discover(timeoutMs: Int = 4000): List<SonosDevice> {
        val found = linkedMapOf<String, SonosDevice>()
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket()
            socket.soTimeout = 800
            val group = InetAddress.getByName(SSDP_ADDR)
            val msg = buildMSearch(ZONE_PLAYER_ST)
            val pkt = DatagramPacket(msg, msg.size, group, SSDP_PORT)
            // A few rounds; speakers answer with unicast UDP.
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastSend = 0L
            val buf = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now - lastSend > 1200) {
                    try { socket.send(pkt) } catch (_: Exception) {}
                    lastSend = now
                }
                try {
                    val recv = DatagramPacket(buf, buf.size)
                    socket.receive(recv)
                    val location = parseSsdpLocation(
                        recv.data.decodeToString(0, recv.length)
                    ) ?: continue
                    val device = describeDevice(location) ?: continue
                    found.putIfAbsent(device.udn, device)
                } catch (_: java.net.SocketTimeoutException) {
                    // keep waiting until the deadline
                } catch (_: Exception) {
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
        return found.values.toList()
    }

    /** Point the speaker at [streamUrl] and start playing it. */
    fun play(device: SonosDevice, streamUrl: String, title: String, artist: String) {
        val meta = didlLite(streamUrl, title, artist)
        soap(
            device.avTransportUrl, "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${xmlEscape(streamUrl)}</CurrentURI>" +
                "<CurrentURIMetaData>${xmlEscape(meta)}</CurrentURIMetaData>"
        )
        soap(device.avTransportUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    fun pause(device: SonosDevice) {
        soap(device.avTransportUrl, "Pause", "<InstanceID>0</InstanceID>")
    }

    /** Resume a paused renderer in place (Play without a new URI). */
    fun resume(device: SonosDevice) {
        soap(device.avTransportUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    fun stop(device: SonosDevice) {
        soap(device.avTransportUrl, "Stop", "<InstanceID>0</InstanceID>")
    }

    /** Seek the renderer to an absolute position in the current track. */
    fun seek(device: SonosDevice, positionMs: Long) {
        soap(
            device.avTransportUrl, "Seek",
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                "<Target>${formatRelTime(positionMs)}</Target>"
        )
    }

    /** Current AVTransport state: PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, … */
    fun transportState(device: SonosDevice): String? {
        val body = soap(
            device.avTransportUrl, "GetTransportInfo",
            "<InstanceID>0</InstanceID>"
        )
        return parseTransportState(body)
    }

    /** (positionMs, durationMs) for the current track, or null when unknown. */
    fun positionInfo(device: SonosDevice): Pair<Long, Long>? {
        val body = soap(
            device.avTransportUrl, "GetPositionInfo",
            "<InstanceID>0</InstanceID>"
        )
        return parsePositionInfo(body)
    }

    // ------------------------------------------------------------------
    // Pure helpers (unit-testable)
    // ------------------------------------------------------------------

    fun buildMSearch(searchTarget: String, mx: Int = 3): ByteArray =
        ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
            "MAN: \"ns=01\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $searchTarget\r\n" +
            "\r\n").toByteArray(Charsets.UTF_8)

    /** Extract the LOCATION header value from an SSDP response, or null. */
    fun parseSsdpLocation(response: String): String? {
        for (line in response.lines()) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("location", ignoreCase = true)) {
                return line.substring(idx + 1).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /**
     * Parse a Sonos device description XML: returns (udn, friendlyName,
     * avTransportControlUrl) with the control URL resolved to absolute form
     * against [locationUrl], or null when the AVTransport service is absent.
     */
    fun parseDeviceDesc(xml: String, locationUrl: String): Triple<String, String, String>? {
        return try {
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = false
            val doc = dbf.newDocumentBuilder().parse(xml.byteInputStream())
            val udn = doc.getElementsByTagName("UDN").item(0)?.textContent?.trim()
                ?: return null
            val name = doc.getElementsByTagName("friendlyName").item(0)?.textContent?.trim()
                ?: "Sonos"
            val services = doc.getElementsByTagName("service")
            var controlUrl: String? = null
            for (i in 0 until services.length) {
                val svc = services.item(i)
                var type: String? = null
                var ctrl: String? = null
                val kids = svc.childNodes
                for (j in 0 until kids.length) {
                    when (kids.item(j).nodeName) {
                        "serviceType" -> type = kids.item(j).textContent?.trim()
                        "controlURL" -> ctrl = kids.item(j).textContent?.trim()
                    }
                }
                if (type == AVTRANSPORT_SERVICE && !ctrl.isNullOrEmpty()) {
                    controlUrl = ctrl
                    break
                }
            }
            controlUrl ?: return null
            val absolute = URL(URL(locationUrl), controlUrl).toString()
            Triple(udn, name, absolute)
        } catch (_: Exception) {
            null
        }
    }

    fun buildSoapEnvelope(action: String, bodyXml: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:$action xmlns:u=\"$AVTRANSPORT_SERVICE\">$bodyXml</u:$action>" +
            "</s:Body></s:Envelope>"

    fun didlLite(streamUrl: String, title: String, artist: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"outro-stream\" parentID=\"0\" restricted=\"true\">" +
            "<dc:title>${xmlEscape(title)}</dc:title>" +
            "<upnp:artist>${xmlEscape(artist)}</upnp:artist>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:audio/mpeg:*\">${xmlEscape(streamUrl)}</res>" +
            "</item></DIDL-Lite>"

    fun xmlEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Format a millisecond offset as a UPnP REL_TIME (H:MM:SS). */
    fun formatRelTime(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%d:%02d:%02d".format(h, m, s)
    }

    /** Parse an H:MM:SS(.fraction) UPnP time to milliseconds; null if unparseable. */
    fun parseRelTime(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().substringBefore('.').split(":")
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toLongOrNull() ?: return null
        return (h * 3600 + m * 60 + s) * 1000
    }

    /** Extract &lt;CurrentTransportState&gt; from a GetTransportInfo response. */
    fun parseTransportState(soapXml: String): String? =
        extractTag(soapXml, "CurrentTransportState")

    /** (RelTime, TrackDuration) in ms from a GetPositionInfo response. */
    fun parsePositionInfo(soapXml: String): Pair<Long, Long>? {
        val rel = parseRelTime(extractTag(soapXml, "RelTime")) ?: return null
        val dur = parseRelTime(extractTag(soapXml, "TrackDuration")) ?: 0L
        return rel to dur
    }

    /** Text of the first &lt;tag&gt;…&lt;/tag&gt; in [xml], XML-unescaped; null if absent. */
    fun extractTag(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf(close, start + open.length)
        if (end < 0) return null
        return xml.substring(start + open.length, end)
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------
    // Network internals
    // ------------------------------------------------------------------

    private fun describeDevice(locationUrl: String): SonosDevice? {
        return try {
            val xml = httpGet(locationUrl, 4000) ?: return null
            val (udn, name, controlUrl) = parseDeviceDesc(xml, locationUrl) ?: return null
            SonosDevice(udn, name, controlUrl)
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "text/xml")
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** POST a SOAP action; returns the response body ("" when empty). Throws on HTTP errors. */
    private fun soap(controlUrl: String, action: String, bodyXml: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(controlUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty(
                "SOAPACTION", "\"$AVTRANSPORT_SERVICE#$action\""
            )
            val payload = buildSoapEnvelope(action, bodyXml).toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try {
                    conn.errorStream?.let { es ->
                        val bos = ByteArrayOutputStream()
                        es.copyTo(bos)
                        bos.toString(Charsets.UTF_8)
                    }
                } catch (_: Exception) { null }
                throw IllegalStateException("Sonos SOAP $action failed: HTTP $code ${err?.take(200)}")
            }
            return try {
                conn.inputStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            } catch (_: Exception) {
                ""
            }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
