// The bar that replaces a collection page's top bar while songs are selected, and what its buttons do.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.flume.hearth.data.SongEntity
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.theme.SurfaceHigh

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
        Modifier.fillMaxWidth().background(SurfaceHigh).statusBarsPadding().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Stop selecting") }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, "Select all") }
        IconButton(onClick = onPlayNext) { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "Play next") }
        IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") }
        IconButton(onClick = onPlaylist) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist") }
        IconButton(onClick = onDownload) { Icon(Icons.Outlined.ArrowCircleDown, "Download") }
    }
}
