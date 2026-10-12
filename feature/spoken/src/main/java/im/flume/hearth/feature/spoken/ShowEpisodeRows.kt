package im.flume.hearth.feature.spoken

// A show page's episode list pieces: the filter box, one feed episode row, and getting an episode onto the server.

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.playback.AppContainer
import im.flume.hearth.core.playback.container
import im.flume.hearth.domain.spoken.FeedEpisode
import im.flume.hearth.domain.spoken.FetchPhase
import im.flume.hearth.domain.spoken.FetchProgress
import im.flume.hearth.domain.spoken.FetchThen
import im.flume.hearth.domain.spoken.ServerFetch
import im.flume.hearth.domain.spoken.SpokenFormat
import im.flume.hearth.core.ui.components.ActionSheet
import im.flume.hearth.core.ui.components.Actions
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.MenuItem
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.ui.theme.NotOnServerAlpha
import im.flume.hearth.core.ui.screens.spokenCover
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun EpisodeFilterField(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter).padding(bottom = HearthTheme.spacing.s)
            .heightIn(min = 44.dp).clip(HearthTheme.shapes.card).background(HearthTheme.colors.surfaceHigh).padding(start = HearthTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HearthTheme.icons.search, null, tint = HearthTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = HearthTheme.colors.text),
            cursorBrush = SolidColor(HearthTheme.colors.text),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) Text("Find an episode", color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyLarge)
                inner()
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onChange("") }) { Icon(HearthTheme.icons.close, "Clear", tint = HearthTheme.colors.textSecondary) }
        } else {
            Spacer(Modifier.width(HearthTheme.spacing.m))
        }
    }
}

/** Asks the server for a feed episode, unless offline. */
internal fun getEpisode(c: AppContainer, actions: Actions, podcastId: String?, feedUrl: String, ep: FeedEpisode, then: FetchThen) {
    if (c.network.cannotStream) {
        actions.showMessage("You're offline. Connect to get this episode")
        return
    }
    c.spoken.feeds.getOntoServer(podcastId, feedUrl, ep, then)
}

/**
 * An episode the server doesn't have (yet): dimmed, with a cloud, like music that isn't on the server.
 * Tap has the server fetch it and plays it when it arrives; ⋯ (or long-press) can download it to the
 * phone or just add it to the server.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FeedEpisodeRow(ep: FeedEpisode, podcastId: String?, feedUrl: String, showTitle: String, fetch: ServerFetch?, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val getting = fetch != null && fetch.phase != FetchPhase.FAILED && fetch.phase != FetchPhase.READY
    val failed = fetch?.phase == FetchPhase.FAILED
    val play = {
        if (getting) {
            c.spoken.feeds.getOntoServer(podcastId, feedUrl, ep, FetchThen.PLAY)
            actions.showMessage("It'll start when it's ready")
        } else {
            getEpisode(c, actions, podcastId, feedUrl, ep, FetchThen.PLAY)
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = play,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
            )
            .padding(start = HearthTheme.spacing.gutter, end = HearthTheme.spacing.xs, top = HearthTheme.spacing.rowPaddingV, bottom = HearthTheme.spacing.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                color = if (getting) MaterialTheme.colorScheme.primary else HearthTheme.colors.text.copy(alpha = NotOnServerAlpha),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    getting -> CircularProgressIndicator(
                        Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary, trackColor = HearthTheme.colors.textSecondary.copy(alpha = 0.3f),
                    )
                    failed -> Icon(HearthTheme.icons.failedOutline, "Couldn't get it", Modifier.size(14.dp), tint = HearthTheme.colors.destructive)
                    else -> Icon(HearthTheme.icons.notOnServer, "Not on your server yet", Modifier.size(14.dp), tint = HearthTheme.colors.textSecondary.copy(alpha = NotOnServerAlpha))
                }
                Spacer(Modifier.width(HearthTheme.spacing.xs))
                val line = listOfNotNull(
                    SpokenFormat.date(ep.publishedAt).ifEmpty { null },
                    SpokenFormat.length(ep.durationSec).ifEmpty { null },
                ).joinToString(" · ")
                Text(
                    when {
                        getting -> FetchProgress.label(fetch!!)
                        failed -> "Couldn't get it · tap to try again"
                        else -> line
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = (if (failed) HearthTheme.colors.destructive else HearthTheme.colors.textSecondary).copy(alpha = if (getting) 1f else NotOnServerAlpha),
                )
            }
        }
        IconButton(onClick = { menu = true }) { Icon(HearthTheme.icons.more, "More", tint = HearthTheme.colors.textSecondary) }
    }
    ActionSheet(menu, { menu = false }, title = ep.title, subtitle = showTitle, fallback = showTitle, coverUrl = podcastId?.let { c.spokenCover(it, 150) }) {
        MenuItem(if (getting) "Play when it's ready" else "Play", HearthTheme.icons.play) { menu = false; play() }
        MenuItem("Download to phone", HearthTheme.icons.download) {
            menu = false
            getEpisode(c, actions, podcastId, feedUrl, ep, FetchThen.DOWNLOAD)
        }
        if (!getting) {
            MenuItem("Add to server", HearthTheme.icons.fetchToServer) {
                menu = false
                getEpisode(c, actions, podcastId, feedUrl, ep, FetchThen.NOTHING)
            }
        }
    }
}

/** The show found for the page (null: not on the server), once the phone's list has been read. */
