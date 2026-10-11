package im.flume.hearth.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import im.flume.hearth.container
import im.flume.hearth.spoken.BookCandidate
import im.flume.hearth.spoken.BookMatch
import im.flume.hearth.spoken.LocalBookEntity
import im.flume.hearth.spoken.LocalMatchState
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.util.userMessage
import kotlinx.coroutines.CancellationException
import im.flume.hearth.spoken.LocalBookRow
import im.flume.hearth.spoken.LocalUploadState
import im.flume.hearth.spoken.SpokenFormat
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.UnavailablePage
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.theme.Destructive
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.launch

// Audiobooks from a folder on this phone: the Settings part (pick / remove folders, rescan), the
// rows in Your Library's Audiobooks tab, and the book page. Server books are in SpokenScreens.kt.

/** Settings → Podcasts & audiobooks: folders on the phone that hold audiobooks. */
@Composable
fun PhoneAudiobookFolders() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val local = c.spoken.local
    val folders by local.folders.collectAsStateWithLifecycle()
    val scanning by local.scanning.collectAsStateWithLifecycle()
    val problem by local.scanProblem.collectAsStateWithLifecycle()
    val books by remember { local.books() }.collectAsStateWithLifecycle(emptyList())
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            local.addFolder(uri)
            actions.showMessage("Looking for audiobooks in that folder…")
        }
    }

    Text(
        "Audiobooks on this phone",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = Dimens.Gutter, end = Dimens.Gutter, top = Dimens.Gutter),
    )
    Text(
        if (folders.isEmpty()) "Pick a folder of audiobooks on this phone to listen to them here. Each sub-folder is one book; a single file (like an .m4b) is a book on its own."
        else "${plural(books.size, "book")} found. Each sub-folder is one book. You can add any of them to the server from its page.",
        color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 4.dp),
    )
    folders.forEach { f ->
        MediaRow(
            f.name, null, onClick = null,
            cover = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Folder, null, tint = TextSecondary) } },
            trailing = { TextButton(onClick = { confirmRemove = f.uri }) { Text("Remove", color = Destructive) } },
        )
    }
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { runCatching { picker.launch(null) }.onFailure { actions.showMessage("This phone has no folder picker") } }) {
            Text(if (folders.isEmpty()) "Add a folder on this phone" else "Add another folder")
        }
        if (folders.isNotEmpty()) {
            TextButton(enabled = !scanning, onClick = {
                scope.launch {
                    val n = local.rescan()
                    actions.showMessage("${plural(n, "audiobook")} on this phone")
                }
            }) { Text(if (scanning) "Scanning…" else "Rescan") }
        }
    }
    problem?.let {
        Text(it, color = Destructive, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Dimens.Gutter))
    }

    confirmRemove?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove this folder?") },
            text = { Text("Its books leave your library. The files stay on your phone, and anything already added to the server stays there.") },
            confirmButton = { TextButton(onClick = { confirmRemove = null; local.removeFolder(uri) }) { Text("Remove", color = Destructive) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Your Library → Audiobooks: the books from folders on this phone, above the server's, in the tab's
 * sort order ([sort]: 0 A–Z, 1 recently added, 2 author, 3 recently listened).
 */
@Composable
fun PhoneAudiobooks(sort: Int) {
    val c = LocalContext.current.container
    val books by remember { c.spoken.local.books() }.collectAsStateWithLifecycle(emptyList())
    val sorted = remember(books, sort) {
        when (sort) {
            1 -> books.sortedByDescending { it.book.addedAt }
            2 -> books.sortedWith(compareBy({ it.book.author.orEmpty().lowercase() }, { it.book.title.lowercase() }))
            3 -> books.sortedByDescending { it.progressUpdatedAt ?: 0L }
            else -> books
        }
    }
    Column { sorted.forEach { PhoneBookRow(it) } }
}

@Composable
private fun PhoneBookRow(row: LocalBookRow) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val b = row.book
    val pos = row.positionSec ?: 0.0
    val status = when {
        row.finished -> "Finished"
        pos > 0 -> SpokenFormat.remaining(b.durationSec - pos)
        else -> SpokenFormat.length(b.durationSec).ifEmpty { null }
    }
    Column {
        MediaRow(
            b.title,
            listOfNotNull("On this phone", b.author, status).joinToString(" • "),
            onClick = { actions.open(bookRoute(b.id)) },
            coverUrl = c.spokenCover(b.id, 150),
            fallback = b.author ?: b.title,
            subtitleIcon = { Icon(Icons.Outlined.PhoneAndroid, null, tint = TextSecondary, modifier = Modifier.size(14.dp)) },
        )
        SpokenFormat.fraction(row.positionSec, b.durationSec, row.finished)?.let { f ->
            ListenedBar(f, Modifier.padding(start = Dimens.Gutter + Dimens.RowCover + Dimens.CoverGap, bottom = 6.dp).width(96.dp))
        }
    }
}

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
    LaunchedEffect(id) { kotlinx.coroutines.delay(300); loaded = true }

    val r = row
    if (r == null) {
        if (loaded) UnavailablePage(Icons.Outlined.PhoneAndroid, "This book isn't on the phone any more")
        else Box(Modifier.fillMaxSize())
        return
    }
    val b = r.book
    b.serverItemId?.let { serverId ->
        // Added to the server: that copy is the one to use now.
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth()) { im.flume.hearth.ui.components.BackButton() }
            im.flume.hearth.ui.components.EmptyState(Icons.Outlined.CloudUpload, "\"${b.title}\" is on the server now.", action = "Open it") { actions.open(bookRoute(serverId)) }
        }
        return
    }
    if (b.hidden) return UnavailablePage(Icons.Outlined.VisibilityOff, "Removed from your library. The files are still on your phone.")
    val isThis = now?.libraryItemId == id
    var livePosSec by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(isThis, playerState.isPlaying) {
        if (!isThis) { livePosSec = null; return@LaunchedEffect }
        while (true) {
            livePosSec = c.player.spokenPositionMs / 1000.0
            kotlinx.coroutines.delay(if (playerState.isPlaying) 1000 else 3000)
        }
    }
    val posSec = livePosSec ?: r.positionSec ?: 0.0
    val started = (r.positionSec ?: 0.0) > 0 && !r.finished
    val currentChapter = if (isThis || started) im.flume.hearth.spoken.ChapterMarks.current(chapters, posSec) else -1
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
                    "\"${b.title}\" (${im.flume.hearth.ui.components.formatBytes(b.sizeBytes)}) is sent to your audiobook library, so you can listen on any device. " +
                        "Where you are in it carries over. After that the server's copy is the one in your library: download it there to listen offline. The files on this phone aren't touched."
                )
            },
            confirmButton = { TextButton(onClick = { confirmUpload = false; startUpload() }) { Text("Add to server") } },
            dismissButton = { TextButton(onClick = { confirmUpload = false }) { Text("Cancel") } },
        )
    }
    FindDetailsSheet(findDetails, b, signedIn = account != null && account?.needsSignIn != true) { findDetails = false }
    ActionSheet(menu, { menu = false }, title = b.title, subtitle = b.author, showCover = false) {
        if (!busy) MenuItem("Add to server", Icons.Outlined.CloudUpload) { menu = false; confirmUpload = true }
        MenuItem("Find details", Icons.Default.Search) { menu = false; findDetails = true }
        if (r.finished || started) {
            MenuItem("Mark as not started", Icons.Outlined.RadioButtonUnchecked) { menu = false; c.appScope.launch { c.spoken.markFinished(id, null, false) } }
        }
        if (!r.finished) {
            MenuItem("Mark as finished", Icons.Outlined.CheckCircle) {
                menu = false
                c.appScope.launch { c.spoken.markFinished(id, null, true) }
                actions.showUndo("Marked as finished") { c.spoken.markFinished(id, null, false) }
            }
        }
        MenuItem("Remove from library", Icons.Outlined.VisibilityOff) {
            menu = false
            c.appScope.launch { local.setHidden(id, true) }
            actions.showUndo("Removed \"${b.title}\". The files are still on your phone.") { local.setHidden(id, false) }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            SpokenHeader(
                c.spokenCover(id, 600), b.title,
                listOfNotNull(
                    b.author, b.narrator?.let { "Read by $it" }, b.series,
                    listOfNotNull("On this phone", SpokenFormat.length(b.durationSec).ifEmpty { null }, b.publishedYear).joinToString(" • "),
                ),
            )
        }
        item(key = "progress") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    r.finished -> Text("Finished", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    posSec > 0 && b.durationSec > 0 -> {
                        ListenedBar((posSec / b.durationSec).toFloat().coerceIn(0f, 1f), Modifier.widthIn(max = 280.dp).fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(SpokenFormat.remaining(b.durationSec - posSec), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item(key = "buttons") {
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
                Spacer(Modifier.weight(1f))
                val playing = isThis && playerState.isPlaying
                Button(
                    onClick = { if (isThis) c.player.playPause() else playBook(c, actions, id, downloaded = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
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
        item(key = "details") { DetailsSource(b.matchState, b.matchSource) { findDetails = true } }
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
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (state) {
            LocalUploadState.UPLOADING -> {
                Text(
                    if (progress == null) "Getting ready to add to the server…" else "Adding to the server… ${(progress * 100).toInt()}%",
                    color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                )
                if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            LocalUploadState.WAITING_FOR_SERVER -> {
                Text("Sent. Waiting for the server to add it to your library…", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LocalUploadState.FAILED -> {
                Text("Couldn't add it to the server: ${error ?: "something went wrong"}", color = Destructive, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}

/**
 * Under the play button: where the book's details came from, with a way to change them ("Not
 * right?"), or "Find details" when the files' own names are in use.
 */
@Composable
private fun DetailsSource(matchState: String?, matchSource: String?, onOpen: () -> Unit) {
    val matched = matchState == LocalMatchState.AUTO.name || matchState == LocalMatchState.CONFIRMED.name
    if (!matched) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { TextButton(onClick = onOpen) { Text("Find details") } }
        return
    }
    val action = if (matchState == LocalMatchState.AUTO.name) "Not right?" else "Change"
    val accent = Accent
    Text(
        buildAnnotatedString {
            append("Details from ${BookMatch.providerName(matchSource)} · ")
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(action) }
        },
        color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = Dimens.Gutter, vertical = 10.dp),
    )
}

/**
 * Pick the right book from the server's search (Audible), search again with other words, or go back
 * to the file's own name. Opens on the results found when the book was scanned, if any.
 */
@Composable
private fun FindDetailsSheet(open: Boolean, book: LocalBookEntity, signedIn: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val details = c.spoken.local.details
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(BookMatch.query(book.fileTitle ?: book.title, book.fileAuthor).title) }
    var candidates by remember { mutableStateOf<List<BookCandidate>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun search() {
        if (query.isBlank() || searching) return
        keyboard?.hide()
        scope.launch {
            searching = true; error = null
            try {
                candidates = details.searchAgain(book.id, query.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't search: ${userMessage(e)}"
            } finally {
                searching = false
            }
        }
    }

    LaunchedEffect(book.id) {
        val saved = details.saved(book.id)
        if (saved != null) { query = saved.title; candidates = saved.candidates }
        else if (signedIn) search()
    }

    ActionSheet(true, onDismiss, title = "Find details", subtitle = book.fileTitle ?: book.title, showCover = false) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (!signedIn) {
                Text(
                    "Sign in to podcasts & audiobooks in Settings to look up a book's details.",
                    color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 8.dp),
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Title") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = !searching && query.isNotBlank(), onClick = { search() }) { Text("Search again") }
                }
                val problem = error
                when {
                    searching -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 12.dp))
                    problem != null -> Text(problem, color = Destructive, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 8.dp))
                    candidates?.isEmpty() == true -> Text(
                        "Nothing found. Try fewer words, or the title as it's printed on the cover.",
                        color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                candidates.orEmpty().forEach { cand ->
                    val current = book.matchSource != null && cand.source == book.matchSource
                    MediaRow(
                        cand.title,
                        listOfNotNull(cand.author, cand.narrator?.let { "Read by $it" }, SpokenFormat.length(cand.durationSec).ifEmpty { null }, cand.publishedYear)
                            .joinToString(" • ").ifEmpty { null },
                        onClick = {
                            onDismiss()
                            c.appScope.launch { details.choose(book.id, cand) }
                            actions.showMessage("Using the details of \"${cand.title}\"")
                        },
                        coverUrl = cand.cover,
                        fallback = cand.title,
                        trailing = if (current) { { Icon(Icons.Default.Check, "In use", tint = Accent, modifier = Modifier.padding(end = 12.dp)) } } else null,
                    )
                }
            }
            val matched = book.matchState == LocalMatchState.AUTO.name || book.matchState == LocalMatchState.CONFIRMED.name
            if (matched) {
                MenuItem("Use the file's own name", Icons.Default.History) {
                    onDismiss()
                    c.appScope.launch { details.useFileNames(book.id) }
                    actions.showMessage("Back to \"${book.fileTitle ?: book.title}\"")
                }
            }
        }
    }
}
