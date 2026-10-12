package im.flume.hearth.ui.screens

// The spoken-word player's ⋯ menu: back to music, go to the book or show, download, mark finished and sleep timer.

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import im.flume.hearth.container
import im.flume.hearth.playback.PlayerUiState
import im.flume.hearth.playback.SpokenNowPlaying
import im.flume.hearth.spoken.LocalBookIds
import im.flume.hearth.spoken.SpokenDownloadState
import im.flume.hearth.spoken.SpokenKey
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.theme.HearthTheme
import kotlinx.coroutines.launch

@Composable
internal fun SpokenPlayerMenu(state: PlayerUiState, spoken: SpokenNowPlaying, info: SpokenInfo, onClose: () -> Unit, openPage: () -> Unit, onSleep: () -> Unit) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val icons = HearthTheme.icons
    var open by remember { mutableStateOf(false) }
    val key = SpokenKey(spoken.libraryItemId, spoken.episodeId.orEmpty())
    IconButton(onClick = { open = true }) { Icon(icons.moreHorizontal, "More options") }
    ActionSheet(open, { open = false }, title = info.title, subtitle = info.subtitle, coverUrl = c.spokenCover(spoken.libraryItemId, 150), fallback = info.subtitle) {
        if (spoken.canGoBackToMusic) {
            MenuItem("Back to music", icons.library) { open = false; c.player.backToMusic() }
        }
        MenuItem(if (spoken.isBook) "Go to book" else "Go to show", if (spoken.isBook) icons.audiobook else icons.podcast) { open = false; openPage() }
        if (!LocalBookIds.isLocal(spoken.libraryItemId)) when (info.downloadState) {
            SpokenDownloadState.DONE -> MenuItem("Remove download", icons.downloaded) {
                open = false
                if (key.episodeId.isNotEmpty()) removeEpisodeDownload(c, actions, key.libraryItemId, key.episodeId)
                else c.spoken.downloads.remove(key.libraryItemId, key.episodeId)
            }
            SpokenDownloadState.QUEUED, SpokenDownloadState.DOWNLOADING -> MenuItem("Cancel download", icons.close) {
                open = false; c.spoken.downloads.remove(key.libraryItemId, key.episodeId)
            }
            else -> MenuItem("Download", icons.download) {
                open = false
                val episodeId = spoken.episodeId
                if (episodeId != null) c.spoken.downloads.downloadEpisode(spoken.libraryItemId, episodeId) else c.spoken.downloads.downloadBook(spoken.libraryItemId)
                actions.showMessage("Downloading")
            }
        }
        if (!info.finished) {
            MenuItem(if (spoken.isBook) "Mark as finished" else "Mark as played", icons.markPlayed) {
                open = false
                c.appScope.launch { c.spoken.markFinished(spoken.libraryItemId, spoken.episodeId, true) }
                actions.showUndo(if (spoken.isBook) "Marked as finished" else "Marked as played") {
                    c.spoken.markFinished(spoken.libraryItemId, spoken.episodeId, false)
                }
            }
        }
        MenuItem(
            if (state.sleepAt != 0L) "Sleep timer (on)" else "Sleep timer", icons.sleepTimer,
            tint = if (state.sleepAt != 0L) MaterialTheme.colorScheme.primary else HearthTheme.colors.text,
        ) { open = false; onSleep() }
    }
}
