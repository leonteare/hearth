// The sheet that looks up a phone book's proper title, writer and cover, or goes back to the file's own name.
package im.flume.hearth.feature.spoken

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import im.flume.hearth.core.playback.container
import im.flume.hearth.domain.spoken.BookCandidate
import im.flume.hearth.domain.spoken.BookMatch
import im.flume.hearth.core.data.spoken.LocalBookEntity
import im.flume.hearth.core.data.spoken.LocalMatchState
import im.flume.hearth.domain.spoken.SpokenFormat
import im.flume.hearth.core.ui.components.ActionSheet
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.MediaRow
import im.flume.hearth.core.ui.components.MenuItem
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Pick the right book from the server's search (Audible), search again with other words, or go back
 * to the file's own name. Opens on the results found when the book was scanned, if any.
 */
@Composable
internal fun FindDetailsSheet(open: Boolean, book: LocalBookEntity, signedIn: Boolean, onDismiss: () -> Unit) {
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
                    color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s),
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter), verticalAlignment = Alignment.CenterVertically) {
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
                    searching -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.m))
                    problem != null -> Text(problem, color = HearthTheme.colors.destructive, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s))
                    candidates?.isEmpty() == true -> Text(
                        "Nothing found. Try fewer words, or the title as it's printed on the cover.",
                        color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s),
                    )
                }
                Spacer(Modifier.height(HearthTheme.spacing.xs))
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
                        trailing = if (current) { { Icon(HearthTheme.icons.check, "In use", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = HearthTheme.spacing.m)) } } else null,
                    )
                }
            }
            val matched = book.matchState == LocalMatchState.AUTO.name || book.matchState == LocalMatchState.CONFIRMED.name
            if (matched) {
                MenuItem("Use the file's own name", HearthTheme.icons.history) {
                    onDismiss()
                    c.appScope.launch { details.useFileNames(book.id) }
                    actions.showMessage("Back to \"${book.fileTitle ?: book.title}\"")
                }
            }
        }
    }
}
