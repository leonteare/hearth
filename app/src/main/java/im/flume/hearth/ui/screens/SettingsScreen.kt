package im.flume.hearth.ui.screens

import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import im.flume.hearth.ui.theme.HearthShapes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.theme.AccentChoices
import im.flume.hearth.ui.theme.AccentDeep
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.playback.QueueStore
import im.flume.hearth.sync.SyncState
import im.flume.hearth.ui.components.formatBytes
import im.flume.hearth.ui.components.ChoiceSheet
import im.flume.hearth.ui.components.TopBar
import im.flume.hearth.ui.theme.Destructive
import im.flume.hearth.ui.theme.DividerColor
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BITRATES = listOf(0 to "Original quality", 320 to "320 kbps", 256 to "256 kbps", 192 to "192 kbps", 128 to "128 kbps (saves data)")
private val PARALLEL = listOf(1 to "1 (gentlest on the server)", 2 to "2", 3 to "3", 4 to "4 (fastest if the server has 4+ cores)")
private val CACHE_SIZES = listOf(512 to "512 MB", 1024 to "1 GB", 2048 to "2 GB", 4096 to "4 GB", 8192 to "8 GB")

@Composable
private fun SettingsPage(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(title)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

/** Settings home: three groups, each its own page. */
@Composable
fun SettingsScreen() {
    val actions = LocalActions.current
    SettingsPage("Settings") {
        listOf(
            Triple("General", "Account, library, requests, playback, streaming, updates", Icons.Outlined.Settings) to "settings/general",
            Triple("Storage", "Downloads, cache", Icons.Outlined.Storage) to "settings/storage",
            Triple("Appearance", "Accent colour, lyrics", Icons.Outlined.Palette) to "settings/appearance",
        ).forEach { (row, route) ->
            val (title, subtitle, icon) = row
            Row(
                Modifier.fillMaxWidth().clickable { actions.open(route) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title)
                    Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextSecondary)
            }
        }
    }
}

