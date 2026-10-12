// Podcast and audiobook sections on Home: Continue listening cards and New episodes.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.AppContainer
import im.flume.hearth.spoken.ContinueItem
import im.flume.hearth.spoken.EpisodeRow
import im.flume.hearth.spoken.SpokenDownloadState
import im.flume.hearth.spoken.SpokenFormat
import im.flume.hearth.spoken.SpokenKey
import im.flume.hearth.ui.components.Actions
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.MediaCard
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.theme.HearthTheme

/** Home: started episodes and books, with how far you got; tap carries on, long-press opens the page. */
fun LazyListScope.continueListeningSection(items: List<ContinueItem>, c: AppContainer, actions: Actions) {
    if (items.isEmpty()) return
    item(key = "continue-listening") {
        SectionHeader("Continue listening")
        LazyRow(contentPadding = PaddingValues(horizontal = HearthTheme.spacing.gutter), horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.carouselSpacing)) {
            items(items, key = { "${it.libraryItemId}/${it.episodeId.orEmpty()}" }) { item -> ContinueCard(item, c, actions) }
        }
    }
}

@Composable
private fun ContinueCard(item: ContinueItem, c: AppContainer, actions: Actions) {
    val states by remember { c.spoken.downloads.states }.collectAsStateWithLifecycle(emptyMap())
    val downloaded = states[SpokenKey(item.libraryItemId, item.episodeId.orEmpty())] == SpokenDownloadState.DONE
    val url = c.spokenCover(item.libraryItemId, 300)
    MediaCard(
        title = item.title,
        subtitle = listOfNotNull(item.subtitle, SpokenFormat.remaining(item.durationSec - item.positionSec)).joinToString(" · "),
        subtitleLines = 2,
        onClick = {
            val episodeId = item.episodeId
            if (episodeId != null) playEpisode(c, actions, item.libraryItemId, episodeId, downloaded)
            else playBook(c, actions, item.libraryItemId, downloaded)
        },
        onLongClick = { actions.open(if (item.isBook) bookRoute(item.libraryItemId) else podcastRoute(item.libraryItemId)) },
        cover = {
            Box(Modifier.size(HearthTheme.spacing.cardWidth)) {
                CoverArt(null, HearthTheme.spacing.cardWidth, fallback = item.subtitle ?: item.title, url = url)
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, HearthTheme.colors.shade.copy(alpha = 0.6f))))
                        .padding(horizontal = HearthTheme.spacing.s, vertical = HearthTheme.spacing.s)
                ) {
                    ListenedBar(item.fraction, Modifier.fillMaxWidth(), color = HearthTheme.colors.onCover)
                }
            }
        },
    )
}

/** Home: the newest episodes of followed shows that nobody's started. */
fun LazyListScope.newEpisodesSection(rows: List<EpisodeRow>) {
    if (rows.isEmpty()) return
    item(key = "new-episodes-head") { SectionHeader("New episodes") }
    items(rows, key = { "new-ep:${it.episode.id}" }) { row ->
        EpisodeListRow(row, Modifier.animateItem(), showCover = true, withShow = true, playButton = true, showGoToShow = true)
    }
}
