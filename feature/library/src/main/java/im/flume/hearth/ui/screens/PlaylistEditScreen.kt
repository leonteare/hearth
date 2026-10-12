// Reordering a playlist's songs by dragging, then saving the new order to Navidrome.
package im.flume.hearth.ui.screens

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.MediaRow
import im.flume.hearth.core.ui.components.TopBar
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.common.couldNot
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import im.flume.hearth.core.ui.screens.coverArt

/** Drag songs into a new order, then save it to Navidrome. */
@Composable
fun PlaylistEditScreen(id: String) {
    val c = LocalContext.current.container
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val owner = remember(id) { c.library.playlistItem(id) }.collectAsStateWithLifecycle(null).value?.access?.owner
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    // The server's list when editing began; null until loaded. Save merges against it.
    var original by remember(id) { mutableStateOf<List<String>?>(null) }
    // (stable key, song id, song details or null when this phone doesn't know the song)
    var order by remember(id) { mutableStateOf<List<Triple<Int, String, SongEntity?>>>(emptyList()) }
    LaunchedEffect(id) {
        val ids = c.library.playlistSongIdsForEdit(id)
        val known = c.library.songsByIds(ids.distinct()).associateBy { it.id }
        order = ids.mapIndexed { i, s -> Triple(i, s, known[s]) }
        original = ids
    }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Edit order") {
            // Stay on the screen until Navidrome has the new order, so a failed save doesn't lose the edit.
            TextButton(enabled = !saving && original != null, onClick = {
                val start = original ?: return@TextButton
                saving = true
                val ids = order.map { it.second }
                scope.launch {
                    val error = c.appScope.async { runCatching { c.library.savePlaylistEdit(id, start, ids) }.exceptionOrNull() }.await()
                    saving = false
                    if (error == null) back?.onBackPressed()
                    else actions.showMessage(c.library.editError(error, owner, couldNot("Couldn't save the new order", error, !c.network.isOnline.value)))
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(order, key = { it.first }) { (key, _, song) ->
                ReorderableItem(reorder, key = key) { dragging ->
                    MediaRow(
                        song?.title ?: "Song not synced yet", song?.artist.orEmpty(), onClick = null,
                        modifier = Modifier.background(if (dragging) HearthTheme.colors.surfaceHigh else HearthTheme.colors.background),
                        coverArt = song?.coverArt, fallback = song?.album.orEmpty(),
                    ) {
                        Box(Modifier.draggableHandle().size(48.dp), contentAlignment = Alignment.Center) {
                            Icon(HearthTheme.icons.dragHandle, "Reorder", tint = HearthTheme.colors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}
