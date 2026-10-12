// The bar that replaces a collection page's top bar while songs are selected, and what its buttons do.
package im.flume.hearth.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.theme.HearthTheme

/**
 * The selection bar for [songs], where [selection] holds the row keys ([keys]) of the picked songs.
 * Each action applies to the picked songs and then ends selecting.
 */
@Composable
internal fun SongSelectionBar(
    songs: List<SongEntity>,
    keys: List<String>,
    selection: Set<String>,
    onSelectionChange: (Set<String>?) -> Unit,
) {
    val actions = LocalActions.current
    val picked = songs.filterIndexed { i, _ -> keys.getOrNull(i) in selection }
    SelectionBar(
        count = picked.size,
        onClose = { onSelectionChange(null) },
        onPlayNext = { actions.playNext(picked.map { it.id }); onSelectionChange(null) },
        onQueue = { actions.addToQueue(picked.map { it.id }); onSelectionChange(null) },
        onPlaylist = { actions.addToPlaylist(picked.map { it.id }); onSelectionChange(null) },
        onDownload = { actions.download(picked); onSelectionChange(null) },
        onSelectAll = { onSelectionChange(keys.toSet()) },
    )
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
    onPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onSelectAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(HearthTheme.colors.surfaceHigh).statusBarsPadding().padding(end = HearthTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(HearthTheme.icons.close, "Stop selecting") }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onSelectAll) { Icon(HearthTheme.icons.selectAll, "Select all") }
        IconButton(onClick = onPlayNext) { Icon(HearthTheme.icons.playNext, "Play next") }
        IconButton(onClick = onQueue) { Icon(HearthTheme.icons.queue, "Add to queue") }
        IconButton(onClick = onPlaylist) { Icon(HearthTheme.icons.addToPlaylist, "Add to playlist") }
        IconButton(onClick = onDownload) { Icon(HearthTheme.icons.download, "Download") }
    }
}
