package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.data.DownloadState
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SongEntity
import im.flume.hearth.ui.components.BannerCard
import im.flume.hearth.ui.components.EmptyState
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.LocalRowContext
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.SongRow
import im.flume.hearth.ui.components.TopBar
import im.flume.hearth.ui.components.formatBytes
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.theme.Destructive
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How far back "Finished" reaches. */
private const val FINISHED_WINDOW_MS = 24 * 60 * 60 * 1000L
private const val SECTION_LIMIT = 50

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

    val groups = remember(pending, states) { pending.groupBy { states[it.id] ?: DownloadState.QUEUED } }
    val downloading = groups[DownloadState.DOWNLOADING].orEmpty()
    val queued = groups[DownloadState.QUEUED].orEmpty()
    val failed = groups[DownloadState.FAILED].orEmpty()
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
                TextButton(onClick = { confirmCancelAll = false; c.downloads.cancelPending() }) { Text("Cancel all", color = Destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmCancelAll = false }) { Text("Keep downloading") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Downloads")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (pending.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Outlined.ArrowCircleDown,
                        "No downloads in progress. Tap the download button on an album or playlist to keep it on your phone.",
                    )
                }
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
                                TextButton(onClick = { c.downloads.retryFailed() }) { Text("Retry now") }
                            }
                            TextButton(onClick = { confirmCancelAll = true }) { Text("Cancel all", color = TextSecondary) }
                        },
                    )
                }
                if (downloading.isNotEmpty()) {
                    item(key = "d-head") { SectionHeader("Downloading") }
                    items(downloading, key = { "d:${it.id}" }) { song -> PendingSongRow(song, { play(downloading, song) }, Modifier.animateItem()) }
                }
                if (queued.isNotEmpty()) {
                    item(key = "q-head") { SectionHeader("Up next") }
                    val shown = queued.take(SECTION_LIMIT)
                    items(shown, key = { "q:${it.id}" }) { song -> PendingSongRow(song, { play(shown, song) }, Modifier.animateItem()) }
                    if (queued.size > SECTION_LIMIT) {
                        item(key = "q-more") { MoreLine("+${queued.size - SECTION_LIMIT} more waiting") }
                    }
                }
                if (failed.isNotEmpty()) {
                    item(key = "f-head") {
                        SectionHeader("Couldn't download") {
                            if (!storageFull) TextButton(onClick = { c.downloads.retryFailed() }) { Text("Retry") }
                        }
                        // Storage and backoff problems are already explained in the status card.
                        if (!storageFull && !backingOff) lastError?.let { MoreLine(it) }
                    }
                    val shown = failed.take(SECTION_LIMIT)
                    items(shown, key = { "f:${it.id}" }) { song -> Column(Modifier.animateItem()) { SongRow(song, onClick = { play(shown, song) }) } }
                    if (failed.size > SECTION_LIMIT) {
                        item(key = "f-more") { MoreLine("+${failed.size - SECTION_LIMIT} more") }
                    }
                }
            }
            finishedSection(finished) { song -> play(finished, song) }
            item(key = "space") {
                MoreLine("${formatBytes(bytes)} of music downloaded to this phone")
            }
        }
    }
}

private fun LazyListScope.finishedSection(finished: List<SongEntity>, onPlay: (SongEntity) -> Unit) {
    if (finished.isEmpty()) return
    item(key = "done-head") { SectionHeader("Finished") }
    items(finished, key = { "done:${it.id}" }) { song -> Column(Modifier.animateItem()) { SongRow(song, onClick = { onPlay(song) }) } }
}

@Composable
private fun MoreLine(text: String) {
    Text(
        text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 8.dp),
    )
}

/** A song waiting to download, with a progress bar while it's on its way. Collects only its own progress. */
@Composable
private fun PendingSongRow(song: SongEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    // null when not downloading; 0 when the size isn't known yet.
    val progress by remember(song.id) {
        c.downloads.progress.map { if (song.id in it) it[song.id] ?: 0f else null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(null)
    Column(modifier) {
        SongRow(song, onClick = onClick)
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress ?: 0f },
                modifier = Modifier.fillMaxWidth().padding(start = Dimens.Gutter + Dimens.RowCover + Dimens.CoverGap, end = Dimens.Gutter).height(3.dp),
                trackColor = TextSecondary.copy(alpha = 0.25f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}
