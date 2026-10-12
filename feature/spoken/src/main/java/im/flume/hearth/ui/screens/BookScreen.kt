// The audiobook page ("book/{id}") for a book on the server. The show page is in ShowPage.kt.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import im.flume.hearth.container
import im.flume.hearth.spoken.ChapterMarks
import im.flume.hearth.spoken.LocalBookIds
import im.flume.hearth.spoken.SpokenDownloadState
import im.flume.hearth.spoken.SpokenFormat
import im.flume.hearth.spoken.SpokenKey
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.DownloadToggle
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.UnavailablePage
import im.flume.hearth.ui.components.formatBytes
import im.flume.hearth.ui.theme.CoverSize
import im.flume.hearth.ui.theme.HearthTheme
import im.flume.hearth.util.userMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(id: String) {
    // A book from a folder on the phone has its own page (no server, no download).
    if (LocalBookIds.isLocal(id)) return LocalBookScreen(id)
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val book by remember(id) { c.spoken.book(id) }.collectAsStateWithLifecycle(null)
    val chapters by remember(id) { c.spoken.chapters(id) }.collectAsStateWithLifecycle(emptyList())
    val now = LocalSpokenNow.current
    val playerState by c.player.state.collectAsStateWithLifecycle()
    val downloadProgress by remember(id) {
        c.spoken.downloads.progress.map { it[SpokenKey(id)] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(null)
    var refreshing by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        val b = c.db.spoken().bookOnce(id)
        if ((b == null || b.detailsFetchedAt == 0L) && !c.network.cannotStream) runCatching { c.spoken.fetchItem(id) }
        loaded = true
    }
    val row = book
    if (row == null) {
        if (loaded) UnavailablePage(HearthTheme.icons.audiobook, "This book isn't on the server any more")
        else Box(Modifier.fillMaxSize())
        return
    }
    val b = row.book
    val isThis = now?.libraryItemId == id && now.episodeId == null
    // Where you are: live while it's playing, otherwise your saved progress.
    var livePosSec by remember { mutableStateOf<Double?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(isThis, playerState.isPlaying, lifecycle) {
        if (!isThis) { livePosSec = null; return@LaunchedEffect }
        // Only while the app is in the foreground: nothing shows it in the background.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                livePosSec = c.player.spokenPositionMs / 1000.0
                delay(if (playerState.isPlaying) 1000 else 3000)
            }
        }
    }
    val posSec = livePosSec ?: row.progressSec ?: 0.0
    val currentChapter = if (isThis || (row.progressSec ?: 0.0) > 0) ChapterMarks.current(chapters, posSec) else -1
    val downloaded = row.downloadState == SpokenDownloadState.DONE
    val downloading = row.downloadState == SpokenDownloadState.QUEUED || row.downloadState == SpokenDownloadState.DOWNLOADING
    val started = (row.progressSec ?: 0.0) > 0 && !row.finished
    val coverUrl = c.spokenCover(id, CoverSize.LARGE)

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(if (downloading) "Stop downloading?" else "Remove download?") },
            text = { Text("\"${b.title}\" will be removed from this phone. You can still stream it, or download it again later.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; c.spoken.downloads.remove(id) }) {
                    Text(if (downloading) "Stop" else "Remove", color = HearthTheme.colors.destructive)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
    ActionSheet(menu, { menu = false }, title = b.title, subtitle = b.author, showCover = false) {
        if (row.finished || started) {
            MenuItem("Mark as not started", HearthTheme.icons.markUnplayed) {
                menu = false; c.appScope.launch { c.spoken.markFinished(id, null, false) }
            }
        }
        if (!row.finished) {
            MenuItem("Mark as finished", HearthTheme.icons.markPlayed) {
                menu = false
                c.appScope.launch { c.spoken.markFinished(id, null, true) }
                actions.showUndo("Marked as finished") { c.spoken.markFinished(id, null, false) }
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                runCatching { c.spoken.fetchItem(id) }.onFailure { actions.showMessage("Couldn't refresh: ${userMessage(it)}") }
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = HearthTheme.spacing.xl)) {
            item(key = "header") {
                SpokenHeader(
                    coverUrl, b.title,
                    listOfNotNull(
                        b.subtitle,
                        b.author,
                        b.narrator?.let { "Read by $it" },
                        listOfNotNull(b.series, b.publishedYear, SpokenFormat.length(b.durationSec).ifEmpty { null }).joinToString(" • "),
                    ),
                    onMenu = { menu = true },
                )
            }
            item(key = "progress") {
                Column(Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        row.finished -> Text("Finished", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        posSec > 0 && b.durationSec > 0 -> {
                            ListenedBar((posSec / b.durationSec).toFloat().coerceIn(0f, 1f), Modifier.widthIn(max = 280.dp).fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text(SpokenFormat.remaining(b.durationSec - posSec), color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item(key = "buttons") {
                Row(Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter), verticalAlignment = Alignment.CenterVertically) {
                    DownloadToggle(downloaded = downloaded, progress = if (downloading) downloadProgress ?: 0f else null) {
                        when {
                            downloaded || downloading -> confirmRemove = true
                            else -> {
                                c.spoken.downloads.downloadBook(id)
                                actions.showMessage(downloadingMessage(c) + " \"${b.title}\"" + (if (b.sizeBytes > 0) " (${formatBytes(b.sizeBytes)})" else ""))
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    val playing = isThis && playerState.isPlaying
                    Button(
                        onClick = { if (isThis) c.player.playPause() else playBook(c, actions, id, downloaded) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = HearthTheme.spacing.m),
                    ) {
                        Icon(if (playing) HearthTheme.icons.pause else HearthTheme.icons.play, null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                playing -> "Pause"
                                isThis || started -> "Resume"
                                row.finished -> "Listen again"
                                else -> "Play"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            item(key = "description") { Description(b.description) }
            if (chapters.isNotEmpty()) {
                item(key = "chapters-head") { SectionHeader("Chapters") }
                itemsIndexed(chapters, key = { _, ch -> "ch:${ch.chapterIndex}" }) { i, ch ->
                    ChapterRow(
                        index = i, title = ch.title, startSec = ch.startSec, lengthSec = ch.endSec - ch.startSec,
                        current = i == currentChapter, done = i < currentChapter || row.finished,
                    ) {
                        if (isThis) c.player.seekSpoken((ch.startSec * 1000).toLong()).also { if (!playerState.isPlaying) c.player.resume() }
                        else playBook(c, actions, id, downloaded, startSec = ch.startSec)
                    }
                }
            } else if (b.detailsFetchedAt == 0L && c.network.cannotStream) {
                item(key = "no-chapters") {
                    Text("Chapters show up once you're online.", color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(HearthTheme.spacing.l))
                }
            }
        }
    }
}
