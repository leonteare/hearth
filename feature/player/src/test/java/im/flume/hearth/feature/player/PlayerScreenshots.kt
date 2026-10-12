package im.flume.hearth.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import im.flume.hearth.core.playback.PlaybackService
import im.flume.hearth.core.data.spoken.ChapterEntity
import im.flume.hearth.core.testing.Samples
import im.flume.hearth.core.testing.ScreenshotTest
import im.flume.hearth.core.ui.theme.Dimens
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The mini player, the player controls and the queue. */
class PlayerScreenshots : ScreenshotTest() {
    private val songs = Samples.songs(5)
    private val state = Samples.playerState(songs[1], songs)

    @Test
    fun `the mini player with a song playing`() = snap("mini_player_music") {
        MiniPlayer(state, onOpen = {})
    }

    @Test
    fun `the mini player with a song paused`() = snap("mini_player_paused") {
        MiniPlayer(state.copy(isPlaying = false), onOpen = {})
    }

    @Test
    fun `the mini player with a podcast episode, skip buttons instead of next and previous`() = snap("mini_player_spoken") {
        MiniPlayer(Samples.spokenPlayerState(), onOpen = {})
    }

    @Test
    fun `the music player controls below the cover`() = snap("player_controls") {
        Column(Modifier.padding(horizontal = Dimens.Gutter)) {
            PlayerControls(
                state.copy(shuffle = true), songs[1].copy(starred = true), onClose = {}, showLyrics = false,
                onToggleLyrics = {}, onOpenQueue = {}, onOpenVisualiser = {},
            )
        }
    }

    @Test
    fun `the podcast player controls with speed and chapters`() {
        val spokenState = Samples.spokenPlayerState(speed = 1.25f)
        snap("spoken_controls") {
            Column(Modifier.padding(horizontal = Dimens.Gutter)) {
                SpokenControls(
                    spokenState, spokenState.spoken!!,
                    SpokenInfo("The town that forgot to sleep", "The Long Read", isBook = false, downloadState = null, finished = false),
                    chapters = emptyList(), openPage = {}, onSpeed = {}, onChapters = {}, onOpenQueue = {},
                )
            }
        }
    }

    @Test
    fun `the speed sheet at one and a quarter`() = snapScreen("speed_sheet") {
        SpeedSheet(1.25f, onDismiss = {}, onSpeed = {})
    }

    @Test
    fun `queue rows, the playing song highlighted, then up next and later`() = snap("queue_rows") {
        Column {
            QueueHeader("Now playing")
            QueueRow(state.queue[1], isCurrent = true)
            QueueHeader("Next from: Long Winter", trailing = "3 songs")
            QueueRow(state.queue[2], handle = { Modifier })
            QueueRow(state.queue[3], handle = { Modifier })
            QueueHeader("Later", alpha = 0.6f)
            LaterRow(songs[4], alpha = 0.6f)
        }
    }

    @Test
    fun `the now playing screen`() = snapScreen("now_playing") {
        Box { NowPlayingScreen(state, onClose = {}, onOpenQueue = {}, onOpenVisualiser = {}) }
    }

    @Test
    fun `the chapters sheet with the first chapter playing`() {
        val spokenState = Samples.spokenPlayerState()
        val spoken = spokenState.spoken!!
        val titles = listOf("Opening", "The night shift", "Streetlights", "", "The mayor's plan", "Morning")
        val chapters = titles.mapIndexed { i, t -> ChapterEntity("pod1", "ep1", i, i * 420.0, (i + 1) * 420.0, t) }
        snapScreen("chapters_sheet") { ChaptersSheet(chapters, spoken, spokenState, onDismiss = {}) }
    }

    @Test
    fun `the spoken sleep timer sheet, set to the end of the chapter`() = snapScreen("spoken_sleep_sheet") {
        SpokenSleepSheet(PlaybackService.SLEEP_END_OF_CHAPTER, hasChapters = true, isBook = false, onDismiss = {})
    }

    @Test
    fun `the podcast queue, the episode playing then more from the show`() {
        val spokenState = Samples.spokenPlayerState()
        val episodes = listOf(
            Samples.episode(),
            Samples.episode(id = "ep2", title = "Rivers under the city", publishedAt = Samples.PUBLISHED_MS + 7 * DAY_MS),
            Samples.episode(id = "ep3", title = "The last lamplighter", publishedAt = Samples.PUBLISHED_MS - 7 * DAY_MS),
        )
        runBlocking {
            container.db.spoken().upsertPodcasts(listOf(Samples.podcast()))
            container.db.spoken().upsertEpisodes(episodes)
        }
        snapScreen("spoken_queue") { SpokenQueueScreen(spokenState, spokenState.spoken!!, onBack = {}) }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
