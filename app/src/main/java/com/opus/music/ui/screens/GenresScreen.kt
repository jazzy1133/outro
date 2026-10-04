package com.opus.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.opus.music.Graph
import com.opus.music.Session
import com.opus.music.network.GenreEntry
import com.opus.music.network.Song
import com.opus.music.player.PlayerManager
import com.opus.music.ui.Routes
import com.opus.music.ui.SongRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Genre browser: list genres, then songs in a genre. Built on
 * getGenres / getSongsByGenre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenresScreen(nav: NavController) {
    var genres by remember { mutableStateOf<List<GenreEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<GenreEntry?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingSongs by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        genres = withContext(Dispatchers.IO) {
            try {
                Session.client?.api()?.getGenres()?.response?.genres?.genre
                    .orEmpty().filter { it.name.isNotBlank() }
                    .sortedByDescending { it.songCount }
            } catch (_: Exception) { emptyList() }
        }
        loading = false
    }

    LaunchedEffect(selected) {
        val g = selected ?: return@LaunchedEffect
        loadingSongs = true
        songs = withContext(Dispatchers.IO) {
            try {
                Session.client?.api()?.getSongsByGenre(g.name, count = 200)
                    ?.response?.songsByGenre?.song.orEmpty()
            } catch (_: Exception) { emptyList() }
        }
        loadingSongs = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected == null) "Genres" else selected!!.name) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected != null) selected = null else nav.popBackStack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (selected != null && songs.isNotEmpty()) {
                        IconButton(onClick = {
                            PlayerManager.playSongs(songs.shuffled(), 0, Graph.downloads)
                        }) {
                            Icon(Icons.Filled.PlayArrow, "Shuffle play genre")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (selected == null) {
            when {
                loading -> {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                genres.isEmpty() -> {
                    Box(Modifier.fillMaxSize().padding(padding).padding(32.dp),
                        contentAlignment = Alignment.Center) {
                        Text("No genres found on your server.")
                    }
                }
                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item { Spacer(Modifier.height(4.dp)) }
                        items(genres, key = { it.name }) { g ->
                            Card(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { selected = g },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.Category, null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(g.name, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${g.songCount} songs • ${g.albumCount} albums",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        } else {
            if (loadingSongs) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(songs, key = { it.id }) { song ->
                        SongRow(
                            song = song,
                            onClick = {
                                PlayerManager.playSongs(songs, songs.indexOf(song), Graph.downloads)
                            },
                            onGoAlbum = song.albumId?.let { id -> { nav.navigate(Routes.album(id)) } },
                            onGoArtist = song.artistId?.let { id -> { nav.navigate(Routes.artist(id)) } }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
