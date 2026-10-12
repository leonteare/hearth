// The page of an audiobook from a folder on this phone, and how adding it to the server is going.
// Server books have their own page in BookScreen.kt.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import im.flume.hearth.core.playback.container
import im.flume.hearth.domain.spoken.BookMatch
import im.flume.hearth.domain.spoken.ChapterMarks
import im.flume.hearth.core.data.spoken.LocalMatchState
import im.flume.hearth.core.data.spoken.LocalUploadState
import im.flume.hearth.domain.spoken.SpokenFormat
import im.flume.hearth.core.ui.components.ActionSheet
import im.flume.hearth.core.ui.components.BackButton
import im.flume.hearth.core.ui.components.EmptyState
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.MenuItem
import im.flume.hearth.core.ui.components.SectionHeader
import im.flume.hearth.core.ui.components.UnavailablePage
import im.flume.hearth.core.ui.components.formatBytes
import im.flume.hearth.core.ui.theme.CoverSize
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import im.flume.hearth.core.ui.screens.ChapterRow
import im.flume.hearth.core.ui.screens.Description
import im.flume.hearth.core.ui.screens.ListenedBar
import im.flume.hearth.core.ui.screens.LocalSpokenNow
import im.flume.hearth.core.ui.screens.SpokenHeader
import im.flume.hearth.core.ui.screens.bookRoute
import im.flume.hearth.core.ui.screens.playBook
import im.flume.hearth.core.ui.screens.spokenCover

