package im.flume.hearth.ui.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import im.flume.hearth.domain.spoken.ContinueItem
import im.flume.hearth.core.testing.Samples
import im.flume.hearth.core.testing.ScreenshotTest
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.theme.HearthTheme
import org.junit.Test

/** Home's pieces. */
class HomeScreenshots : ScreenshotTest() {
    @Test
    fun `the quick tiles at the top of Home`() = snap("quick_tiles") {
        // The same four tiles HomeScreen builds.
        QuickGrid(
            listOf(
                QuickTile("Shuffle my library", HearthTheme.icons.shuffle, MaterialTheme.colorScheme.primary) {},
                QuickTile("Liked Songs", HearthTheme.icons.likeOn, HearthTheme.colors.liked) {},
                QuickTile("Podcasts", HearthTheme.icons.podcast, HearthTheme.colors.podcasts) {},
                QuickTile("Audiobooks", HearthTheme.icons.audiobook, HearthTheme.colors.audiobooks) {},
            )
        )
    }

    @Test
    fun `continue listening shows an episode and a book with how far you got`() {
        val items = listOf(
            ContinueItem("pod1", "ep1", "Episode 1", "The Long Read", positionSec = 1_200.0, durationSec = 3_600.0, lastPlayedAt = 0),
            ContinueItem("bk1", null, "The Lighthouse Keeper", "Mara Ellison", positionSec = 27_000.0, durationSec = 36_000.0, lastPlayedAt = 0),
        )
        val c = container
        snap("continue_listening") {
            val actions = LocalActions.current
            LazyColumn { continueListeningSection(items, c, actions) }
        }
    }

    @Test
    fun `new episodes of followed shows`() = snap("new_episodes") {
        LazyColumn {
            newEpisodesSection(
                listOf(
                    Samples.episodeRow(),
                    Samples.episodeRow(Samples.episode(id = "ep2", title = "Episode 2")),
                )
            )
        }
    }
}
