package com.opus.music.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubsonicResponse(
    @SerialName("subsonic-response") val response: Payload = Payload()
)

@Serializable
data class Payload(
    val status: String = "",
    val version: String = "",
    val error: SubsonicError? = null,
    val artists: ArtistsContainer? = null,
    val artist: ArtistDetail? = null,
    val album: AlbumDetail? = null,
    val albumList: AlbumListContainer? = null,
    @SerialName("albumList2") val albumList2: AlbumListContainer? = null,
    val playlists: PlaylistsContainer? = null,
    val playlist: PlaylistDetail? = null,
    val searchResult3: SearchResult3? = null,
    val randomSongs: RandomSongs? = null,
    val starred: Starred? = null,
    val user: SubsonicUser? = null,
    // Phase 4: library smarts
    val lyrics: LyricsResult? = null,
    val artistInfo2: ArtistInfo2? = null,
    val internetRadioStations: InternetRadioStations? = null,
    val bookmarks: BookmarksContainer? = null,
    val similarSongs2: SimilarSongs2? = null,
    val songsByGenre: SongsByGenre? = null,
    val genres: Genres? = null,
    val playQueue: PlayQueueState? = null
)

@Serializable
data class SubsonicError(val code: Int = 0, val message: String = "")

@Serializable
data class ArtistsContainer(val index: List<ArtistIndex> = emptyList())

@Serializable
data class ArtistIndex(val name: String = "", val artist: List<Artist> = emptyList())

@Serializable
data class Artist(
    val id: String = "",
    val name: String = "",
    val albumCount: Int = 0,
    val coverArt: String? = null
)

@Serializable
data class ArtistDetail(
    val id: String = "",
    val name: String = "",
    val albumCount: Int = 0,
    val album: List<Album> = emptyList()
)

@Serializable
data class Album(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null
)

@Serializable
data class AlbumDetail(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    val song: List<Song> = emptyList()
)

@Serializable
data class Song(
    val id: String = "",
    val title: String = "",
    val album: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val bitRate: Int? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val playCount: Long = 0,
    val starred: String? = null
)

@Serializable
data class AlbumListContainer(val album: List<Album> = emptyList())

@Serializable
data class PlaylistsContainer(val playlist: List<Playlist> = emptyList())

@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null
)

@Serializable
data class PlaylistDetail(
    val id: String = "",
    val name: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val entry: List<Song> = emptyList()
)

@Serializable
data class SearchResult3(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList()
)

@Serializable
data class RandomSongs(val song: List<Song> = emptyList())

@Serializable
data class Starred(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList()
)

@Serializable
data class SubsonicUser(val username: String = "")

// ---- Phase 4: library smarts ----

/** getLyrics.view: Subsonic puts the lyric text in the element's text content,
 *  which its JSON mapping exposes as "value". */
@Serializable
data class LyricsResult(
    val artist: String? = null,
    val title: String? = null,
    val value: String? = null
)

/** getArtistInfo2.view. Only populated when the server has an external
 *  metadata integration (e.g. Last.fm on Navidrome). */
@Serializable
data class ArtistInfo2(
    val biography: String? = null,
    val musicBrainzId: String? = null,
    val lastFmUrl: String? = null,
    val smallImageUrl: String? = null,
    val mediumImageUrl: String? = null,
    val largeImageUrl: String? = null,
    val similarArtist: List<Artist> = emptyList()
)

@Serializable
data class InternetRadioStations(
    val internetRadioStation: List<InternetRadioStation> = emptyList()
)

@Serializable
data class InternetRadioStation(
    val id: String = "",
    val name: String = "",
    val streamUrl: String = "",
    val homePageUrl: String? = null
)

@Serializable
data class BookmarksContainer(val bookmark: List<BookmarkEntry> = emptyList())

/** A server-side resume point / user bookmark on a media item. */
@Serializable
data class BookmarkEntry(
    val position: Long = 0,
    val comment: String? = null,
    val created: String? = null,
    val changed: String? = null,
    val entry: Song? = null
)

@Serializable
data class SimilarSongs2(val song: List<Song> = emptyList())

@Serializable
data class SongsByGenre(val song: List<Song> = emptyList())

@Serializable
data class Genres(val genre: List<GenreEntry> = emptyList())

@Serializable
data class GenreEntry(
    val name: String = "",
    val songCount: Int = 0,
    val albumCount: Int = 0
)

@Serializable
data class PlayQueueState(
    val current: String? = null,
    val position: Long = 0,
    val entry: List<Song> = emptyList()
)
