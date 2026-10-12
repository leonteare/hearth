// Rows for podcasts and audiobooks: episodes, shows, books, chapters and podcast directory results.
package im.flume.hearth.core.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.network.AbsPodcastSearchResult
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.data.spoken.BookRow
import im.flume.hearth.domain.spoken.DirectoryState
import im.flume.hearth.core.data.spoken.EpisodeRow
import im.flume.hearth.core.data.spoken.PodcastEntity
import im.flume.hearth.domain.spoken.SpokenDiscovery
import im.flume.hearth.core.data.spoken.SpokenDownloadState
import im.flume.hearth.domain.spoken.SpokenFormat
import im.flume.hearth.core.ui.components.CoverArt
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.MediaRow
import im.flume.hearth.core.ui.components.plural
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.common.userMessage
import kotlinx.coroutines.launch

/**
 * One episode: title (two lines), "date · length / time left", how far you got and whether it's on the
 * phone. Tap plays it; ⋯ (or long-press) opens its menu. [playButton] puts a play button at the end
 * instead of ⋯ (Home's "New episodes").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeListRow(
    row: EpisodeRow,
    modifier: Modifier = Modifier,
    showCover: Boolean = false,
    withShow: Boolean = false,
    playButton: Boolean = false,
    showGoToShow: Boolean = withShow,
    /** Put before the usual line (Search's "Episode"), so it reads apart from songs and shows. */
    label: String? = null,
) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val haptics = LocalHapticFeedback.current
    val now = LocalSpokenNow.current
    var menu by remember { mutableStateOf(false) }
    val e = row.episode
    val isCurrent = now?.episodeId == e.id
    val downloaded = row.downloadState == SpokenDownloadState.DONE
    val playable = e.ino != null
    val accent = MaterialTheme.colorScheme.primary
    val play = { playEpisode(c, actions, e.libraryItemId, e.id, downloaded) }
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = playable,
                onClick = play,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
            )
            .padding(start = HearthTheme.spacing.gutter, end = HearthTheme.spacing.xs, top = HearthTheme.spacing.rowPaddingV, bottom = HearthTheme.spacing.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) {
            CoverArt(null, HearthTheme.spacing.rowCover, requestSize = 150, fallback = row.podcastTitle, url = c.spokenCover(e.libraryItemId, 150))
            Spacer(Modifier.width(HearthTheme.spacing.coverGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                e.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isCurrent -> accent
                    row.finished || !playable -> HearthTheme.colors.textSecondary
                    else -> HearthTheme.colors.text
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.downloadState != null && row.downloadState != SpokenDownloadState.REMOVED) {
                    SpokenDownloadIcon(row.downloadState)
                    Spacer(Modifier.width(HearthTheme.spacing.xs))
                }
                Text(
                    listOfNotNull(label, SpokenFormat.episodeLine(row, withShow = withShow)).joinToString(" • "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium,
                )
            }
            SpokenFormat.fraction(row.progressSec, e.durationSec, row.finished)?.let { f ->
                Spacer(Modifier.height(HearthTheme.spacing.xs))
                ListenedBar(f, Modifier.width(96.dp))
            }
        }
        if (playButton) {
            IconButton(onClick = play, enabled = playable) {
                Icon(HearthTheme.icons.play, "Play ${e.title}", tint = HearthTheme.colors.text)
            }
        } else {
            IconButton(onClick = { menu = true }) { Icon(HearthTheme.icons.more, "More", tint = HearthTheme.colors.textSecondary) }
        }
    }
    EpisodeMenu(row, menu, onDismiss = { menu = false }, showGoToShow = showGoToShow)
}

@Composable
fun PodcastListRow(p: PodcastEntity, newCount: Int = 0, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    MediaRow(
        p.title,
        listOfNotNull("Podcast", p.author?.takeIf { it.isNotBlank() }, if (newCount > 0) "$newCount new" else null).joinToString(" • "),
        onClick = { actions.open(podcastRoute(p.id)) },
        modifier = modifier,
        coverUrl = c.spokenCover(p.id, 150),
        subtitleColor = HearthTheme.colors.textSecondary,
        trailing = if (newCount > 0) {
            { Box(Modifier.padding(end = HearthTheme.spacing.l).size(8.dp).clip(HearthTheme.shapes.round).background(MaterialTheme.colorScheme.primary)) }
        } else null,
    )
}

