package com.opus.music.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navArgument
import com.opus.music.Graph
import com.opus.music.Session
import com.opus.music.data.OfflineMix
import com.opus.music.ui.screens.AlbumScreen
import com.opus.music.ui.screens.ArtistScreen
import com.opus.music.ui.screens.DownloadsScreen
import com.opus.music.ui.screens.GenresScreen
import com.opus.music.ui.screens.HomeScreen
import com.opus.music.ui.screens.LibraryScreen
import com.opus.music.ui.screens.MixesScreen
import com.opus.music.ui.screens.NowPlayingScreen
import com.opus.music.ui.screens.PartyScreen
import com.opus.music.ui.screens.PlaylistScreen
import com.opus.music.ui.screens.RadioScreen
import com.opus.music.ui.screens.SearchScreen
import com.opus.music.ui.screens.SettingsScreen
import com.opus.music.ui.screens.SetupScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object Routes {
    const val SETUP = "setup"
    const val MAIN = "main"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val DOWNLOADS = "downloads"
    const val NOW_PLAYING = "nowplaying"
    const val SETTINGS = "settings"
    const val PARTY = "party"
    const val MIXES = "mixes"
    const val RADIO = "radio"
    const val GENRES = "genres"

    fun artist(id: String) = "artist/$id"
    fun album(id: String) = "album/$id"
    fun playlist(id: String) = "playlist/$id"
}

@Composable
fun Nav() {
    val nav = rememberNavController()
    var ready by remember { mutableStateOf(false) }
    val config by Graph.settings.configFlow.collectAsState(initial = null)
    val appCtx = LocalContext.current.applicationContext
    // Guards the one-per-process automatic mix sync (the effect below can
    // re-run when `config` resolves from its initial null).
    var mixKicked by remember { mutableStateOf(false) }

    LaunchedEffect(config) {
        Graph.settings.ensureInit()
        val restored = Graph.settings.configFlow.first()
        // Restore the network session from saved credentials; otherwise the
        // main screen would load with Session.music == null.
        if (restored != null && Session.music == null) {
            Session.open(restored)
        }
        ready = true
        // Smart Offline Mix: refresh the offline mix in the background once
        // the session exists (covers both app start with saved login and a
        // fresh login, which re-triggers this effect via `config`).
        // Runs at most once every 20h, Wi-Fi/unmetered only.
        if (restored != null && Session.music != null && !mixKicked) {
            mixKicked = true
            CoroutineScope(Dispatchers.IO).launch {
                try { OfflineMix.syncIfNeeded(appCtx) } catch (_: Exception) { }
            }
        }
    }

    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Mini player lives at root level so it shows above every screen
    // (home, library, artist/album details, settings).
    // Hidden on the setup screen where there is no session yet, and hidden
    // on the Now Playing screen itself (tapping the mini player expands to
    // full player, so showing both is redundant).
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = if (config == null) Routes.SETUP else Routes.MAIN) {
                composable(Routes.SETUP) {
                    SetupScreen(onDone = {
                        nav.navigate(Routes.MAIN) { popUpTo(Routes.SETUP) { inclusive = true } }
                    })
                }
                composable(Routes.MAIN) { MainScreen(nav) }
                composable(
                    "artist/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType })
                ) { ArtistScreen(id = it.arguments!!.getString("id")!!, nav = nav) }
                composable(
                    "album/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType })
                ) { AlbumScreen(id = it.arguments!!.getString("id")!!, nav = nav) }
                composable(
                    "playlist/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType })
                ) { PlaylistScreen(id = it.arguments!!.getString("id")!!, nav = nav) }
                // Full player as a full-screen dialog: the route underneath
                // (home, library, artist...) stays composed and visible, so a
                // swipe-down collapse reveals the real screen behind instead
                // of a blank surface. The player paints its own background
                // and handles its own enter/dismiss animations.
                dialog(
                    route = Routes.NOW_PLAYING,
                    dialogProperties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        dismissOnClickOutside = false
                    )
                ) { NowPlayingScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                // Deep link into a specific settings section, e.g. the Now
                // Playing speaker button jumps to "settings/speakers".
                composable(
                    "settings/{section}",
                    arguments = listOf(navArgument("section") { type = NavType.StringType })
                ) { backStackEntry ->
                    SettingsScreen(nav, initialSection = backStackEntry.arguments?.getString("section"))
                }
                composable(Routes.PARTY) { PartyScreen(nav) }
                composable(Routes.MIXES) { MixesScreen(nav) }
                composable(Routes.RADIO) { RadioScreen(nav) }
                composable(Routes.GENRES) { GenresScreen(nav) }
            }
        }
        // The mini player bounces in with a small spring when the full player
        // collapses onto it, and slides away when the full player opens.
        AnimatedVisibility(
            visible = config != null && currentRoute != Routes.NOW_PLAYING,
            enter = slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it / 2 },
                animationSpec = tween(200)
            ) + fadeOut(animationSpec = tween(200))
        ) {
            MiniPlayer(onTap = { nav.navigate(Routes.NOW_PLAYING) })
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun MainScreen(rootNav: NavController) {
    val tabs = listOf(
        Tab(Routes.HOME, "Home", Icons.Filled.Home),
        Tab(Routes.LIBRARY, "Library", Icons.Filled.LibraryMusic),
        Tab(Routes.SEARCH, "Search", Icons.Filled.Search),
        Tab(Routes.DOWNLOADS, "Offline", Icons.Filled.Download),
    )
    val nav = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val dest = nav.currentBackStackEntryAsState().value?.destination
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = dest?.route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.startDestinationId)
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) { HomeScreen(rootNav) }
            composable(Routes.LIBRARY) { LibraryScreen(rootNav) }
            composable(Routes.SEARCH) { SearchScreen(rootNav) }
            composable(Routes.DOWNLOADS) { DownloadsScreen(rootNav) }
        }
    }
}
