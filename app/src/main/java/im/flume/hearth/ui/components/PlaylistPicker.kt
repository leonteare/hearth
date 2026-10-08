package im.flume.hearth.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.data.PlaylistRules

/** Asks for a name for a new playlist, with the keyboard already up. */
@Composable
fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (name: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Playlist name") },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick one of your own (or shared-with-you, can add) playlists, or create a new one, to add [songIds] to. */
@Composable
fun PlaylistPickerDialog(
    songIds: List<String>,
    onDismiss: () -> Unit,
    onAdd: (playlistId: String, name: String) -> Unit,
    onCreate: (name: String) -> Unit,
) {
    val c = LocalContext.current.container
    val all by remember { c.library.playlistItems }.collectAsStateWithLifecycle(emptyList())
    // Yours, plus shared ones you can add to; view-only and others' playlists are left out.
    val mine = remember(all) { PlaylistRules.editable(all).map { it.playlist } }
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        NewPlaylistDialog(onDismiss = onDismiss, onCreate = onCreate)
        return
    }

    ActionSheet(
        open = true,
        onDismiss = onDismiss,
        title = if (songIds.size == 1) "Add to playlist" else "Add ${plural(songIds.size, "song")} to playlist",
        showCover = false,
    ) {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item { MenuItem("New playlist", Icons.Default.Add, tint = MaterialTheme.colorScheme.primary, tintText = true) { creating = true } }
            items(mine, key = { it.id }) { p ->
                MediaRow(p.name, plural(p.songCount, "song"), onClick = { onAdd(p.id, p.name) }, coverArt = p.coverArt)
            }
        }
    }
}
