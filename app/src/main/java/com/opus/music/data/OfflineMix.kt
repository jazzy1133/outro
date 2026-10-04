package com.opus.music.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.opus.music.Graph
import com.opus.music.Session
import com.opus.music.network.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Smart Offline Mix: keeps the user's most-played and starred songs
 * downloaded automatically whenever the phone is on Wi-Fi (or any
 * unmetered connection). Runs when the app starts and on demand from
 * Settings — no background worker, so it never drains battery unseen.
 */
object OfflineMix {

    private const val SYNC_INTERVAL_MS = 20 * 60 * 60 * 1000L // 20h

    data class SyncResult(val downloaded: Int, val skipped: Int, val message: String)

    private fun networkOk(ctx: Context, wifiOnly: Boolean): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            if (wifiOnly) {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Exception) { false }
    }

    /** Entry point: call from app start (IO dispatcher) and from "Sync now". */
    suspend fun syncIfNeeded(ctx: Context, force: Boolean = false): SyncResult =
        withContext(Dispatchers.IO) {
            val settings = try { Graph.settings } catch (_: Exception) {
                return@withContext SyncResult(0, 0, "not ready")
            }
            if (!settings.isOfflineMixEnabled()) return@withContext SyncResult(0, 0, "disabled")
            if (Session.music == null || Session.client == null)
                return@withContext SyncResult(0, 0, "not logged in")
            val stats = try { Graph.stats } catch (_: Exception) {
                return@withContext SyncResult(0, 0, "not ready")
            }
            val now = System.currentTimeMillis()
            if (!force && now - stats.getLastMixSync() < SYNC_INTERVAL_MS)
                return@withContext SyncResult(0, 0, "recently synced")
            if (!networkOk(ctx.applicationContext, settings.isOfflineMixWifiOnly()))
                return@withContext SyncResult(0, 0, "waiting for wi-fi")

            try {
                // MIX_UNLIMITED (-1) means: take every mix candidate available.
                val maxSize = settings.getOfflineMixSize()
                val limited = maxSize != SettingsRepository.MIX_UNLIMITED
                val candidates = stats.topPlayed(if (limited) 300 else Int.MAX_VALUE)
                // One server call: fold starred songs into the candidates so
                // pure favorites with no local plays still make the mix.
                val starredSongs = try {
                    Session.music?.starred()?.song ?: emptyList()
                } catch (_: Exception) { emptyList() }
                val starredIds = starredSongs.map { it.id }.toSet()
                val withStarred = candidates.toMutableList()
                val known = withStarred.map { it.meta.id }.toSet()
                for (s in starredSongs) {
                    if (s.id !in known) {
                        withStarred.add(
                            MixCandidate(
                                SongMeta(s.id, s.title, s.artist, s.album, s.coverArt, s.duration, s.suffix),
                                playCount = 0, starred = true
                            )
                        )
                    }
                }

                val downloadedIds = withStarred.mapNotNull {
                    if (runCatching { Graph.downloads.isDownloaded(it.meta.id) }.getOrDefault(false)) it.meta.id else null
                }.toSet()

                val mix = OfflineMixSelector.selectMix(withStarred, starredIds, downloadedIds, maxSize)
                var done = 0
                val client = Session.client ?: return@withContext SyncResult(0, 0, "not logged in")
                val bitrate = try { settings.getStreamBitrate() } catch (_: Exception) { 0 }
                for (m in mix) {
                    try {
                        val song = Song(
                            id = m.id, title = m.title, artist = m.artist, album = m.album,
                            coverArt = m.coverArt, duration = m.duration, suffix = m.suffix
                        )
                        Graph.downloads.download(song, client.streamUrl(song.id, bitrate))
                        done++
                    } catch (_: Exception) {
                        // Keep going; one bad file shouldn't kill the mix.
                    }
                }
                stats.setLastMixSync(now)
                SyncResult(done, mix.size - done, "ok")
            } catch (e: Exception) {
                SyncResult(0, 0, "error: ${e.message}")
            }
        }
}
