// The Downloads page: the status card and what's downloading, waiting, failed and recently finished.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.data.DownloadState
import im.flume.hearth.data.PlaySource
import im.flume.hearth.core.data.RequestStatus
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.core.data.spoken.SpokenDownloadState
import im.flume.hearth.core.ui.components.BannerCard
import im.flume.hearth.core.ui.components.EmptyState
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.LocalRowContext
import im.flume.hearth.core.ui.components.SectionHeader
import im.flume.hearth.core.ui.components.SongRow
import im.flume.hearth.core.ui.components.TopBar
import im.flume.hearth.core.ui.components.formatBytes
import im.flume.hearth.core.ui.components.plural
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlinx.coroutines.flow.map

/** How far back "Finished" reaches. */
private const val FINISHED_WINDOW_MS = 24 * 60 * 60 * 1000L
internal const val SECTION_LIMIT = 50

/**
 * What's downloading, waiting, failed and recently finished. Not a playlist: finished downloads live
 * in their albums and playlists, and nothing here can delete them.
 */
@Composable
fun DownloadsScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val dao = c.db.library()
    val pending by remember { dao.pendingDownloadSongs() }.collectAsStateWithLifecycle(emptyList())
    val since = remember { System.currentTimeMillis() - FINISHED_WINDOW_MS }
    val finished by remember { dao.recentlyDownloadedSongs(since, 100) }.collectAsStateWithLifecycle(emptyList())
    val bytes by remember { c.downloads.totalBytes }.collectAsStateWithLifecycle(0L)
    // Download progress is deliberately not read here: it ticks every 256 KB, and reading it at this
    // level would recompose the whole screen each time. Each downloading row collects its own.
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val onWifi by c.network.wifi.collectAsStateWithLifecycle()
    val states = LocalRowContext.current.downloads
    val lastError by c.downloads.lastError.collectAsStateWithLifecycle()
    val backingOff by c.downloads.backingOff.collectAsStateWithLifecycle()
    val storageFull by c.downloads.storageFull.collectAsStateWithLifecycle()
    var confirmCancelAll by remember { mutableStateOf(false) }
    // Music on its way to the server (it then downloads here). Look now rather than at the next round.
    val requests by remember { c.requests.requests }.collectAsStateWithLifecycle(emptyList())
    val coming = remember(requests) { requests.filter { it.status.active || it.status == RequestStatus.FAILED } }
    LaunchedEffect(Unit) { runCatching { c.requests.poll() } }

    val groups = remember(pending, states) { pending.groupBy { states[it.id] ?: DownloadState.QUEUED } }
    val downloading = groups[DownloadState.DOWNLOADING].orEmpty()
    val queued = groups[DownloadState.QUEUED].orEmpty()
    val failed = groups[DownloadState.FAILED].orEmpty()
    // Podcast episodes and audiobooks.
    val spokenRows by remember { c.spoken.downloadRows() }.collectAsStateWithLifecycle(emptyList())
    val spokenBytes by remember { c.spoken.downloads.totalBytes }.collectAsStateWithLifecycle(0L)
    val spokenGroups = remember(spokenRows) { spokenRows.groupBy { it.download.state } }
    val spokenDownloading = spokenGroups[SpokenDownloadState.DOWNLOADING].orEmpty()
    val spokenQueued = spokenGroups[SpokenDownloadState.QUEUED].orEmpty()
    val spokenFailed = spokenGroups[SpokenDownloadState.FAILED].orEmpty()
    val spokenFinished = remember(spokenGroups) {
        spokenGroups[SpokenDownloadState.DONE].orEmpty().filter { it.download.completedAt >= since }.sortedByDescending { it.download.completedAt }
    }
    val spokenActive = spokenDownloading + spokenQueued + spokenFailed
    val left = downloading.size + queued.size
    val userPaused = settings.downloadsPaused
    val waitingForWifi = left > 0 && settings.wifiOnlyDownloads && !onWifi

    fun play(list: List<SongEntity>, song: SongEntity) =
        actions.play(PlaySource(PlaySource.Kind.SONGS, label = "Downloads", songIds = list.map { it.id }), startSongId = song.id)

    if (confirmCancelAll) {
        AlertDialog(
            onDismissRequest = { confirmCancelAll = false },
            title = { Text("Cancel all downloads?") },
            text = { Text("${plural(pending.size, "song")} still waiting or failed will be dropped. Songs that already finished stay on this phone.") },
            confirmButton = {
                TextButton(onClick = { confirmCancelAll = false; c.downloads.cancelPending() }) { Text("Cancel all", color = HearthTheme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmCancelAll = false }) { Text("Keep downloading") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Downloads")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = HearthTheme.spacing.xl)) {
            if (coming.isNotEmpty()) {
                item(key = "c-head") { SectionHeader("Coming to your server") }
                items(coming, key = { "c:${it.type}:${it.tidalId}" }) { r -> ComingRow(r, Modifier.animateItem()) }
            }
            if (pending.isEmpty() && coming.isEmpty() && spokenActive.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        HearthTheme.icons.download,
                        "No downloads in progress. Tap the download button on an album or playlist to keep it on your phone.",
                    )
                }
            }
            if (pending.isEmpty()) {
                // Podcasts and audiobooks download on their own; their rows sit in the same sections as songs.
                spokenSection("sd", "Downloading", spokenDownloading)
                spokenSection("sq", "Up next", spokenQueued)
                spokenSection("sf", "Couldn't download", spokenFailed) { TextButton(onClick = { c.spoken.downloads.retryFailed() }) { Text("Try again") } }
            } else {
                item(key = "status") {
                    val (title, body) = when {
                        left == 0 -> "Nothing downloading" to null
                        userPaused -> "Paused · ${plural(left, "song")} left" to null
                        storageFull -> "Stopped" to "Phone storage is full. Free up some space, then try again."
                        waitingForWifi -> "Waiting for Wi-Fi · ${plural(left, "song")} left" to "Downloads are set to use Wi-Fi only."
                        backingOff -> "Server busy" to (lastError ?: "Trying again shortly")
                        else -> "Downloading ${plural(left, "song")}" to null
                    }
                    BannerCard(
                        title = title,
                        body = body,
                        actions = {
                            when {
                                left == 0 -> {}
                                userPaused -> TextButton(onClick = { c.downloads.resume() }) { Text("Resume") }
                                storageFull -> TextButton(onClick = { c.downloads.retryFailed() }) { Text("Try again") }
                                else -> TextButton(onClick = { c.downloads.pause() }) { Text("Pause") }
                            }
                            if (waitingForWifi && !userPaused) {
                                TextButton(onClick = {
                                    c.session.updateSettings { it.copy(wifiOnlyDownloads = false) }
                                    c.downloads.schedule()
                                }) { Text("Use mobile data") }
                            }
                            if (backingOff && !userPaused && !storageFull) {
                                TextButton(onClick = { c.downloads.retryFailed() }) { Text("Try again") }
                            }
                            TextButton(onClick = { confirmCancelAll = true }) { Text("Cancel all", color = HearthTheme.colors.textSecondary) }
                        },
                    )
                }
                if (downloading.isNotEmpty() || spokenDownloading.isNotEmpty()) {
                    item(key = "d-head") { SectionHeader("Downloading") }
                    items(downloading, key = { "d:${it.id}" }) { song -> PendingSongRow(song, { play(downloading, song) }, Modifier.animateItem()) }
                    spokenRows("sd", spokenDownloading)
                }
                if (queued.isNotEmpty() || spokenQueued.isNotEmpty()) {
                    item(key = "q-head") { SectionHeader("Up next") }
                    val shown = queued.take(SECTION_LIMIT)
                    items(shown, key = { "q:${it.id}" }) { song -> PendingSongRow(song, { play(shown, song) }, Modifier.animateItem()) }
                    if (queued.size > SECTION_LIMIT) {
                        item(key = "q-more") { MoreLine("+${queued.size - SECTION_LIMIT} more waiting") }
                    }
                    spokenRows("sq", spokenQueued)
                }
                if (failed.isEmpty()) {
                    spokenSection("sf", "Couldn't download", spokenFailed) { TextButton(onClick = { c.spoken.downloads.retryFailed() }) { Text("Try again") } }
                } else {
                    item(key = "f-head") {
                        SectionHeader("Couldn't download") {
                            if (!storageFull) TextButton(onClick = { c.downloads.retryFailed() }) { Text("Try again") }
                        }
                        // Storage and backoff problems are already explained in the status card.
                        if (!storageFull && !backingOff) lastError?.let { MoreLine(it) }
                    }
                    val shown = failed.take(SECTION_LIMIT)
                    items(shown, key = { "f:${it.id}" }) { song -> Column(Modifier.animateItem()) { SongRow(song, onClick = { play(shown, song) }) } }
                    if (failed.size > SECTION_LIMIT) {
                        item(key = "f-more") { MoreLine("+${failed.size - SECTION_LIMIT} more") }
                    }
                    if (spokenFailed.isNotEmpty()) {
                        item(key = "sf-retry") {
                            TextButton(onClick = { c.spoken.downloads.retryFailed() }, modifier = Modifier.padding(horizontal = HearthTheme.spacing.s)) { Text("Try podcasts and audiobooks again") }
                        }
                        spokenRows("sf", spokenFailed)
                    }
                }
            }
            if (finished.isEmpty()) {
                spokenSection("sdone", "Finished", spokenFinished)
            } else {
                finishedSection(finished) { song -> play(finished, song) }
                spokenRows("sdone", spokenFinished)
            }
            item(key = "space") {
                MoreLine(
                    if (spokenBytes > 0) "${formatBytes(bytes + spokenBytes)} downloaded to this phone: ${formatBytes(bytes)} of music, ${formatBytes(spokenBytes)} of podcasts and audiobooks"
                    else "${formatBytes(bytes)} of music downloaded to this phone"
                )
            }
        }
    }
}

private fun LazyListScope.finishedSection(finished: List<SongEntity>, onPlay: (SongEntity) -> Unit) {
    if (finished.isEmpty()) return
    item(key = "done-head") { SectionHeader("Finished") }
    items(finished, key = { "done:${it.id}" }) { song -> Column(Modifier.animateItem()) { SongRow(song, onClick = { onPlay(song) }) } }
}
