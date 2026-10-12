// Your Library → Audiobooks: the rows for books from folders on this phone.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.data.LibraryOrder
import im.flume.hearth.spoken.LocalBookRow
import im.flume.hearth.spoken.SpokenFormat
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.TextSecondary

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
            else -> LibraryOrder.alphabetical(books) { it.book.title }
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
