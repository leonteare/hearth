// The ⋯ menu for an episode.
package im.flume.hearth.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import im.flume.hearth.container
import im.flume.hearth.spoken.EpisodeRow
import im.flume.hearth.spoken.SpokenDownloadState
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MenuItem
import kotlinx.coroutines.launch

/** ⋯ for an episode: play, download / remove, mark played / unplayed, go to the show. */
@Composable
fun EpisodeMenu(row: EpisodeRow, open: Boolean, onDismiss: () -> Unit, showGoToShow: Boolean) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val e = row.episode
    ActionSheet(open, onDismiss, title = e.title, subtitle = row.podcastTitle, fallback = row.podcastTitle, coverUrl = c.spokenCover(e.libraryItemId, 150)) {
        if (e.ino != null) {
            MenuItem(if (row.started) "Resume" else "Play", Icons.Default.PlayArrow) {
                onDismiss(); playEpisode(c, actions, e.libraryItemId, e.id, row.downloadState == SpokenDownloadState.DONE)
            }
            when (row.downloadState) {
                SpokenDownloadState.DONE -> MenuItem("Remove download", Icons.Default.CheckCircle) {
                    onDismiss(); removeEpisodeDownload(c, actions, e.libraryItemId, e.id)
                }
                SpokenDownloadState.QUEUED, SpokenDownloadState.DOWNLOADING -> MenuItem("Cancel download", Icons.Default.Close) {
                    onDismiss(); c.spoken.downloads.remove(e.libraryItemId, e.id)
                }
                SpokenDownloadState.FAILED -> MenuItem("Try again", Icons.Default.Refresh) {
                    onDismiss(); c.spoken.downloads.retryFailed()
                }
                else -> MenuItem("Download", Icons.Outlined.ArrowCircleDown) {
                    onDismiss(); c.spoken.downloads.downloadEpisode(e.libraryItemId, e.id); actions.showMessage(downloadingMessage(c))
                }
            }
        }
        if (row.finished) {
            MenuItem("Mark as unplayed", Icons.Outlined.RadioButtonUnchecked) {
                onDismiss(); c.appScope.launch { c.spoken.markFinished(e.libraryItemId, e.id, false) }
            }
        } else {
            MenuItem("Mark as played", Icons.Outlined.CheckCircle) {
                onDismiss()
                c.appScope.launch { c.spoken.markFinished(e.libraryItemId, e.id, true) }
                actions.showUndo("Marked as played") { c.spoken.markFinished(e.libraryItemId, e.id, false) }
            }
        }
        if (showGoToShow) {
            MenuItem("Go to show", Icons.Default.Podcasts) { onDismiss(); actions.open(podcastRoute(e.libraryItemId)) }
        }
    }
}
