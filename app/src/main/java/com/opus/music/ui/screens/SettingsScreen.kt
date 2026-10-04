package com.opus.music.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.opus.music.Graph
import com.opus.music.Session
import com.opus.music.data.Account
import com.opus.music.data.MusicRepository
import com.opus.music.data.ServerConfig
import com.opus.music.data.SettingsRepository
import com.opus.music.data.SubsonicException
import com.opus.music.data.SwipeActions
import com.opus.music.network.SubsonicClient
import com.opus.music.player.EqualizerEngine
import kotlin.math.roundToInt
import com.opus.music.player.PlayerManager
import com.opus.music.ui.Routes
import com.opus.music.ui.theme.ThemeController
import kotlinx.coroutines.launch

private val BITRATE_OPTIONS = listOf(
    0 to "Original (no transcoding)",
    320 to "320 kbps",
    192 to "192 kbps",
    128 to "128 kbps",
)

private val SLEEP_OPTIONS = listOf(
    null to "Off",
    15 to "15 minutes",
    30 to "30 minutes",
    60 to "1 hour",
    90 to "1.5 hours",
)

private val SCREEN_LOCK_OPTIONS = listOf(
    "never" to "Never",
    "playing" to "When Playing",
    "always" to "Always",
)

private val CROSSFADE_OPTIONS = listOf(
    0 to "Off",
    2 to "2 seconds",
    4 to "4 seconds",
    6 to "6 seconds",
    8 to "8 seconds",
    12 to "12 seconds",
)

private val SLEEP_FADE_OPTIONS = listOf(1, 3, 5, 10)

private val MIX_SIZE_OPTIONS = listOf(25, 50, 100, SettingsRepository.MIX_UNLIMITED)

private fun mixSizeLabel(n: Int): String =
    if (n == SettingsRepository.MIX_UNLIMITED) "∞" else "$n"

private fun bitrateLabel(bitrate: Int): String =
    BITRATE_OPTIONS.firstOrNull { it.first == bitrate }?.second ?: "Original (no transcoding)"

