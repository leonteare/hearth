// Settings > Storage: downloads, the streaming cache, podcasts and audiobooks on the phone, and getting music.
package im.flume.hearth.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.data.DownloadCounts
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.formatBytes
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PARALLEL = listOf(1 to "1 (gentlest on the server)", 2 to "2", 3 to "3", 4 to "4 (fastest if the server has 4+ cores)")
private val CACHE_SIZES = listOf(512 to "512 MB", 1024 to "1 GB", 2048 to "2 GB", 4096 to "4 GB", 8192 to "8 GB")

@Composable
fun StorageSettings() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val downloadedBytes by remember { c.downloads.totalBytes }.collectAsStateWithLifecycle(0L)
    val spokenDownloadedBytes by remember { c.spoken.downloads.totalBytes }.collectAsStateWithLifecycle(0L)
    val counts by remember { c.downloads.counts }.collectAsStateWithLifecycle(DownloadCounts())
    val actions = LocalActions.current
    var cacheCleared by remember { mutableStateOf(false) }
    var confirmRemoveAll by remember { mutableStateOf(false) }

    SettingsPage("Storage") {
        Section("Downloads")
        Clickable(
            "Downloads",
            listOfNotNull(
                "${formatBytes(downloadedBytes + spokenDownloadedBytes)} used",
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
            Clickable("Remove all downloaded music", color = HearthTheme.colors.destructive) { confirmRemoveAll = true }
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
            color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter),
        )

        PodcastsAndAudiobooksStorage()

        GetMusicSettings()
    }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text("Remove all downloaded music?") },
            text = { Text("All the music downloaded to this phone will be deleted. Downloaded podcasts and audiobooks stay. You can download the music again later.") },
            confirmButton = {
                TextButton(onClick = { confirmRemoveAll = false; c.downloads.removeAll() }) {
                    Text("Remove", color = HearthTheme.colors.destructive)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") } },
        )
    }
}
