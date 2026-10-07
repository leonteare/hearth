package im.flume.hearth.ui.screens

import androidx.compose.foundation.clickable
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
fun SettingsScreen() {
    val c = LocalContext.current.container
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val creds by c.session.credentials.collectAsStateWithLifecycle()
    val songCount by remember { c.db.library().songCount() }.collectAsStateWithLifecycle(0)
    val sync by c.sync.state.collectAsStateWithLifecycle()
    val downloadedBytes by remember { c.downloads.totalBytes }.collectAsStateWithLifecycle(0L)
    var confirmLogout by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableStateOf(-1L) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            BackButton()
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

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
        ) { scope.launch { c.sync.fullSync(c.api.scanStatus()?.lastScan) } }

        Section("Streaming")
        Choice("Quality on Wi-Fi", settings.wifiBitrate, BITRATES) { v -> c.session.updateSettings { it.copy(wifiBitrate = v) } }
        Choice("Quality on mobile data", settings.mobileBitrate, BITRATES) { v -> c.session.updateSettings { it.copy(mobileBitrate = v) } }
        Text(
            "Lower qualities are converted on the server, so seeking within a song that isn't cached yet can be slower.",
            color = TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Choice("Streaming cache size", settings.cacheSizeMb, CACHE_SIZES) { v -> c.session.updateSettings { it.copy(cacheSizeMb = v) } }
        Clickable(
            title = "Clear streaming cache",
            subtitle = if (cacheBytes >= 0) "Cleared" else "Takes effect immediately; downloads are kept",
        ) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    c.mediaCache.keys.toList().forEach { c.mediaCache.removeResource(it) }
                }
                cacheBytes = 0
            }
        }

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

        Section("")
        Clickable("Sign out", "Removes downloads and the library index from this phone", color = MaterialTheme.colorScheme.error) {
            confirmLogout = true
        }
        Text(
            "Cache size changes apply after the app restarts",
            color = TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp),
        )
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