@Composable
fun BookListRow(b: BookRow, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val book = b.book
    val status = when {
        b.finished -> "Finished"
        (b.progressSec ?: 0.0) > 0 -> SpokenFormat.remaining(book.durationSec - (b.progressSec ?: 0.0))
        else -> SpokenFormat.length(book.durationSec).ifEmpty { null }
    }
    Column(modifier) {
        MediaRow(
            book.title,
            listOfNotNull("Audiobook", book.author, status).joinToString(" • "),
            onClick = { actions.open(bookRoute(book.id)) },
            coverUrl = c.spokenCover(book.id, 150),
            subtitleIcon = if (b.downloadState == SpokenDownloadState.DONE || b.downloadState == SpokenDownloadState.DOWNLOADING || b.downloadState == SpokenDownloadState.QUEUED) {
                { SpokenDownloadIcon(b.downloadState) }
            } else null,
        )
        SpokenFormat.fraction(b.progressSec, book.durationSec, b.finished)?.let { f ->
            ListenedBar(f, Modifier.padding(start = HearthTheme.spacing.gutter + HearthTheme.spacing.rowCover + HearthTheme.spacing.coverGap, bottom = 6.dp).width(96.dp))
        }
    }
}

/** A chapter in a list: number, title, where it starts and how long it is. Tap to listen from there. */
@Composable
fun ChapterRow(index: Int, title: String, startSec: Double, lengthSec: Double, current: Boolean, done: Boolean = false, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth()
            .background(if (current) accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", Modifier.width(32.dp), color = if (current) accent else HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "Chapter ${index + 1}" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = when {
                    current -> accent
                    done -> HearthTheme.colors.textSecondary
                    else -> HearthTheme.colors.text
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.width(HearthTheme.spacing.s))
        Column(horizontalAlignment = Alignment.End) {
            Text(SpokenFormat.clock(startSec), color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium)
            if (lengthSec > 0) Text(SpokenFormat.length(lengthSec), color = HearthTheme.colors.textSecondary.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * A podcast from the directory in Search: Follow adds it to the server (if it isn't there yet) and
 * follows it; one already on the server opens its page.
 */
@Composable
fun DirectoryRow(r: AbsPodcastSearchResult, library: List<PodcastEntity>, username: String?, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val (state, existing) = remember(r, library, username) { SpokenDiscovery.state(r, library, username) }
    var busy by remember(r.feedUrl) { mutableStateOf(false) }
    MediaRow(
        r.title,
        listOfNotNull("Podcast", r.artistName?.takeIf { it.isNotBlank() }, r.trackCount?.takeIf { it > 0 }?.let { plural(it, "episode") }).joinToString(" • ").ifEmpty { null },
        // On the server: its page. Not yet: the feed's page, where any episode can be fetched.
        onClick = existing?.let { e -> { actions.open(podcastRoute(e.id)) } } ?: r.feedUrl?.let { f -> { actions.open(podcastFeedRoute(f)) } },
        modifier = modifier,
        coverUrl = r.cover,
        trailing = {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(horizontal = HearthTheme.spacing.l).size(20.dp), strokeWidth = 2.dp)
            } else {
                Box(Modifier.padding(end = HearthTheme.spacing.s)) {
                    FollowButton(following = state == DirectoryState.FOLLOWING) {
                        if (state == DirectoryState.FOLLOWING) {
                            existing?.let { actions.open(podcastRoute(it.id)) }
                            return@FollowButton
                        }
                        val feed = r.feedUrl ?: return@FollowButton
                        busy = true
                        c.appScope.launch {
                            c.spoken.addPodcast(feed, r, follow = true).fold(
                                onSuccess = { actions.showMessage("Following ${r.title}. Its newest episodes are on their way.") },
                                onFailure = { actions.showMessage("Couldn't follow ${r.title}: ${userMessage(it)}") },
                            )
                            busy = false
                        }
                    }
                }
            }
        },
    )
}
