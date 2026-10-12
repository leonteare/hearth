package im.flume.hearth.ui.screens

// The queue while a podcast episode plays ("Up next"): the episode playing, then more from the same show.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.playback.PlayerUiState
import im.flume.hearth.playback.SpokenNowPlaying
import im.flume.hearth.spoken.SpokenLists
import im.flume.hearth.ui.components.playerBackdrop
import im.flume.hearth.ui.components.rememberCoverColor
import im.flume.hearth.ui.theme.HearthTheme

/** The queue in spoken-word mode: what's playing, then more from the same show. */
@Composable
internal fun SpokenQueueScreen(state: PlayerUiState, spoken: SpokenNowPlaying, onBack: (() -> Unit)?) {
    val c = LocalContext.current.container
    val info = rememberSpokenInfo(state, spoken)
    val episodes by remember(spoken.libraryItemId) { c.spoken.episodes(spoken.libraryItemId) }.collectAsStateWithLifecycle(emptyList())
    val upNext = remember(episodes, spoken.episodeId) { SpokenLists.upNext(episodes, spoken.episodeId) }
    val topColor = rememberCoverColor(null, imageUrl = c.spokenCover(spoken.libraryItemId, 300))
    val colors = HearthTheme.colors
    val spacing = HearthTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .playerBackdrop(topColor)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = spacing.xl), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onBack?.invoke() }) { Icon(HearthTheme.icons.collapse, "Close", Modifier.size(32.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("UP NEXT", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                Text(info.subtitle, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.size(48.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = spacing.xl)) {
            episodes.firstOrNull { it.episode.id == spoken.episodeId }?.let { now ->
                item(key = "h-now") { QueueSectionTitle("Now playing") }
                item(key = "now") { EpisodeListRow(now, showCover = true) }
            }
            if (upNext.isNotEmpty()) {
                item(key = "h-more") { QueueSectionTitle("More from ${info.subtitle}") }
                items(upNext, key = { it.episode.id }) { row -> EpisodeListRow(row, Modifier.animateItem(), showCover = true) }
            } else {
                item(key = "none") {
                    Text("You're up to date with this show.", color = colors.textSecondary, modifier = Modifier.padding(spacing.l))
                }
            }
            item(key = "hint") {
                Text(
                    "Tap an episode to play it. Episodes don't play one after another on their own.",
                    color = colors.textSecondary.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(spacing.l),
                )
            }
        }
    }
}

@Composable
private fun QueueSectionTitle(text: String) {
    val spacing = HearthTheme.spacing
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = spacing.gutter, end = spacing.gutter, top = 14.dp, bottom = spacing.xs),
    )
}