@Composable
fun GeneralSettings() {
    val c = LocalContext.current.container
    val context = LocalContext.current
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val creds by c.session.credentials.collectAsStateWithLifecycle()
    val songCount by remember { c.db.library().songCount() }.collectAsStateWithLifecycle(0)
    val sync by c.sync.state.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    val usageCounts by remember { c.usage.changes() }.collectAsStateWithLifecycle(c.usage.all())

    SettingsPage("General") {
        Section("App")
        UpdateSettingsRow()

        Section("Account")
        Info("Signed in as", creds?.username.orEmpty())
        Info("Server", creds?.serverUrl.orEmpty())
        Clickable("Sign out", "Your downloads stay on this phone", color = Destructive) {
            confirmLogout = true
        }

        Section("Library")
        Info("Songs in your library", songCount.toString())
        Clickable(
            title = if (sync is SyncState.Running) "Syncing…" else "Resync library now",
            subtitle = (sync as? SyncState.Failed)?.message ?: "Fetches any changes from Navidrome",
        ) { c.appScope.launch { c.sync.fullSync(c.api.scanStatus()?.lastScan) } }

        TidarrSettings()

        Section("Playback")
        Choice("Volume levelling", settings.volumeLevelling, listOf(0 to "Off", 1 to "Per song", 2 to "Per album")) { v ->
            c.session.updateSettings { it.copy(volumeLevelling = v) }
        }
        Text(
            "Uses the loudness info in your music files so songs play at a similar volume. Per album keeps the intended differences between songs on an album.",
            color = TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Toggle("Smart shuffle", settings.smartShuffle, "Avoid playing the same artist twice in a row") { v ->
            c.session.updateSettings { it.copy(smartShuffle = v) }
        }
        Toggle("Offline mode", settings.offlineMode, "Only play downloaded songs, even with a connection") { v ->
            c.session.updateSettings { it.copy(offlineMode = v) }
        }

        Section("Streaming")
        Choice("Quality on Wi-Fi", settings.wifiBitrate, BITRATES) { v -> c.session.updateSettings { it.copy(wifiBitrate = v) } }
        Choice("Quality on mobile data", settings.mobileBitrate, BITRATES) { v -> c.session.updateSettings { it.copy(mobileBitrate = v) } }
        Text(
            "Lower qualities are converted on the server, so seeking within a song that isn't cached yet can be slower.",
            color = TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp),
        )

        Section("Feature use on this phone")
        Text(
            "How often each feature has been used. Never sent anywhere; it helps decide what to keep.",
            color = TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp),
        )
        usageCounts.sortedByDescending { it.second }.forEach { (name, count) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(name, modifier = Modifier.weight(1f))
                Text("$count", color = TextSecondary)
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out?") },
            text = { Text("Your downloads stay on this phone, ready for when you sign in again. Anything still waiting to download carries on after you sign back in.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    c.player.stopAndClear()
                    c.appScope.launch {
                        // Downloads, pins, lyrics, play history and the library index are kept: signing out
                        // is usually an accident, and signing back in should find everything still there.
                        // Wait for the download worker to stop before the login it uses goes away.
                        c.downloads.stopWorker()
                        QueueStore(context.filesDir).clear()
                        c.nativeApi.forgetSession()
                        c.session.logout()
                        c.downloads.reset()
                    }
                }) { Text("Sign out", color = Destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun StorageSettings() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val downloadedBytes by remember { c.downloads.totalBytes }.collectAsStateWithLifecycle(0L)
    val counts by remember { c.downloads.counts }.collectAsStateWithLifecycle(im.flume.hearth.data.DownloadCounts())
    val actions = LocalActions.current
    var cacheCleared by remember { mutableStateOf(false) }
    var confirmRemoveAll by remember { mutableStateOf(false) }

    SettingsPage("Storage") {
        Section("Downloads")
        Clickable(
            "Downloads",
            listOfNotNull(
                "${formatBytes(downloadedBytes)} used",
                "${counts.left} left to download".takeIf { counts.left > 0 },
                "${counts.failed} couldn't download".takeIf { counts.failed > 0 },
            ).joinToString(" · "),
        ) { actions.open("downloads") }
        Choice("Download quality", settings.downloadBitrate, BITRATES) { v -> c.session.updateSettings { it.copy(downloadBitrate = v) } }
        Choice("Downloads at once", settings.parallelDownloads, PARALLEL) { v ->
            c.session.updateSettings { it.copy(parallelDownloads = v) }
        }
        Toggle("Download on Wi-Fi only", settings.wifiOnlyDownloads) { v ->
            c.session.updateSettings { it.copy(wifiOnlyDownloads = v) }
            c.downloads.schedule()
        }
        if (downloadedBytes > 0) {
            Clickable("Remove all downloads", color = Destructive) { confirmRemoveAll = true }
        }

        Section("Streaming cache")
        Choice("Cache size", settings.cacheSizeMb, CACHE_SIZES) { v -> c.session.updateSettings { it.copy(cacheSizeMb = v) } }
        Clickable(
            title = "Clear streaming cache",
            subtitle = if (cacheCleared) "Cleared" else "Recently played songs kept for instant replay. Downloads aren't affected.",
        ) {
            scope.launch {
                withContext(Dispatchers.IO) { c.mediaCache.keys.toList().forEach { c.mediaCache.removeResource(it) } }
                cacheCleared = true
            }
        }
        Text(
            "Cache size changes apply after the app restarts.",
            color = TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text("Remove all downloads?") },
            text = { Text("Everything downloaded to this phone will be deleted. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = { confirmRemoveAll = false; c.downloads.removeAll() }) {
                    Text("Remove", color = Destructive)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettings() {
    val c = LocalContext.current.container
    val settings by c.session.settings.collectAsStateWithLifecycle()

    SettingsPage("Appearance") {
        Section("Accent colour")
        FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AccentChoices.forEach { (name, argb) ->
                val selected = settings.accent == argb.toInt()
                // The whole swatch + label is one 48dp+ radio target; TalkBack reads the colour name and whether it is selected.
                Column(
                    Modifier
                        .clip(HearthShapes.Card)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            c.session.updateSettings { it.copy(accent = argb.toInt()) }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(if (selected) 3.dp else 0.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Default.Check, null, tint = Color.Black)
                    }
                    Text(name, style = MaterialTheme.typography.labelMedium, color = if (selected) Color.White else TextSecondary)
                }
            }
        }

        Section("Lyrics")
        Choice("Text size", settings.lyricsSize, listOf(0 to "Small", 1 to "Medium", 2 to "Large", 3 to "Extra large")) { v ->
            c.session.updateSettings { it.copy(lyricsSize = v) }
        }
        Choice("Line spacing", settings.lyricsSpacing, listOf(0 to "Compact", 1 to "Normal", 2 to "Relaxed")) { v ->
            c.session.updateSettings { it.copy(lyricsSpacing = v) }
        }
        // Live preview of the lyric style.
        val size = LYRIC_SIZES.getOrElse(settings.lyricsSize) { 22 }.sp
        val gap = LYRIC_GAPS.getOrElse(settings.lyricsSpacing) { 8 }.dp
        Column(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(8.dp)).background(AccentDeep).padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            listOf("Here's how your lyrics" to 0.45f, "will look while a song" to 1f, "is playing" to 0.35f).forEach { (line, alpha) ->
                Text(line, fontSize = size, lineHeight = size * 1.25f, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = alpha), modifier = Modifier.padding(vertical = gap))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp), color = DividerColor)
    if (title.isNotEmpty()) {
        Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
    }
}

@Composable
private fun Info(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title)
        Text(value, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Clickable(title: String, subtitle: String? = null, color: Color = Color.White, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, color = color)
        subtitle?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Toggle(title: String, value: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodyMedium) }
        }
        Switch(value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun Choice(title: String, value: Int, options: List<Pair<Int, String>>, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title)
        Text(options.firstOrNull { it.first == value }?.second ?: "$value", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
    ChoiceSheet(open, title, options, value, onDismiss = { open = false }, onPick = onChange)
}

/** Requests (Tidarr): where Tidarr is, its optional API key, a connection test, and the Search toggle. */
@Composable
private fun TidarrSettings() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val requests by remember { c.requests.requests }.collectAsStateWithLifecycle(emptyList())
    var url by remember { mutableStateOf(settings.tidarrUrl) }
    var key by remember { mutableStateOf("") }
    var keyLoaded by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        key = withContext(Dispatchers.IO) { c.requests.apiKey().orEmpty() }
        keyLoaded = true
    }
    val dirty = keyLoaded && (url.trim() != settings.tidarrUrl || key.trim() != c.requests.apiKey().orEmpty())

    fun save() {
        val u = url.trim()
        c.session.updateSettings { it.copy(tidarrUrl = u) }
        scope.launch { withContext(Dispatchers.IO) { c.requests.setApiKey(key) } }
    }

    Section("Requests (Tidarr)")
    Text(
        "Search can show music that isn't on your server yet. Requesting it asks Tidarr to fetch it into your library; it's then liked and downloaded to this phone.",
        color = TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp),
    )
    androidx.compose.material3.OutlinedTextField(
        value = url, onValueChange = { url = it; result = null },
        label = { Text("Tidarr address") }, singleLine = true,
        placeholder = { Text(im.flume.hearth.data.Settings.DEFAULT_TIDARR_URL) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    androidx.compose.material3.OutlinedTextField(
        value = key, onValueChange = { key = it; result = null },
        label = { Text("API key (optional)") }, singleLine = true, enabled = keyLoaded,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { save(); actions.showMessage("Tidarr settings saved") }, enabled = dirty) { Text("Save") }
        TextButton(
            enabled = !testing && url.isNotBlank(),
            onClick = {
                testing = true; result = null
                scope.launch {
                    val error = c.requests.testConnection(url, key)
                    result = if (error == null) "Connected to Tidarr" else "Failed: $error"
                    testing = false
                    if (error == null && dirty) save()
                }
            },
        ) { Text(if (testing) "Testing…" else "Test connection") }
    }
    result?.let {
        Text(
            it, color = if (it.startsWith("Failed")) Destructive else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    Toggle("Show Tidal results in Search", settings.tidalInSearch, "Under your library's results, for music not on the server") { v ->
        c.session.updateSettings { it.copy(tidalInSearch = v) }
    }
    val inProgress = requests.count { it.status.active }
    Clickable(
        "Your requests",
        when {
            requests.isEmpty() -> "Nothing requested yet"
            inProgress > 0 -> "$inProgress in progress"
            else -> "${requests.size} requested"
        },
    ) { actions.open("requests") }
}
