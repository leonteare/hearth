// The ⋯ menu for an episode.
package im.flume.hearth.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import im.flume.hearth.container
import im.flume.hearth.spoken.EpisodeRow
import im.flume.hearth.spoken.SpokenDownloadState
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.theme.HearthTheme
import kotlinx.coroutines.launch

/** ⋯ for an episode: play, download / remove, mark played / unplayed, go to the show. */
@Composable
fun EpisodeMenu(row: EpisodeRow, open: Boolean, onDismiss: () -> Unit, showGoToShow: Boolean) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val e = row.episode
    ActionSheet(open, onDismiss, title = e.title, subtitle = row.podcastTitle, fallback = row.podcastTitle, coverUrl = c.spokenCover(e.libraryItemId, 150)) {
        if (e.ino != null) {
            MenuItem(if (row.started) "Resume" else "Play", HearthTheme.icons.play) {
                onDismiss(); playEpisode(c, actions, e.libraryItemId, e.id, row.downloadState == SpokenDownloadState.DONE)
            }
            when (row.downloadState) {
                SpokenDownloadState.DONE -> MenuItem("Remove download", HearthTheme.icons.downloaded) {
                    onDismiss(); removeEpisodeDownload(c, actions, e.libraryItemId, e.id)
                }
                SpokenDownloadState.QUEUED, SpokenDownloadState.DOWNLOADING -> MenuItem("Cancel download", HearthTheme.icons.close) {
                    onDismiss(); c.spoken.downloads.remove(e.libraryItemId, e.id)
                }
                SpokenDownloadState.FAILED -> MenuItem("Try again", HearthTheme.icons.refresh) {
                    onDismiss(); c.spoken.downloads.retryFailed()
                }
                else -> MenuItem("Download", HearthTheme.icons.download) {
                    onDismiss(); c.spoken.downloads.downloadEpisode(e.libraryItemId, e.id); actions.showMessage(downloadingMessage(c))
                }
            }
        }
        if (row.finished) {
            MenuItem("Mark as unplayed", HearthTheme.icons.markUnplayed) {
                onDismiss(); c.appScope.launch { c.spoken.markFinished(e.libraryItemId, e.id, false) }
            }
        } else {
            MenuItem("Mark as played", HearthTheme.icons.markPlayed) {
                onDismiss()
                c.appScope.launch { c.spoken.markFinished(e.libraryItemId, e.id, true) }
                actions.showUndo("Marked as played") { c.spoken.markFinished(e.libraryItemId, e.id, false) }
            }
        }
        if (showGoToShow) {
            MenuItem("Go to show", HearthTheme.icons.podcast) { onDismiss(); actions.open(podcastRoute(e.libraryItemId)) }
        }
    }
}
