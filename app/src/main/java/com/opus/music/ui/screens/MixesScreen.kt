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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.TrendingUp
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.opus.music.Graph
import com.opus.music.Session
import com.opus.music.data.SongMeta
import com.opus.music.mixes.SmartMixes
import com.opus.music.network.Song
import com.opus.music.player.PlayerManager
import com.opus.music.ui.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class MixDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

private val MIXES = listOf(
    MixDef("heavy", "Heavy Rotation", "Your most-played tracks", Icons.Filled.TrendingUp),
    MixDef("jumpback", "Jump Back In", "Recently played", Icons.Filled.History),
    MixDef("forgotten", "Forgotten Favorites", "Starred songs you rarely play", Icons.Filled.Refresh),
    MixDef("daily", "Daily Mix", "A fresh shuffle of your favorites, daily", Icons.Filled.Shuffle)
)

/**
 * Smart mixes built from local listening stats: Heavy Rotation, Jump Back In,
 * Forgotten Favorites, and Daily Mix. Tapping a mix plays it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixesScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var starredIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mixSongs by remember { mutableStateOf<Map<String, List<SongMeta>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var playingMix by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        // Compute off the main thread, then publish to Compose state on Main.
        val starred: Set<String>
        val mixes: Map<String, List<SongMeta>>
        withContext(Dispatchers.IO) {
            val stats = Graph.stats
            val candidates = stats.topPlayed(500)
            starred = try {
                Session.client?.api()?.getStarred()?.response?.starred?.song
                    ?.map { it.id }?.toSet().orEmpty()
            } catch (_: Exception) { emptySet() }
            mixes = mapOf(
                "heavy" to SmartMixes.heavyRotation(candidates, 50),
                "jumpback" to stats.recentlyPlayed(50),
                "forgotten" to SmartMixes.forgottenFavorites(candidates, starred, 50),
                "daily" to SmartMixes.dailyMix(candidates, starred, 50)
            )
        }
        starredIds = starred
        mixSongs = mixes
        loading = false
    }

    fun playMix(id: String) {
        val metas = mixSongs[id].orEmpty()
        if (metas.isEmpty()) return
        playingMix = id
        scope.launch(Dispatchers.IO) {
            // Resolve full Song objects for playback (need stream ids + metadata).
            val api = Session.client?.api()
            val songs = ArrayList<Song>()
            if (api != null) {
                for (m in metas) {
                    try {
                        val res = api.search3(m.title + " " + (m.artist ?: ""), songCount = 5)
                        val hits = res.response.searchResult3?.song.orEmpty()
                        // Prefer the exact id, then a title+artist match. Never
                        // take a blind first hit — a wrong song is worse than
                        // a stub built from cached metadata.
                        val hit = hits.firstOrNull { it.id == m.id }
                            ?: hits.firstOrNull {
                                it.title.equals(m.title, ignoreCase = true) &&
                                    (m.artist == null ||
                                        it.artist.equals(m.artist, ignoreCase = true))
                            }
                        if (hit != null) songs.add(hit)
                    } catch (_: Exception) { }
                    if (songs.size >= 50) break
                }
            }
            // Fallback: build minimal Song stubs from cached metadata.
            if (songs.isEmpty()) {
                for (m in metas) songs.add(
                    Song(id = m.id, title = m.title, artist = m.artist,
                        album = m.album, coverArt = m.coverArt,
                        duration = m.duration, suffix = m.suffix)
                )
            }
            withContext(Dispatchers.Main) {
                try {
                    PlayerManager.playSongs(songs, 0, Graph.downloads)
                } catch (_: Exception) { }
                playingMix = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mixes") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(MIXES) { mix ->
                val songs = mixSongs[mix.id].orEmpty()
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = songs.isNotEmpty()) { playMix(mix.id) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            mix.icon, null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mix.title, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium)
                            Text(mix.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (songs.isEmpty()) "Not enough listening history yet"
                                else "${songs.size} songs",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (playingMix == mix.id) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Filled.PlayArrow, "Play mix",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