/** The page of a book from a folder on the phone: play, chapters (one per file), mark finished, add to server. */
@Composable
fun LocalBookScreen(id: String) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val local = c.spoken.local
    val row by remember(id) { local.book(id) }.collectAsStateWithLifecycle(null)
    val chapters by remember(id) { local.chapters(id) }.collectAsStateWithLifecycle(emptyList())
    val uploads by local.uploadProgress.collectAsStateWithLifecycle()
    val account by c.spoken.account.collectAsStateWithLifecycle()
    val now = LocalSpokenNow.current
    val playerState by c.player.state.collectAsStateWithLifecycle()
    var loaded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmUpload by remember { mutableStateOf(false) }
    var findDetails by remember { mutableStateOf(false) }
    LaunchedEffect(id) { delay(300); loaded = true }

    val r = row
    if (r == null) {
        if (loaded) UnavailablePage(HearthTheme.icons.phone, "This book isn't on the phone any more")
        else Box(Modifier.fillMaxSize())
        return
    }
    val b = r.book
    b.serverItemId?.let { serverId ->
        // Added to the server: that copy is the one to use now.
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth()) { BackButton() }
            EmptyState(HearthTheme.icons.addToServer, "\"${b.title}\" is on the server now.", action = "Open it") { actions.open(bookRoute(serverId)) }
        }
        return
    }
    if (b.hidden) return UnavailablePage(HearthTheme.icons.hidden, "Removed from your library. The files are still on your phone.")
    val isThis = now?.libraryItemId == id
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
    val posSec = livePosSec ?: r.positionSec ?: 0.0
    val started = (r.positionSec ?: 0.0) > 0 && !r.finished
    val currentChapter = if (isThis || started) ChapterMarks.current(chapters, posSec) else -1
    val uploadState = b.uploadState?.let { s -> LocalUploadState.entries.firstOrNull { it.name == s } }
    val busy = uploadState == LocalUploadState.UPLOADING || uploadState == LocalUploadState.WAITING_FOR_SERVER

    fun startUpload() {
        if (account == null || account?.needsSignIn == true) { actions.showMessage("Sign in to podcasts & audiobooks in Settings first"); return }
        local.upload(id)
        actions.showMessage("Adding \"${b.title}\" to the server")
    }

    if (confirmUpload) {
        AlertDialog(
            onDismissRequest = { confirmUpload = false },
            title = { Text("Add to the server?") },
            text = {
                Text(
                    "\"${b.title}\" (${formatBytes(b.sizeBytes)}) is sent to your audiobook library, so you can listen on any device. " +
                        "Where you are in it carries over. After that the server's copy is the one in your library: download it there to listen offline. The files on this phone aren't touched."
                )
            },
            confirmButton = { TextButton(onClick = { confirmUpload = false; startUpload() }) { Text("Add to server") } },
            dismissButton = { TextButton(onClick = { confirmUpload = false }) { Text("Cancel") } },
        )
    }
    FindDetailsSheet(findDetails, b, signedIn = account != null && account?.needsSignIn != true) { findDetails = false }
    ActionSheet(menu, { menu = false }, title = b.title, subtitle = b.author, showCover = false) {
        if (!busy) MenuItem("Add to server", HearthTheme.icons.addToServer) { menu = false; confirmUpload = true }
        // Where the title, writer and cover came from, and the way to look them up or change them.
        val matched = b.matchState == LocalMatchState.AUTO.name || b.matchState == LocalMatchState.CONFIRMED.name
        MenuItem(
            if (matched) "Change details (from ${BookMatch.providerName(b.matchSource)})" else "Find details",
            HearthTheme.icons.search,
        ) { menu = false; findDetails = true }
        if (r.finished || started) {
            MenuItem("Mark as not started", HearthTheme.icons.markUnplayed) { menu = false; c.appScope.launch { c.spoken.markFinished(id, null, false) } }
        }
        if (!r.finished) {
            MenuItem("Mark as finished", HearthTheme.icons.markPlayed) {
                menu = false
                c.appScope.launch { c.spoken.markFinished(id, null, true) }
                actions.showUndo("Marked as finished") { c.spoken.markFinished(id, null, false) }
            }
        }
        MenuItem("Remove from library", HearthTheme.icons.removeFromLibrary) {
            menu = false
            c.appScope.launch { local.setHidden(id, true) }
            actions.showUndo("Removed \"${b.title}\". The files are still on your phone.") { local.setHidden(id, false) }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = HearthTheme.spacing.xl)) {
        item(key = "header") {
            SpokenHeader(
                c.spokenCover(id, CoverSize.LARGE), b.title,
                listOfNotNull(
                    b.author, b.narrator?.let { "Read by $it" }, b.series,
                    listOfNotNull("On this phone", SpokenFormat.length(b.durationSec).ifEmpty { null }, b.publishedYear).joinToString(" • "),
                ),
                onMenu = { menu = true },
            )
        }
        item(key = "progress") {
            Column(Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    r.finished -> Text("Finished", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
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
                Spacer(Modifier.weight(1f))
                val playing = isThis && playerState.isPlaying
                Button(
                    onClick = { if (isThis) c.player.playPause() else playBook(c, actions, id, downloaded = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = HearthTheme.spacing.m),
                ) {
                    Icon(if (playing) HearthTheme.icons.pause else HearthTheme.icons.play, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            playing -> "Pause"
                            isThis || started -> "Resume"
                            r.finished -> "Listen again"
                            else -> "Play"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        item(key = "upload") { UploadStatus(uploadState, b.uploadError, uploads[id], onRetry = { startUpload() }) }
        if (!b.description.isNullOrBlank()) item(key = "description") { Description(b.description) }
        if (chapters.isNotEmpty()) {
            item(key = "chapters-head") { SectionHeader("Chapters") }
            itemsIndexed(chapters, key = { _, ch -> "ch:${ch.chapterIndex}" }) { i, ch ->
                ChapterRow(
                    index = i, title = ch.title, startSec = ch.startSec, lengthSec = ch.endSec - ch.startSec,
                    current = i == currentChapter, done = i < currentChapter || r.finished,
                ) {
                    if (isThis) c.player.seekSpoken((ch.startSec * 1000).toLong()).also { if (!playerState.isPlaying) c.player.resume() }
                    else playBook(c, actions, id, downloaded = true, startSec = ch.startSec)
                }
            }
        }
    }
}

/** How adding the book to the server is going: sending (with progress), waiting for the server, or what went wrong. */
@Composable
private fun UploadStatus(state: LocalUploadState?, error: String?, progress: Float?, onRetry: () -> Unit) {
    if (state == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (state) {
            LocalUploadState.UPLOADING -> {
                Text(
                    if (progress == null) "Getting ready to add to the server…" else "Adding to the server… ${(progress * 100).toInt()}%",
                    color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium,
                )
                if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            LocalUploadState.WAITING_FOR_SERVER -> {
                Text("Sent. Waiting for the server to add it to your library…", color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LocalUploadState.FAILED -> {
                Text("Couldn't add it to the server: ${error ?: "something went wrong"}", color = HearthTheme.colors.destructive, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}
