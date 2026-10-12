package im.flume.hearth.ui.screens

// What the spoken-word player shows about the episode or book playing: title, show / author, download and finished state.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.playback.PlayerUiState
import im.flume.hearth.core.playback.SpokenNowPlaying
import im.flume.hearth.core.data.spoken.SpokenDownloadState

/** Title and show / author of what's playing, from the catalogue (falls back to the player's own). */
internal data class SpokenInfo(val title: String, val subtitle: String, val isBook: Boolean, val downloadState: SpokenDownloadState?, val finished: Boolean)

@Composable
internal fun rememberSpokenInfo(state: PlayerUiState, spoken: SpokenNowPlaying): SpokenInfo {
    val c = LocalContext.current.container
    val meta = state.current?.mediaMetadata
    val fallbackTitle = (if (spoken.isBook) meta?.albumTitle else meta?.title)?.toString().orEmpty()
    val fallbackSubtitle = meta?.artist?.toString().orEmpty()
    val episodeId = spoken.episodeId
    return if (episodeId != null) {
        val ep by remember(episodeId) { c.spoken.episode(episodeId) }.collectAsStateWithLifecycle(null)
        SpokenInfo(ep?.episode?.title ?: fallbackTitle, ep?.podcastTitle ?: fallbackSubtitle, false, ep?.downloadState, ep?.finished == true)
    } else {
        val book by remember(spoken.libraryItemId) { c.spoken.book(spoken.libraryItemId) }.collectAsStateWithLifecycle(null)
        SpokenInfo(book?.book?.title ?: fallbackTitle, book?.book?.author ?: fallbackSubtitle, true, book?.downloadState, book?.finished == true)
    }
}
