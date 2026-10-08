package im.flume.hearth.ui.screens

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
import androidx.work.WorkManager
import im.flume.hearth.container
import im.flume.hearth.download.DownloadRepository
import im.flume.hearth.playback.QueueStore
import im.flume.hearth.sync.SyncState
import im.flume.hearth.ui.components.formatBytes
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BITRATES = listOf(0 to "Original quality", 320 to "320 kbps", 256 to "256 kbps", 192 to "192 kbps", 128 to "128 kbps (saves data)")
private val CACHE_SIZES = listOf(512 to "512 MB", 1024 to "1 GB", 2048 to "2 GB", 4096 to "4 GB", 8192 to "8 GB")

@Composable
private fun SettingsPage(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            BackButton()
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
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
            Triple("General", "Account, library, streaming, updates", Icons.Outlined.Settings) to "settings/general",
            Triple("Storage", "Downloads, cache, offline mode", Icons.Outlined.Storage) to "settings/storage",
            Triple("Appearance", "Accent colour, lyrics", Icons.Outlined.Palette) to "settings/appearance",
        ).forEach { (row, route) ->
            val (title, subtitle, icon) = row
            Row(
                Modifier.fillMaxWidth().clickable { actions.open(route) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = Accent)
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

    SettingsPage("General") {
        Section("App")
        UpdateSettingsRow()

        Section("Account")
        Info("Signed in as", creds?.username.orEmpty())
        Info("Server", creds?.serverUrl.orEmpty())

        Section("Library")
        Info("Songs on this phone's index", songCount.toString())
        Clickable(
            title = if (sync is SyncState.Running) "Syncing…" else "Resync library now",
            subtitle = (sync as? SyncState.Failed)?.message ?: "Fetches any changes from Navidrome",
        ) { c.appScope.launch { c.sync.fullSync(c.api.scanStatus()?.lastScan) } }

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
        remember { c.usage.all() }.sortedByDescending { it.second }.forEach { (name, count) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(name, modifier = Modifier.weight(1f))
                Text("$count", color = TextSecondary)
            }
        }

        Section("")
        Clickable("Sign out", "Removes downloads and the library index from this phone", color = MaterialTheme.colorScheme.error) {
            confirmLogout = true
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out?") },
            text = { Text("Downloaded music on this phone will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    c.player.stopAndClear()
                    c.appScope.launch {
                        WorkManager.getInstance(context).cancelUniqueWork(DownloadRepository.WORK_NAME)
                        c.db.clearAllTables()
                        c.downloads.dir.listFiles()?.forEach { it.delete() }
                        QueueStore(context.filesDir).clear()
                        c.session.logout()
                    }
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
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
    var cacheCleared by remember { mutableStateOf(false) }
    var confirmRemoveAll by remember { mutableStateOf(false) }

    SettingsPage("Storage") {
        Section("Downloads")
        Info("Space used", formatBytes(downloadedBytes))
        Choice("Download quality", settings.downloadBitrate, BITRATES) { v -> c.session.updateSettings { it.copy(downloadBitrate = v) } }
        Toggle("Download on Wi-Fi only", settings.wifiOnlyDownloads) { v ->
            c.session.updateSettings { it.copy(wifiOnlyDownloads = v) }
            c.downloads.schedule()
        }
        Toggle("Offline mode", settings.offlineMode, "Only play downloaded songs, even with a connection") { v ->
            c.session.updateSettings { it.copy(offlineMode = v) }
        }
        if (downloadedBytes > 0) {
            Clickable("Remove all downloads", color = MaterialTheme.colorScheme.error) { confirmRemoveAll = true }
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
                    Text("Remove", color = MaterialTheme.colorScheme.error)
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(if (selected) 3.dp else 0.dp, Color.White, CircleShape)
                            .clickable { c.session.updateSettings { it.copy(accent = argb.toInt()) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Default.Check, name, tint = Color.Black)
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
    HorizontalDivider(Modifier.padding(top = 16.dp), color = Color(0xFF2A2A2A))
    if (title.isNotEmpty()) {
        Text(title, color = Accent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
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
        Switch(value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
    }
}

@Composable
private fun Choice(title: String, value: Int, options: List<Pair<Int, String>>, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title)
            Text(options.firstOrNull { it.first == value }?.second ?: "$value", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (v, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onChange(v); open = false })
            }
        }
    }
}
