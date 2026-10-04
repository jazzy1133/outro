package com.opus.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.opus.music.Graph
import com.opus.music.network.Album
import com.opus.music.network.Artist
import com.opus.music.network.Playlist
import com.opus.music.network.Song
import com.opus.music.player.PlayerManager
import com.opus.music.ui.AlbumCard
import com.opus.music.ui.ArtistRow
import com.opus.music.ui.EmptyBox
import com.opus.music.ui.ErrorBox
import com.opus.music.ui.LoadingBox
import com.opus.music.ui.PlaylistRow
import com.opus.music.ui.Routes
import com.opus.music.ui.SongRow
import com.opus.music.ui.vm.LibraryViewModel
import com.opus.music.ui.vm.LoadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: NavController) {
    val vm: LibraryViewModel = viewModel()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Artists", "Albums", "Playlists", "Loved")

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Your Library", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall) })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Smart mixes + radio shortcuts.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LibraryShortcut(
                    title = "Mixes",
                    subtitle = "Made for you",
                    icon = Icons.Filled.AutoAwesome,
                    modifier = Modifier.weight(1f),
                    onClick = { nav.navigate(Routes.MIXES) }
                )
                LibraryShortcut(
                    title = "Radio",
                    subtitle = "Internet stations",
                    icon = Icons.Filled.Radio,
                    modifier = Modifier.weight(1f),
                    onClick = { nav.navigate(Routes.RADIO) }
                )
                LibraryShortcut(
                    title = "Genres",
                    subtitle = "Browse by style",
                    icon = Icons.Filled.Category,
                    modifier = Modifier.weight(1f),
                    onClick = { nav.navigate(Routes.GENRES) }
                )
            }
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            when (tab) {
                0 -> LibraryList(vm.artists, onRetry = vm::refresh,
                    empty = "No artists found.",
                    content = { artists: List<Artist> ->
                        LazyColumn { items(artists) { ArtistRow(it) { nav.navigate(Routes.artist(it.id)) } } }
                    })
                1 -> LibraryList(vm.albums, onRetry = vm::refresh,
                    empty = "No albums found.",
                    content = { albums: List<Album> ->
                        LazyVerticalGrid(
                            GridCells.Fixed(2),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            items(albums) { AlbumCard(it, onClick = { nav.navigate(Routes.album(it.id)) }) }
                        }
                    })
                2 -> LibraryList(vm.playlists, onRetry = vm::refresh,
                    empty = "No playlists yet.",
                    content = { playlists: List<Playlist> ->
                        LazyColumn { items(playlists) { PlaylistRow(it) { nav.navigate(Routes.playlist(it.id)) } } }
                    })
                3 -> LibraryList(vm.loved, onRetry = vm::refresh,
                    empty = "Tap the heart on anything to build your Loved list.",
                    content = { songs: List<Song> ->
                        LazyColumn {
                            items(songs) { song ->
                                SongRow(
                                    song = song,
                                    onClick = { PlayerManager.playSongs(songs, songs.indexOf(song), Graph.downloads) },
                                    onGoAlbum = song.albumId?.let { id -> { nav.navigate(Routes.album(id)) } },
                                    onGoArtist = song.artistId?.let { id -> { nav.navigate(Routes.artist(id)) } }
                                )
                            }
                        }
                    })
            }
        }
    }
}

@Composable
private fun <T> LibraryList(
    state: LoadState<T>,
    onRetry: () -> Unit,
    empty: String,
    content: @Composable (T) -> Unit
) {
    when (state) {
        is LoadState.Loading -> LoadingBox()
        is LoadState.Err -> ErrorBox(state.message, onRetry)
        is LoadState.Ok -> {
            val emptyList = when (val d = state.data) {
                is List<*> -> d.isEmpty()
                else -> false
            }
            if (emptyList) EmptyBox(empty) else content(state.data)
        }
    }
}

@Composable
private fun LibraryShortcut(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    androidx.compose.material3.Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}
