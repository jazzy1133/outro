package com.opus.music.lyrics

import android.content.Context
import com.opus.music.network.Song
import com.opus.music.network.SubsonicApi
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where synced lyrics came from. */
data class LyricsData(
    val lines: List<LyricLine>,
    val plainText: String?,
    val synced: Boolean,
    val source: String
)

@Serializable
private data class LrclibResult(
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val instrumental: Boolean = false
)

/**
 * Synced lyrics via LRCLIB (free, no key), falling back to the server's
 * getLyrics (Navidrome: embedded tags + external files). Results are cached
 * on disk per song id.
 */
class LyricsRepository(
    private val context: Context,
    private val api: () -> SubsonicApi?
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val mem = LinkedHashMap<String, LyricsData>()

    private fun cacheFile(songId: String): File {
        val dir = File(context.cacheDir, "lyrics").also { it.mkdirs() }
        val safe = songId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        return File(dir, "$safe.json")
    }

    suspend fun getLyrics(song: Song): LyricsData = withContext(Dispatchers.IO) {
        mem[song.id]?.let { return@withContext it }
        readDisk(song.id)?.let { mem[song.id] = it; return@withContext it }

        // 1. LRCLIB synced lookup.
        val lrclib = fetchLrclib(song)
        if (lrclib != null) {
            val lines = lrclib.syncedLyrics?.let { LrcParser.parse(it) }.orEmpty()
            if (lines.isNotEmpty()) {
                val data = LyricsData(lines, lrclib.plainLyrics, true, "lrclib")
                store(song.id, data); return@withContext data
            }
            if (!lrclib.plainLyrics.isNullOrBlank()) {
                val data = LyricsData(emptyList(), lrclib.plainLyrics, false, "lrclib")
                store(song.id, data); return@withContext data
            }
        }

        // 2. Server getLyrics fallback (plain text).
        try {
            val res = api()?.getLyrics(song.artist, song.title)
            val text = res?.response?.lyrics?.value?.trim()?.takeIf { it.isNotEmpty() }
            if (text != null) {
                val data = if (LrcParser.looksSynced(text))
                    LyricsData(LrcParser.parse(text), null, true, "server")
                else
                    LyricsData(emptyList(), text, false, "server")
                store(song.id, data); return@withContext data
            }
        } catch (_: Exception) { }

        val none = LyricsData(emptyList(), null, false, "none")
        mem[song.id] = none
        none
    }

    private fun fetchLrclib(song: Song): LrclibResult? {
        return try {
            val q = buildString {
                append("artist_name=").append(enc(song.artist ?: ""))
                append("&track_name=").append(enc(song.title))
                append("&album_name=").append(enc(song.album ?: ""))
                if (song.duration > 0) append("&duration=").append(song.duration)
            }
            val req = Request.Builder()
                .url("https://lrclib.net/api/get?$q")
                .header("User-Agent", "Outro/1.0 (https://github.com/jazzy1133/outro)")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                json.decodeFromString(LrclibResult.serializer(), body)
            }
        } catch (_: Exception) { null }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    @Serializable
    private data class DiskEntry(
        val lines: List<DiskLine> = emptyList(),
        val plainText: String? = null,
        val synced: Boolean = false,
        val source: String = ""
    )

    @Serializable
    private data class DiskLine(val t: Long, val x: String)

    private fun store(id: String, data: LyricsData) {
        mem[id] = data
        try {
            val disk = DiskEntry(
                data.lines.map { DiskLine(it.timeMs, it.text) },
                data.plainText, data.synced, data.source
            )
            cacheFile(id).writeText(json.encodeToString(DiskEntry.serializer(), disk))
        } catch (_: Exception) { }
    }

    private fun readDisk(id: String): LyricsData? {
        return try {
            val f = cacheFile(id)
            if (!f.exists()) return null
            val disk = json.decodeFromString(DiskEntry.serializer(), f.readText())
            LyricsData(disk.lines.map { LyricLine(it.t, it.x) }, disk.plainText, disk.synced, disk.source)
        } catch (_: Exception) { null }
    }
}
