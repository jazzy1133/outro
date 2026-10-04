package com.opus.music.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.opus.music.Graph
import com.opus.music.data.SongMeta
import com.opus.music.mixes.SmartMixes
import com.opus.music.network.Song
import java.util.concurrent.ConcurrentHashMap

/**
 * Android Auto / Automotive browse tree for Outro.
 *
 * Root -> "Now Playing" (the current phone queue) and "Mixes"
 * (Heavy Rotation / Recently Played / Daily Mix, built from local stats).
 * Playable items carry a "song:<id>" mediaId; [onAddMediaItems] resolves
 * them to streamable URIs via [PlayerManager] just before playback.
 */
class AutoLibraryCallback : MediaLibraryService.MediaLibrarySession.Callback {

    companion object {
        private const val ROOT_ID = "root"
        private const val QUEUE_ID = "queue"
        private const val MIXES_ID = "mixes"
        private const val SONG_PREFIX = "song:"
        private const val MIX_PREFIX = "mix:"
    }

    /** mediaId -> Song, filled when browsing so playback can resolve URIs. */
    private val registry = ConcurrentHashMap<String, Song>()

    private fun node(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()

    private fun playable(song: Song): MediaItem {
        val id = SONG_PREFIX + song.id
        registry[id] = song
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title.ifEmpty { "Unknown title" })
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun stubSong(m: SongMeta): Song = Song(
        id = m.id,
        title = m.title,
        album = m.album,
        artist = m.artist,
        coverArt = m.coverArt,
        duration = m.duration,
        suffix = m.suffix
    )

    private data class MixDef(val id: String, val title: String)

    private val mixes = listOf(
        MixDef("heavy", "Heavy Rotation"),
        MixDef("recent", "Recently Played"),
        MixDef("daily", "Daily Mix")
    )

    private fun mixSongs(id: String): List<SongMeta> = try {
        val stats = Graph.stats
        when (id) {
            "heavy" -> SmartMixes.heavyRotation(stats.topPlayed(500), 50)
            "recent" -> stats.recentlyPlayed(50)
            "daily" -> SmartMixes.dailyMix(stats.topPlayed(500), emptySet(), 50)
            else -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(node(ROOT_ID, "Outro"), params))

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
        val items: List<MediaItem> = when {
            parentId == ROOT_ID -> listOf(node(QUEUE_ID, "Now Playing"), node(MIXES_ID, "Mixes"))
            parentId == QUEUE_ID -> try {
                PlayerManager.queueSongs.value.map { playable(it) }
            } catch (_: Exception) {
                emptyList()
            }
            parentId == MIXES_ID -> mixes.map { node(MIX_PREFIX + it.id, it.title) }
            parentId.startsWith(MIX_PREFIX) ->
                mixSongs(parentId.removePrefix(MIX_PREFIX)).map { playable(stubSong(it)) }
            else -> emptyList()
        }
        val from = (page * pageSize).coerceAtMost(items.size)
        val to = ((page + 1) * pageSize).coerceAtMost(items.size)
        return Futures.immediateFuture(LibraryResult.ofItemList(items.subList(from, to), params))
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val song = registry[mediaId]
            ?: return Futures.immediateFuture(
                LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        return Futures.immediateFuture(LibraryResult.ofItem(playable(song), null))
    }

    override fun onAddMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        val resolved = mediaItems.map { item ->
            val song = registry[item.mediaId]
                ?: PlayerManager.queueSongs.value.firstOrNull { SONG_PREFIX + it.id == item.mediaId }
            if (song != null) {
                try {
                    PlayerManager.mediaItemFor(song)
                } catch (_: Exception) {
                    item
                }
            } else item
        }
        return Futures.immediateFuture(resolved)
    }
}
