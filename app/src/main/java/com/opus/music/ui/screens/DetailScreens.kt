package com.opus.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.opus.music.Graph
import com.opus.music.data.toSong
import com.opus.music.network.Album
import com.opus.music.network.Artist
import com.opus.music.player.PlayerManager
import com.opus.music.ui.CoverArt
import com.opus.music.ui.EmptyBox
import com.opus.music.ui.ErrorBox
import com.opus.music.ui.LoadingBox
import com.opus.music.ui.Routes
import com.opus.music.ui.SectionHeader
import com.opus.music.Session
import com.opus.music.ui.SongRow
import com.opus.music.ui.formatDuration
import com.opus.music.ui.vm.DetailViewModel
import com.opus.music.ui.vm.LoadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(title: String, nav: NavController) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(id: String, nav: NavController) {
    val vm: DetailViewModel = viewModel()
    LaunchedEffect(id) { vm.loadArtist(id) }

    Scaffold(topBar = { DetailTopBar("Artist", nav) }) { padding ->
        when (val s = vm.artist) {
            is LoadState.Loading -> LoadingBox()
            is LoadState.Err -> ErrorBox(s.message) { vm.loadArtist(id) }
            is LoadState.Ok -> {
                val artist = s.data
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverArt(Session.client?.coverArtUrl(artist.album.firstOrNull()?.coverArt, 400), 120.dp, 16.dp)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(artist.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${artist.albumCount} albums",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (artist.album.isEmpty()) {
                        item { EmptyBox("No albums found for this artist.") }
                    } else {
                        item { ArtistBioSection(id, nav) }
                        item { ArtistTopSongs(artist.name, nav) }
                        items(artist.album) { album ->
                            AlbumListRow(album) { nav.navigate(Routes.album(album.id)) }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/**
 * "Your top songs" for this artist, from on-device listening stats. Free to
 * compute (no extra server calls) and personal: it only appears once the
 * user has actually played this artist's songs.
 */
@Composable
private fun ArtistTopSongs(artistName: String, nav: NavController) {
    val songs = remember(artistName) {
        Graph.stats.topPlayed(300)
            .map { it.meta }
            .filter { it.artist.equals(artistName, ignoreCase = true) }
            .take(5)
            .map { it.toSong() }
    }
    if (songs.isEmpty()) return
    Column(Modifier.padding(vertical = 4.dp)) {
        SectionHeader("Your top songs")
        songs.forEachIndexed { index, song ->
            SongRow(
                song = song,
                onClick = { PlayerManager.playSongs(songs, index, Graph.downloads) },
                onGoAlbum = song.albumId?.let { aid -> { nav.navigate(Routes.album(aid)) } }
            )
        }
    }
}

@Composable
private fun AlbumListRow(album: Album, onClick: () -> Unit) {    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(Session.client?.coverArtUrl(album.coverArt, 200), 56.dp, 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(album.year?.toString(), "${album.songCount} songs").joinToString(" • "),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(id: String, nav: NavController) {
    val vm: DetailViewModel = viewModel()
    LaunchedEffect(id) { vm.loadAlbum(id) }

    Scaffold(topBar = { DetailTopBar("Album", nav) }) { padding ->
        when (val s = vm.album) {
            is LoadState.Loading -> LoadingBox()
            is LoadState.Err -> ErrorBox(s.message) { vm.loadAlbum(id) }
            is LoadState.Ok -> {
                val album = s.data
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CoverArt(Session.client?.coverArtUrl(album.coverArt, 600), 220.dp, 16.dp)
                            Spacer(Modifier.height(16.dp))
                            Text(album.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                album.artist,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = album.artistId?.let {
                                    Modifier.clickable { nav.navigate(Routes.artist(it)) }
                                } ?: Modifier
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(
                                    album.year?.toString(),
                                    "${album.songCount} songs",
                                    formatDuration(album.duration)
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = {
                                    PlayerManager.playSongs(album.song, 0, Graph.downloads)
                                }) {
                                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.padding(end = 8.dp))
                                    Text("Play")
                                }
                                FilledTonalButton(onClick = {
                                    val shuffled = album.song.shuffled()
                                    PlayerManager.playSongs(shuffled, 0, Graph.downloads)
                                }) {
                                    Icon(Icons.Filled.Shuffle, null, modifier = Modifier.padding(end = 8.dp))
                                    Text("Shuffle")
                                }
                            }
                        }
                    }
                    itemsIndexed(album.song) { index, song ->
                        SongRow(
                            song = song,
                            onClick = { PlayerManager.playSongs(album.song, index, Graph.downloads) },
                            onGoArtist = song.artistId?.let { aid -> { nav.navigate(Routes.artist(aid)) } }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(id: String, nav: NavController) {
    val vm: DetailViewModel = viewModel()
    LaunchedEffect(id) { vm.loadPlaylist(id) }

    Scaffold(topBar = { DetailTopBar("Playlist", nav) }) { padding ->
        when (val s = vm.playlist) {
            is LoadState.Loading -> LoadingBox()
            is LoadState.Err -> ErrorBox(s.message) { vm.loadPlaylist(id) }
            is LoadState.Ok -> {
                val playlist = s.data
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverArt(Session.client?.coverArtUrl(playlist.coverArt, 400), 120.dp, 16.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(playlist.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${playlist.songCount} songs • ${formatDuration(playlist.duration)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = {
                                        PlayerManager.playSongs(playlist.entry, 0, Graph.downloads)
                                    }) {
                                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.padding(end = 8.dp))
                                        Text("Play")
                                    }
                                    FilledTonalButton(onClick = {
                                        PlayerManager.playSongs(playlist.entry.shuffled(), 0, Graph.downloads)
                                    }) {
                                        Icon(Icons.Filled.Shuffle, null, modifier = Modifier.padding(end = 8.dp))
                                        Text("Shuffle")
                                    }
                                }
                            }
                        }
                    }
                    if (playlist.entry.isEmpty()) {
                        item { EmptyBox("This playlist is empty.") }
                    } else {
                        itemsIndexed(playlist.entry) { index, song ->
                            SongRow(
                                song = song,
                                onClick = { PlayerManager.playSongs(playlist.entry, index, Graph.downloads) },
                                onGoAlbum = song.albumId?.let { aid -> { nav.navigate(Routes.album(aid)) } },
                                onGoArtist = song.artistId?.let { aid -> { nav.navigate(Routes.artist(aid)) } }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/**
 * Artist biography + similar artists (getArtistInfo2). Only shown when the
 * server actually returns a bio (Navidrome needs its Last.fm integration).
 */
@Composable
private fun ArtistBioSection(artistId: String, nav: NavController) {
    var bio by remember(artistId) { mutableStateOf<String?>(null) }
    var similar by remember(artistId) { mutableStateOf<List<Artist>>(emptyList()) }
    var expanded by remember(artistId) { mutableStateOf(false) }
    var loaded by remember(artistId) { mutableStateOf(false) }

    LaunchedEffect(artistId) {
        try {
            val info = withContext(Dispatchers.IO) {
                Session.client?.api()?.getArtistInfo2(artistId)?.response?.artistInfo2
            }
            val raw = info?.biography?.trim().orEmpty()
            // Last.fm bios end with "Read more on Last.fm" boilerplate.
            bio = raw.substringBefore("Read more on Last.fm").trim()
                .takeIf { it.length > 40 }
            similar = info?.similarArtist.orEmpty().take(10)
        } catch (_: Exception) { }
        loaded = true
    }

    if (!loaded) return
    if (bio.isNullOrBlank() && similar.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        if (!bio.isNullOrBlank()) {
            Text(
                "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                bio!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded }
            )
            Text(
                if (expanded) "Show less" else "Read more",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { expanded = !expanded }
                    .padding(vertical = 4.dp)
            )
        }
        if (similar.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Similar artists",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                similar.take(5).forEach { a ->
                    Column(
                        Modifier.weight(1f).clickable { nav.navigate(Routes.artist(a.id)) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CoverArt(
                            Session.client?.coverArtUrl(a.coverArt, 200),
                            64.dp, 32.dp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            a.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
