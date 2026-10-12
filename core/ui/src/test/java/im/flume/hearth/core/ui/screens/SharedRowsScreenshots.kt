package im.flume.hearth.core.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.playback.SpokenNowPlaying
import im.flume.hearth.core.data.spoken.SpokenDownloadState
import im.flume.hearth.core.testing.Samples
import im.flume.hearth.core.testing.ScreenshotTest
import org.junit.Test

/** Rows and headers that Home, Search, Library and the spoken pages share. */
class SharedRowsScreenshots : ScreenshotTest() {
    @Test
    fun `artist and album list rows`() = snap("artist_album_rows") {
        Column {
            ArtistRow(Samples.artist())
            AlbumListRow(Samples.album())
            AlbumListRow(Samples.album(id = "al2", name = "Paper Boats", year = null))
        }
    }

    @Test
    fun `equal width chips with the middle one chosen`() = snap("equal_width_chips") {
        EqualWidthChips(
            options = listOf("Music", "Podcasts", "Audiobooks"), selected = "Podcasts", label = { it },
            textStyle = MaterialTheme.typography.labelLarge, minHeight = 40.dp, onSelect = {},
        )
    }

    @Test
    fun `episode rows, new, half listened and downloaded, played, and playing now`() {
        val playing = SpokenNowPlaying("pod1", episodeId = "ep4", durationMs = 2_520_000, speed = 1f, canGoBackToMusic = false)
        snap("episode_rows") {
            CompositionLocalProvider(LocalSpokenNow provides playing) {
                Column {
                    EpisodeListRow(Samples.episodeRow())
                    EpisodeListRow(
                        Samples.episodeRow(Samples.episode(id = "ep2", title = "Half way through"), progressSec = 1_260.0, downloadState = SpokenDownloadState.DONE),
                        showCover = true, withShow = true,
                    )
                    EpisodeListRow(Samples.episodeRow(Samples.episode(id = "ep3", title = "Already heard this one"), finished = true), playButton = true)
                    EpisodeListRow(Samples.episodeRow(Samples.episode(id = "ep4", title = "Playing right now")))
                }
            }
        }
    }

    @Test
    fun `podcast and audiobook list rows`() = snap("podcast_book_rows") {
        Column {
            PodcastListRow(Samples.podcast())
            PodcastListRow(Samples.podcast(id = "pod2", title = "Morning Brief", author = "News Desk"), newCount = 3)
            BookListRow(Samples.bookRow())
            BookListRow(Samples.bookRow(Samples.book(id = "bk2", title = "Halfway Home"), progressSec = 12_000.0, downloadState = SpokenDownloadState.DONE))
        }
    }

    @Test
    fun `the spoken page header with cover, title and lines`() = snap("spoken_header") {
        SpokenHeader(coverUrl = null, title = "The Long Read", lines = listOf("Weekly Audio", "120 episodes"), onMenu = {})
    }
}
