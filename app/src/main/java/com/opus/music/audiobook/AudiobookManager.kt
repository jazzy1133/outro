package com.opus.music.audiobook

import android.content.Context
import com.opus.music.network.BookmarkEntry
import com.opus.music.network.Song
import com.opus.music.network.SubsonicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Audiobook mode: detection, per-book playback speed, resume points and
 * user bookmarks.
 *
 * Resume points are stored ON DEVICE ONLY (SharedPreferences). This is
 * deliberate: Subsonic's bookmark model keeps a single bookmark per media
 * item, so a server-side resume point could overwrite — or be wiped out
 * by — the user's own bookmark for the same track. User bookmarks live on
 * the server (Navidrome implements getBookmarks/createBookmark/
 * deleteBookmark); creating one replaces any existing server bookmark for
 * that track, and deleting removes it.
 */
class AudiobookManager(
    private val context: Context,
    private val api: () -> SubsonicApi?
) {
    private val prefs = context.getSharedPreferences("opus_audiobooks", Context.MODE_PRIVATE)

    companion object {
        /** Tracks this long (or tagged as spoken word) count as audiobooks. */
        const val AUDIOBOOK_MIN_SECONDS = 20 * 60
        val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

        /**
         * Heuristic audiobook detection: long duration or spoken-word genre.
         * Pure — no I/O.
         */
        fun isAudiobook(song: Song): Boolean {
            if (song.duration >= AUDIOBOOK_MIN_SECONDS) return true
            val g = (song.genre ?: "").lowercase()
            return g.contains("audiobook") || g.contains("podcast") ||
                g.contains("speech") || g.contains("spoken")
        }

        /** Next speed step up from [current], wrapping around. Pure. */
        fun nextSpeed(current: Float): Float {
            val idx = SPEEDS.indexOfFirst { it > current + 0.01f }
            return if (idx < 0) SPEEDS[0] else SPEEDS[idx]
        }
    }

    /** Grouping key: prefer album (a book), else the single track. */
    fun bookKey(song: Song): String = song.albumId ?: song.album ?: song.id

    fun getSpeed(song: Song): Float = try {
        prefs.getFloat("speed_" + bookKey(song), 1f).coerceIn(0.5f, 3f)
    } catch (_: Exception) { 1f }

    fun setSpeed(song: Song, speed: Float) {
        try { prefs.edit().putFloat("speed_" + bookKey(song), speed.coerceIn(0.5f, 3f)).apply() }
        catch (_: Exception) { }
    }

    /** Next speed step up from [current], wrapping around. */
    fun cycleSpeed(current: Float): Float = nextSpeed(current)

    /** On-device resume point for a track. 0 = none. */
    suspend fun getResumePosition(songId: String): Long = withContext(Dispatchers.IO) {
        try { prefs.getLong("resume_$songId", 0L) } catch (_: Exception) { 0L }
    }

    /** Save an on-device resume point. Positions under 10 s clear the point. */
    suspend fun saveResumePosition(songId: String, positionMs: Long) {
        withContext(Dispatchers.IO) {
            try {
                if (positionMs < 10_000) prefs.edit().remove("resume_$songId").apply()
                else prefs.edit().putLong("resume_$songId", positionMs).apply()
            } catch (_: Exception) { }
        }
    }

    suspend fun clearResumePosition(songId: String) {
        withContext(Dispatchers.IO) {
            try { prefs.edit().remove("resume_$songId").apply() } catch (_: Exception) { }
        }
    }

    /**
     * User bookmarks from the server, newest first. The server keeps one
     * bookmark per track: creating a bookmark for a track that already has
     * one replaces it.
     */
    suspend fun getUserBookmarks(): List<BookmarkEntry> = withContext(Dispatchers.IO) {
        try {
            api()?.getBookmarks()?.response?.bookmarks?.bookmark
                ?.filter { !it.comment.isNullOrBlank() }
                ?.sortedByDescending { it.created ?: "" }
                .orEmpty()
        } catch (_: Exception) { emptyList() }
    }

    suspend fun addBookmark(songId: String, positionMs: Long, comment: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                api()?.createBookmark(songId, positionMs, comment.ifBlank { "Bookmark" })
                true
            } catch (_: Exception) { false }
        }

    suspend fun deleteBookmark(songId: String): Boolean = withContext(Dispatchers.IO) {
        try { api()?.deleteBookmark(songId); true } catch (_: Exception) { false }
    }
}