private fun screenLockLabel(value: String): String =
    SCREEN_LOCK_OPTIONS.firstOrNull { it.first == value }?.second ?: "Never"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController, initialSection: String? = null) {
    var section by remember { mutableStateOf(initialSection) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (section) {
                            "account" -> "Account"
                            "display" -> "Display & Interaction"
                            "library" -> "Library"
                            "player" -> "Player, Stream & Scrobble"
                            "equalizer" -> "Equalizer"
                            "sound" -> "Sound"
                            "speakers" -> "Speakers"
                            "swipe" -> "Swipe"
                            "artwork" -> "Artwork"
                            "support" -> "Support"
                            "license" -> "License"
                            "about" -> "About"
                            else -> "Settings"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (section != null) section = null else nav.popBackStack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            when (section) {
                null -> SettingsMainList(onOpen = { section = it })
                "account" -> AccountSection(nav)
                "display" -> DisplaySection()
                "library" -> LibrarySection()
                "player" -> PlayerSection()
                "equalizer" -> EqualizerSection()
                "sound" -> SoundSection()
                "speakers" -> SpeakersSection()
                "swipe" -> SwipeSection()
                "artwork" -> ArtworkSection()
                "support" -> SupportSection()
                "license" -> LicenseSection()
                "about" -> AboutSection()
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 4.dp)
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(0.dp))
        } else {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SettingsMainList(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var screenLock by remember { mutableStateOf(Graph.settings.getPreventScreenLock()) }
    var showScreenLockDialog by remember { mutableStateOf(false) }

    // Top standalone card: Prevent Screen Lock (Amperfy-style)
    SettingsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { showScreenLockDialog = true }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Prevent Screen Lock",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                screenLockLabel(screenLock),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    // Main group
    SettingsCard {
        SettingsRow("Account") { onOpen("account") }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SettingsRow("Display & Interaction") { onOpen("display") }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SettingsRow("Library") { onOpen("library") }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SettingsRow("Player, Stream & Scrobble") { onOpen("player") }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SettingsRow("Equalizer") { onOpen("equalizer") }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SettingsRow("Sound") { onOpen("sound") }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SettingsRow("Speakers") { onOpen("speakers") }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SettingsRow("Swipe") { onOpen("swipe") }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SettingsRow("Artwork") { onOpen("artwork") }
    }

    Spacer(Modifier.height(16.dp))

    // Support group
    SettingsCard {
        SettingsRow("Support") { onOpen("support") }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SettingsRow("License") { onOpen("license") }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SettingsRow("About") { onOpen("about") }
    }

    if (showScreenLockDialog) {
        AlertDialog(
            onDismissRequest = { showScreenLockDialog = false },
            title = { Text("Prevent Screen Lock") },
            text = {
                Column {
                    SCREEN_LOCK_OPTIONS.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                screenLock = value
                                Graph.settings.setPreventScreenLock(value)
                                // Apply immediately
                                (context as? Activity)?.window?.let { win ->
                                    if (value == "always") {
                                        win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                    } else {
                                        win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                    }
                                }
                                showScreenLockDialog = false
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = screenLock == value, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "\"When Playing\" keeps the screen on while the Now Playing screen is visible.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showScreenLockDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun AccountSection(nav: NavController) {
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(Graph.settings.getAccounts()) }
    var activeId by remember { mutableStateOf(Graph.settings.getActiveAccount()?.id) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<Account?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        accounts = Graph.settings.getAccounts()
        activeId = Graph.settings.getActiveAccount()?.id
    }

    /** Swap the live session to another saved account, then reload the library. */
    fun activate(acc: Account) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                PlayerManager.pause()
                Graph.settings.setActiveAccountId(acc.id)
                Session.open(acc.toConfig())
            } catch (_: Exception) {
            }
            refresh()
            busy = false
            nav.navigate(Routes.MAIN) { popUpTo(Routes.MAIN) { inclusive = true } }
        }
    }

    fun removeAccount(acc: Account) {
        scope.launch {
            try {
                PlayerManager.pause()
                val next = Graph.settings.removeAccount(acc.id)
                if (next == null) {
                    Session.close()
                    nav.navigate(Routes.SETUP) { popUpTo(Routes.MAIN) { inclusive = true } }
                } else {
                    Session.open(next.toConfig())
                    nav.navigate(Routes.MAIN) { popUpTo(Routes.MAIN) { inclusive = true } }
                }
            } catch (_: Exception) {
            }
            refresh()
        }
    }

    SettingsCard {
        if (accounts.isEmpty()) {
            ListItem(headlineContent = { Text("No accounts yet") })
        } else {
            accounts.forEachIndexed { i, acc ->
                val selected = acc.id == activeId
                ListItem(
                    headlineContent = { Text(acc.label) },
                    supportingContent = { Text("${acc.username} • ${acc.baseUrl}") },
                    leadingContent = {
                        RadioButton(selected = selected, onClick = { if (!selected) activate(acc) })
                    },
                    trailingContent = {
                        IconButton(onClick = { confirmRemove = acc }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove account")
                        }
                    },
                    modifier = Modifier.clickable(enabled = !selected && !busy) { activate(acc) }
                )
                if (i < accounts.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = { showAdd = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text("Add account")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            scope.launch {
                Graph.settings.clear()
                Session.close()
                PlayerManager.setSleepTimer(null)
                nav.navigate(Routes.SETUP) {
                    popUpTo(Routes.MAIN) { inclusive = true }
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Log out")
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Switching accounts keeps your downloads, settings and playback tweaks. Logging out removes all saved accounts from this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (showAdd) {
        AddAccountDialog(
            onDismiss = { showAdd = false },
            onAdded = {
                refresh()
                nav.navigate(Routes.MAIN) { popUpTo(Routes.MAIN) { inclusive = true } }
            }
        )
    }

    confirmRemove?.let { acc ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove account?") },
            text = { Text("Remove \"${acc.label}\" from this device? Your downloads stay on the device.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = null; removeAccount(acc) }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!testing) onDismiss() },
        title = { Text("Add account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Name (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Server URL") }, singleLine = true,
                    placeholder = { Text("https://music.example.com") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Username") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (testing) return@TextButton
                    val u = url.trim()
                    if (u.isBlank() || username.isBlank() || password.isEmpty()) {
                        error = "Please fill in server URL, username and password."
                        return@TextButton
                    }
                    testing = true
                    error = null
                    scope.launch {
                        try {
                            val repo = MusicRepository(SubsonicClient(ServerConfig(u, username.trim(), password)))
                            repo.ping()
                            repo.getUser(username.trim())
                            val acc = Graph.settings.addAccount(label.trim(), u, username.trim(), password)
                            Session.open(acc.toConfig())
                            onAdded()
                        } catch (e: SubsonicException) {
                            error = e.message
                        } catch (e: Exception) {
                            error = "Could not reach server: ${e.message}"
                        } finally {
                            testing = false
                        }
                    }
                },
                enabled = !testing
            ) { Text(if (testing) "Checking…" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !testing) { Text("Cancel") }
        }
    )
}

@Composable
private fun DisplaySection() {
    var themeMode by remember { mutableStateOf(Graph.settings.getThemeMode()) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var screenLock by remember { mutableStateOf(Graph.settings.getPreventScreenLock()) }
    var dynamicColor by remember { mutableStateOf(Graph.settings.isDynamicColor()) }
    var playerLayout by remember { mutableStateOf(Graph.settings.getPlayerLayout()) }
    var showLayoutDialog by remember { mutableStateOf(false) }
    val dynamicSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().clickable { showThemeDialog = true }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                Text(
                    ThemeController.label(themeMode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        if (dynamicSupported) {
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ListItem(
                headlineContent = { Text("Dynamic color") },
                supportingContent = { Text("Match colors to your wallpaper (Material You)") },
                trailingContent = {
                    Switch(
                        checked = dynamicColor,
                        onCheckedChange = {
                            dynamicColor = it
                            Graph.settings.setDynamicColor(it)
                            ThemeController.setDynamic(it)
                        }
                    )
                }
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        ListItem(
            headlineContent = { Text("Prevent screen lock") },
            supportingContent = { Text(screenLockLabel(screenLock)) }
        )
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        Row(
            Modifier.fillMaxWidth().clickable { showLayoutDialog = true }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Player layout", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (playerLayout == "immersive") "Immersive" else "Classic",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Change \"Prevent Screen Lock\" from the main Settings list. \"When Playing\" keeps the display awake on the Now Playing screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Theme") },
            text = {
                Column {
                    listOf(
                        ThemeController.DARK to "Dark",
                        ThemeController.LIGHT to "Light"
                    ).forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                themeMode = value
                                Graph.settings.setThemeMode(value)
                                ThemeController.set(value)
                                showThemeDialog = false
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = themeMode == value, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Dark is the classic jazzy look. Light is a clean white theme.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Close") }
            }
        )
    }

    if (showLayoutDialog) {
        AlertDialog(
            onDismissRequest = { showLayoutDialog = false },
            title = { Text("Player layout") },
            text = {
                Column {
                    listOf(
                        "classic" to "Classic",
                        "immersive" to "Immersive"
                    ).forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                playerLayout = value
                                Graph.settings.setPlayerLayout(value)
                                showLayoutDialog = false
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = playerLayout == value, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(label)
                                Text(
                                    if (value == "immersive")
                                        "Full-width artwork with the title on top"
                                    else "Artwork card with details below",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLayoutDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun LibrarySection() {
    val scope = rememberCoroutineScope()
    val bytes by produceState(initialValue = 0L) {
        value = try { Graph.downloads.totalBytes() } catch (_: Exception) { 0L }
    }
    var clearedTick by remember { mutableStateOf(0) }
    var mixEnabled by remember { mutableStateOf(Graph.settings.isOfflineMixEnabled()) }
    var mixSize by remember { mutableStateOf(Graph.settings.getOfflineMixSize()) }
    var wifiOnly by remember { mutableStateOf(Graph.settings.isOfflineMixWifiOnly()) }
    var lastSync by remember { mutableStateOf(Graph.stats.getLastMixSync()) }
    var syncing by remember { mutableStateOf(false) }
    var syncMsg by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    val mb = bytes / (1024.0 * 1024.0)

    SettingsCard {
        ListItem(
            headlineContent = { Text("Offline cache") },
            supportingContent = { Text(String.format("%.1f MB downloaded", mb)) }
        )
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        ListItem(
            headlineContent = { Text("Smart Offline Mix") },
            supportingContent = { Text("Auto-download favorites & most-played on Wi-Fi") },
            trailingContent = {
                Switch(
                    checked = mixEnabled,
                    onCheckedChange = {
                        mixEnabled = it
                        Graph.settings.setOfflineMixEnabled(it)
                    }
                )
            }
        )
        if (mixEnabled) {
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ListItem(
                headlineContent = { Text("Mix size") },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MIX_SIZE_OPTIONS.forEach { n ->
                            OutlinedButton(
                                onClick = {
                                    mixSize = n
                                    Graph.settings.setOfflineMixSize(n)
                                },
                                colors = if (mixSize == n) ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ) else ButtonDefaults.outlinedButtonColors()
                            ) { Text(mixSizeLabel(n)) }
                        }
                    }
                }
            )
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ListItem(
                headlineContent = { Text("Wi-Fi only") },
                supportingContent = { Text("Never use mobile data for the mix") },
                trailingContent = {
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = {
                            wifiOnly = it
                            Graph.settings.setOfflineMixWifiOnly(it)
                        }
                    )
                }
            )
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            ListItem(
                headlineContent = { Text("Last synced") },
                supportingContent = {
                    Text(
                        if (lastSync == 0L) "Never"
                        else java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(lastSync))
                    )
                },
                trailingContent = {
                    OutlinedButton(
                        onClick = {
                            if (syncing) return@OutlinedButton
                            syncing = true
                            syncMsg = null
                            scope.launch {
                                val res = try {
                                    com.opus.music.data.OfflineMix.syncIfNeeded(ctx, force = true)
                                } catch (e: Exception) {
                                    com.opus.music.data.OfflineMix.SyncResult(0, 0, "error")
                                }
                                lastSync = try { Graph.stats.getLastMixSync() } catch (_: Exception) { lastSync }
                                syncMsg = when (res.message) {
                                    "ok" -> "Synced: ${res.downloaded} new song(s) downloaded."
                                    "disabled" -> "Turn the mix on first."
                                    "not logged in" -> "Log in first."
                                    "waiting for wi-fi" -> "Waiting for Wi-Fi."
                                    else -> "Sync: ${res.message}"
                                }
                                syncing = false
                            }
                        },
                        enabled = !syncing
                    ) { Text(if (syncing) "Syncing…" else "Sync now") }
                }
            )
        }
    }
    if (syncMsg != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            syncMsg!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = {
            scope.launch {
                try {
                    val list = Graph.downloads.list()
                    list.forEach { Graph.downloads.delete(it.id) }
                    clearedTick++
                } catch (_: Exception) {}
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Clear offline cache")
    }
    if (clearedTick > 0) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Cache cleared.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlayerSection() {
    var bitrate by remember { mutableStateOf(Graph.settings.getStreamBitrate()) }
    var scrobble by remember { mutableStateOf(Graph.settings.isScrobbleEnabled()) }
    var gapless by remember { mutableStateOf(Graph.settings.isGaplessEnabled()) }
    var crossfade by remember { mutableStateOf(Graph.settings.getCrossfadeSec()) }
    var showBitrateDialog by remember { mutableStateOf(false) }
    var showCrossfadeDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    val sleepEndsAt by PlayerManager.sleepEndsAt.collectAsState()
    val sleepMinutes by PlayerManager.sleepMinutes.collectAsState()

    SettingsCard {
        Row(
            Modifier.fillMaxWidth().clickable { showBitrateDialog = true }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Streaming quality", style = MaterialTheme.typography.bodyLarge)
                Text(
                    bitrateLabel(bitrate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        ListItem(
            headlineContent = { Text("Scrobble to server") },
            supportingContent = { Text("Report played tracks to Navidrome") },
            trailingContent = {
                Switch(
                    checked = scrobble,
                    onCheckedChange = {
                        scrobble = it
                        Graph.settings.setScrobbleEnabled(it)
                    }
                )
            }
        )
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        ListItem(
            headlineContent = { Text("Gapless playback") },
            supportingContent = { Text("Seamless transitions between tracks") },
            trailingContent = {
                Switch(
                    checked = gapless,
                    onCheckedChange = {
                        gapless = it
                        Graph.settings.setGaplessEnabled(it)
                    }
                )
            }
        )
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        Row(
            Modifier.fillMaxWidth().clickable { showCrossfadeDialog = true }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Crossfade", style = MaterialTheme.typography.bodyLarge)
                Text(
                    CROSSFADE_OPTIONS.firstOrNull { it.first == crossfade }?.second ?: "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        Row(
            Modifier.fillMaxWidth().clickable { showSleepDialog = true }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sleep timer", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (sleepEndsAt != null) {
                        val mins = ((sleepEndsAt!! - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
                        "Stops in ~$mins min"
                    } else "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }

    if (showBitrateDialog) {
        AlertDialog(
            onDismissRequest = { showBitrateDialog = false },
            title = { Text("Streaming quality") },
            text = {
                Column {
                    BITRATE_OPTIONS.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                bitrate = value
                                Graph.settings.setStreamBitrate(value)
                                showBitrateDialog = false
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = bitrate == value, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Applies to songs played next. Downloaded songs always play at original quality.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showBitrateDialog = false }) { Text("Close") }
            }
        )
    }

    if (showCrossfadeDialog) {
        AlertDialog(
            onDismissRequest = { showCrossfadeDialog = false },
            title = { Text("Crossfade") },
            text = {
                Column {
                    CROSSFADE_OPTIONS.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                crossfade = value
                                Graph.settings.setCrossfadeSec(value)
                                PlayerManager.refreshCrossfade()
                                showCrossfadeDialog = false
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = crossfade == value, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Songs blend into each other like a DJ set.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showCrossfadeDialog = false }) { Text("Close") }
            }
        )
    }

    if (showSleepDialog) {
        var pendingMinutes by remember { mutableStateOf(sleepMinutes ?: 30) }
        var fadeMin by remember { mutableStateOf(Graph.settings.getSleepFadeMin()) }
        var endOfTrack by remember { mutableStateOf(Graph.settings.isSleepEndOfTrack()) }
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            title = { Text("Sleep timer") },
            text = {
                Column {
                    Text("Stop after",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SLEEP_OPTIONS.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (value == null) {
                                    PlayerManager.setSleepTimer(null)
                                    showSleepDialog = false
                                } else pendingMinutes = value
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = value != null && pendingMinutes == value,
                                onClick = null
                            )
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Fade out over",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SLEEP_FADE_OPTIONS.forEach { m ->
                            val sel = fadeMin == m
                            OutlinedButton(
                                onClick = { fadeMin = m },
                                modifier = Modifier.weight(1f),
                                colors = if (sel) ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ) else ButtonDefaults.outlinedButtonColors()
                            ) { Text("${m}m") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { endOfTrack = !endOfTrack }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Stop at end of current track")
                        Switch(checked = endOfTrack, onCheckedChange = { endOfTrack = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Graph.settings.setSleepFadeMin(fadeMin)
                    Graph.settings.setSleepEndOfTrack(endOfTrack)
                    PlayerManager.setSleepTimer(pendingMinutes, fadeMin, endOfTrack)
                    showSleepDialog = false
                }) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = { showSleepDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EqualizerSection() {
    var enabled by remember { mutableStateOf(Graph.settings.isEqEnabled()) }
    var preset by remember { mutableStateOf(Graph.settings.getEqPreset()) }
    var bands by remember { mutableStateOf(Graph.settings.getEqBands()) }

    val freqs = EqualizerEngine.BAND_FREQUENCIES_HZ

    SettingsCard {
        ListItem(
            headlineContent = { Text("Equalizer") },
            supportingContent = { Text("Built-in 5-band EQ, applied live") },
            trailingContent = {
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        PlayerManager.setEqEnabled(it)
                    }
                )
            }
        )
        if (enabled) {
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "Presets",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (preset == -1) {
                        PresetChip("Custom", selected = true, onClick = {})
                    }
                    EqualizerEngine.presets.forEachIndexed { i, p ->
                        PresetChip(
                            p.name,
                            selected = preset == i,
                            onClick = {
                                preset = i
                                bands = p.bands
                                PlayerManager.setEqPreset(i)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().height(264.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    bands.forEachIndexed { i, mb ->
                        VerticalEqSlider(
                            valueMb = mb,
                            freqLabel = if (freqs[i] >= 1000) "${freqs[i] / 1000} kHz" else "${freqs[i]} Hz",
                            onValueChange = { v ->
                                bands = bands.toMutableList().also { it[i] = v }
                            },
                            onValueChangeFinished = {
                                preset = -1
                                PlayerManager.setEqBands(bands)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = {
                        preset = 0
                        bands = EqualizerEngine.presets[0].bands
                        PlayerManager.setEqPreset(0)
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text("Reset to flat") }
            }
        }
    }
    if (!enabled) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Turn it on to shape the sound. Outro plays bit-perfect audio with the equalizer off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A mixer-style vertical EQ slider: tap-to-jump plus drag, absolute position
 * mapping, quantized to 0.1 dB. Consumes vertical moves so it never fights
 * the parent settings scroll.
 */
@Composable
private fun VerticalEqSlider(
    valueMb: Int,
    freqLabel: String,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxMb = EqualizerEngine.MAX_DB_MB.toFloat()
    val latestChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)
    val frac = ((valueMb / maxMb) + 1f) / 2f // 0..1, 0.5 = 0 dB

    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            String.format(java.util.Locale.US, "%+.1f dB", valueMb / 100f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(64.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(
            Modifier
                .width(56.dp)
                .weight(1f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val h = size.height.toFloat()
                        fun setFromY(y: Float) {
                            val f = (1f - y / h).coerceIn(0f, 1f)
                            val raw = (f * 2f - 1f) * maxMb
                            val stepped = (raw / 10f).roundToInt() * 10
                            latestChange(stepped.coerceIn(-maxMb.toInt(), maxMb.toInt()))
                        }
                        setFromY(down.position.y)
                        val pid = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pid } ?: break
                            if (!change.pressed) break
                            change.consume()
                            setFromY(change.position.y)
                        }
                        latestFinished()
                    }
                }
        ) {
            val trackH = maxHeight
            val center = trackH / 2
            val thumbY = trackH * (1f - frac)
            val fillTop = minOf(center, thumbY)
            val fillH = (if (center > thumbY) center - thumbY else thumbY - center).coerceAtLeast(4.dp)
            // Track
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(6.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            // Fill from the 0 dB center line to the thumb
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = fillTop)
                    .width(6.dp)
                    .height(fillH)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            // 0 dB detent line
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(20.dp)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            )
            // Thumb fader cap
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (thumbY - 11.dp).coerceAtLeast(0.dp))
                    .width(36.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            freqLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
@Composable
private fun SoundSection() {
    var profileName by remember { mutableStateOf(Graph.settings.getHeadphoneProfile()) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var preamp by remember { mutableStateOf(Graph.settings.getEqPreamp()) }
    var boost by remember { mutableStateOf(Graph.settings.getVolumeBoost()) }
    var skipSilence by remember { mutableStateOf(Graph.settings.isSkipSilence()) }
    var compressor by remember { mutableStateOf(Graph.settings.getCompressor()) }
    var showCompDialog by remember { mutableStateOf(false) }

    SettingsCard {
        SettingsRow(
            "Headphone profile",
            profileName.ifEmpty { "None" }
        ) { showProfileDialog = true }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Preamp",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    String.format(java.util.Locale.US, "%+.1f dB", preamp / 100f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = preamp.toFloat(),
                onValueChange = { preamp = it.toInt() },
                onValueChangeFinished = { PlayerManager.setEqPreamp(preamp) },
                valueRange = -1200f..1200f,
                steps = 239
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Volume boost",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    String.format(java.util.Locale.US, "+%.1f dB", boost / 100f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = boost.toFloat(),
                onValueChange = { boost = it.toInt() },
                onValueChangeFinished = { PlayerManager.setVolumeBoost(boost) },
                valueRange = 0f..1000f,
                steps = 99
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        ListItem(
            headlineContent = { Text("Skip silence") },
            supportingContent = { Text("Trim silent parts inside tracks") },
            trailingContent = {
                Switch(
                    checked = skipSilence,
                    onCheckedChange = {
                        skipSilence = it
                        PlayerManager.setSkipSilence(it)
                    }
                )
            }
        )
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SettingsRow(
            "Compressor",
            com.opus.music.player.CompressorController.PRESET_NAMES[compressor]
        ) { showCompDialog = true }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Headphone profiles apply AutoEQ correction curves for popular models. " +
            "Preamp shifts the whole EQ up or down. Volume boost pushes loudness " +
            "past 100% — watch for distortion on already-loud tracks.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (showProfileDialog) {
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text("Headphone profile") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            // Leaving a profile: restore the user's own curve
                            // from before the profile was applied (flat if none).
                            val restore = Graph.settings.getHeadphonePrevBands()
                                ?: List(5) { 0 }
                            Graph.settings.setHeadphonePrevBands(null)
                            profileName = ""
                            Graph.settings.setHeadphoneProfile("")
                            PlayerManager.setEqBands(restore)
                            showProfileDialog = false
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = profileName.isEmpty(), onClick = null)
                        Text("None", Modifier.padding(start = 8.dp))
                    }
                    com.opus.music.player.HeadphoneProfiles.profiles.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                // Stash the user's own curve before the profile
                                // overwrites it, so "None" can bring it back.
                                if (Graph.settings.getHeadphoneProfile().isEmpty()) {
                                    Graph.settings.setHeadphonePrevBands(Graph.settings.getEqBands())
                                }
                                profileName = p.name
                                Graph.settings.setHeadphoneProfile(p.name)
                                // A picked correction only matters with the EQ on.
                                if (!Graph.settings.isEqEnabled()) {
                                    PlayerManager.setEqEnabled(true)
                                }
                                PlayerManager.setEqBands(p.bandsMb)
                                showProfileDialog = false
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = profileName == p.name, onClick = null)
                            Text(p.name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProfileDialog = false }) { Text("Close") }
            }
        )
    }

    if (showCompDialog) {
        AlertDialog(
            onDismissRequest = { showCompDialog = false },
            title = { Text("Compressor") },
            text = {
                Column {
                    com.opus.music.player.CompressorController.PRESET_NAMES.forEachIndexed { i, name ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                compressor = i
                                PlayerManager.setCompressor(i)
                                showCompDialog = false
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = compressor == i, onClick = null)
                            Text(name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCompDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun SpeakersSection() {
    val context = LocalContext.current
    val devices by com.opus.music.cast.CastManager.devices.collectAsState()
    val castState by com.opus.music.cast.CastManager.state.collectAsState()
    val queue by PlayerManager.queueSongs.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.opus.music.cast.CastManager.scan(context)
    }

    SettingsCard {
        when (val st = castState) {
            is com.opus.music.cast.CastState.Scanning -> {
                ListItem(
                    headlineContent = { Text("Scanning for speakers…") },
                    trailingContent = {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp
                        )
                    }
                )
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            }
            is com.opus.music.cast.CastState.Casting -> {
                ListItem(
                    headlineContent = { Text("Casting to ${st.device.name}") },
                    supportingContent = { Text(st.songTitle) },
                    trailingContent = {
                        TextButton(onClick = { com.opus.music.cast.CastManager.stop() }) {
                            Text("Stop")
                        }
                    }
                )
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            }
            is com.opus.music.cast.CastState.Error -> {
                ListItem(
                    headlineContent = {
                        Text(st.message, color = MaterialTheme.colorScheme.error)
                    }
                )
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            }
            else -> {}
        }
        ListItem(
            headlineContent = { Text("Scan for speakers") },
            supportingContent = { Text("Sonos, Chromecast and AirPlay speakers on this Wi-Fi network") },
            trailingContent = {
                TextButton(onClick = { com.opus.music.cast.CastManager.scan(context) }) {
                    Text("Scan")
                }
            }
        )
        if (devices.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            devices.forEach { device ->
                val castingHere =
                    (castState as? com.opus.music.cast.CastState.Casting)?.device?.id == device.id
                ListItem(
                    headlineContent = { Text(device.name) },
                    supportingContent = {
                        Text(
                            if (queue.isEmpty()) "${device.kind.label} · play something first"
                            else device.kind.label
                        )
                    },
                    trailingContent = {
                        if (castingHere) {
                            TextButton(onClick = { com.opus.music.cast.CastManager.stop() }) {
                                Text("Stop")
                            }
                        } else {
                            TextButton(
                                enabled = queue.isNotEmpty(),
                                onClick = {
                                    val idx = PlayerManager.queueIndex().coerceAtLeast(0)
                                    com.opus.music.cast.CastManager.castTo(device, queue, idx)
                                }
                            ) { Text("Cast") }
                        }
                    }
                )
            }
        } else if (castState !is com.opus.music.cast.CastState.Scanning) {
            ListItem(
                headlineContent = { Text("No speakers found") },
                supportingContent = { Text("Make sure the speaker is on the same Wi-Fi network") }
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Casting sends the current queue to the speaker — playback controls " +
            "keep working from the player and the queue.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    // Build label — so a screenshot always shows which APK produced it.
    val versionName = remember {
        runCatching {
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName
        }.getOrNull()
    }
    Text(
        "Outro $versionName",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    )
    Spacer(Modifier.height(16.dp))
    RaopLogSection()
}

@Composable
private fun rememberRaopLogText(): String {
    var text by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            com.opus.music.cast.raop.RaopLogger.snapshot()
        )
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val listener: () -> Unit = {
            text = com.opus.music.cast.raop.RaopLogger.snapshot()
        }
        com.opus.music.cast.raop.RaopLogger.addListener(listener)
        onDispose { com.opus.music.cast.raop.RaopLogger.removeListener(listener) }
    }
    return text
}

@Composable
private fun RaopLogSection() {
    val context = LocalContext.current
    val logText = rememberRaopLogText()
    val scroll = rememberScrollState()

    // Auto-scroll to the newest lines when the log grows.
    androidx.compose.runtime.LaunchedEffect(logText) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    SettingsCard {
        ListItem(
            headlineContent = { Text("AirPlay connection log") },
            supportingContent = {
                Text("Exactly what the phone sent the HomePod, and what it replied")
            },
        )
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(scroll)
        ) {
            Text(
                logText.ifEmpty { "Empty — tap Cast on an AirPlay speaker and the handshake will be recorded here." },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("Outro AirPlay log", logText)
                    )
                    android.widget.Toast.makeText(context, "Log copied", android.widget.Toast.LENGTH_SHORT).show()
                },
                enabled = logText.isNotEmpty(),
            ) { Text("Copy") }
            OutlinedButton(
                onClick = {
                    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Outro AirPlay connection log")
                        putExtra(android.content.Intent.EXTRA_TEXT, logText)
                    }
                    context.startActivity(
                        android.content.Intent.createChooser(share, "Share log")
                    )
                },
                enabled = logText.isNotEmpty(),
            ) { Text("Share") }
            TextButton(
                onClick = { com.opus.music.cast.raop.RaopLogger.clear() },
                enabled = logText.isNotEmpty(),
            ) { Text("Clear") }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    android.widget.Toast.makeText(
                        context,
                        "AirPlay 2 probe started — watch the log",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    Thread {
                        com.opus.music.cast.ap2.Ap2Probe.runProbe(context.applicationContext)
                    }.start()
                },
            ) { Text("Run AirPlay 2 pairing probe") }
        }
    }
}

@Composable
private fun SwipeSection() {
    var tick by remember { mutableStateOf(0) }
    var pickKey by remember { mutableStateOf<String?>(null) }

    val miniKeys = listOf(
        SwipeActions.KEY_MINI_UP,
        SwipeActions.KEY_MINI_DOWN,
        SwipeActions.KEY_MINI_LEFT,
        SwipeActions.KEY_MINI_RIGHT
    )
    val songKeys = listOf(SwipeActions.KEY_SONG_LEFT, SwipeActions.KEY_SONG_RIGHT)

    fun actionLabel(key: String): String {
        @Suppress("UNUSED_EXPRESSION")
        tick
        return SwipeActions.label(Graph.settings.getSwipeAction(key))
    }

    SettingsCard {
        Text(
            "Mini player",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )
        miniKeys.forEachIndexed { i, key ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            SettingsRow(SwipeActions.gestureLabel(key), actionLabel(key)) { pickKey = key }
        }
    }
    Spacer(Modifier.height(16.dp))
    SettingsCard {
        Text(
            "Song list",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )
        songKeys.forEachIndexed { i, key ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            SettingsRow(SwipeActions.gestureLabel(key), actionLabel(key)) { pickKey = key }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Tip: swipe a song row and hold to see what will happen before you let go.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    pickKey?.let { key ->
        val options = SwipeActions.allowedFor(key)
        val selected = Graph.settings.getSwipeAction(key)
        AlertDialog(
            onDismissRequest = { pickKey = null },
            title = { Text(SwipeActions.gestureLabel(key)) },
            text = {
                Column {
                    options.forEach { opt ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                Graph.settings.setSwipeAction(key, opt)
                                tick++
                                pickKey = null
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected == opt, onClick = null)
                            Text(SwipeActions.label(opt), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickKey = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun ArtworkSection() {
    var highQuality by remember { mutableStateOf(Graph.settings.isArtworkHighQuality()) }
    SettingsCard {
        ListItem(
            headlineContent = { Text("High-quality artwork") },
            supportingContent = { Text("Load larger cover art (uses more data)") },
            trailingContent = {
                Switch(
                    checked = highQuality,
                    onCheckedChange = {
                        highQuality = it
                        Graph.settings.setArtworkHighQuality(it)
                    }
                )
            }
        )
    }
}

@Composable
private fun SupportSection() {
    SettingsCard {
        ListItem(
            headlineContent = { Text("Outro Support") },
            supportingContent = { Text("Send diagnostics and feedback from the app") }
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Found a bug? Note what you were doing and which screen you were on — it helps fix things fast.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LicenseSection() {
    Text(
        "License",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Outro is built with open-source libraries: Jetpack Compose, Media3, Coil, OkHttp, and Kotlinx Serialization. Thanks to their authors.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AboutSection() {
    Text(
        "About",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Outro 1.2.6 — a modern Subsonic client for Navidrome.\nMultiple accounts, 5-band equalizer, swipe gestures, and unlimited Smart Offline Mix.\nNow with speaker playback (Sonos, Chromecast, AirPlay/HomePod), Android Auto, a home-screen widget, synced lyrics, radio, personal mixes, and a refreshed player.\nDark or light, smooth, and yours.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
