// Podcast episode and audiobook downloads on the Downloads page: their sections, rows and options sheet.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import im.flume.hearth.spoken.SpokenDownloadRow
import im.flume.hearth.spoken.SpokenDownloadState
import im.flume.hearth.spoken.SpokenKey
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.formatBytes
import im.flume.hearth.ui.theme.Destructive
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Podcast / audiobook downloads in a section of their own (when no songs share that section). */
internal fun LazyListScope.spokenSection(
    key: String,
    title: String,
    rows: List<SpokenDownloadRow>,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    if (rows.isEmpty()) return
    item(key = "$key-head") { SectionHeader(title, trailing = trailing) }
    spokenRows(key, rows)
}

internal fun LazyListScope.spokenRows(key: String, rows: List<SpokenDownloadRow>) {
    items(rows.take(SECTION_LIMIT), key = { "$key:${it.download.libraryItemId}/${it.download.episodeId}" }) { r -> SpokenDownloadListRow(r, Modifier.animateItem()) }
}

/** An episode or book download: cover, what it is, and progress while it's on its way. Tap opens its page. */
@Composable
private fun SpokenDownloadListRow(r: SpokenDownloadRow, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val d = r.download
    val key = SpokenKey(d.libraryItemId, d.episodeId)
    val progress by remember(key) {
        c.spoken.downloads.progress.map { if (key in it) it[key] ?: 0f else null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(null)
    var menu by remember { mutableStateOf(false) }
    val title = r.title ?: if (d.isBook) "Audiobook" else "Episode"
    Column(modifier) {
        MediaRow(
            title,
            when (d.state) {
                SpokenDownloadState.FAILED -> d.error ?: "Couldn't download"
                SpokenDownloadState.DONE -> listOfNotNull(r.subtitle, formatBytes(d.bytes).takeIf { d.bytes > 0 }).joinToString(" · ")
                else -> listOfNotNull(if (d.isBook) "Audiobook" else "Podcast", r.subtitle).joinToString(" · ")
            },
            onClick = { actions.open(if (d.isBook) bookRoute(d.libraryItemId) else podcastRoute(d.libraryItemId)) },
            coverUrl = c.spokenCover(d.libraryItemId, 150),
            fallback = r.subtitle ?: title,
            subtitleColor = if (d.state == SpokenDownloadState.FAILED) Destructive else TextSecondary,
            trailing = { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) } },
        )
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
    ActionSheet(menu, { menu = false }, title = title, subtitle = r.subtitle, coverUrl = c.spokenCover(d.libraryItemId, 150), fallback = r.subtitle) {
        if (d.state == SpokenDownloadState.FAILED) {
            MenuItem("Try again", Icons.Default.Refresh) { menu = false; c.spoken.downloads.retryFailed() }
        }
        MenuItem(
            if (d.state == SpokenDownloadState.DONE) "Remove download" else "Cancel download",
            Icons.Default.Close,
        ) {
            menu = false
            if (d.state == SpokenDownloadState.DONE && !d.isBook) removeEpisodeDownload(c, actions, d.libraryItemId, d.episodeId)
            else c.spoken.downloads.remove(d.libraryItemId, d.episodeId)
        }
        MenuItem(if (d.isBook) "Go to book" else "Go to show", if (d.isBook) Icons.AutoMirrored.Filled.MenuBook else Icons.Default.Podcasts) {
            menu = false; actions.open(if (d.isBook) bookRoute(d.libraryItemId) else podcastRoute(d.libraryItemId))
        }
    }
}
