package im.flume.hearth.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.TextSecondary

/** Pick one of your own playlists, or create a new one, to add [songIds] to. */
@Composable
fun PlaylistPickerDialog(
    songIds: List<String>,
    onDismiss: () -> Unit,
    onAdd: (playlistId: String, name: String) -> Unit,
    onCreate: (name: String) -> Unit,
) {
    val c = LocalContext.current.container
    val me = c.session.credentials.collectAsStateWithLifecycle().value?.username
    val all by remember { c.db.library().playlists() }.collectAsStateWithLifecycle(emptyList())
    val mine = all.filter { it.owner == null || it.owner.equals(me, ignoreCase = true) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    if (creating) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Playlist name") })
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) { Text("Create", color = Accent) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (songIds.size == 1) "Add to playlist" else "Add ${songIds.size} songs to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Add, null, tint = Accent)
                        Spacer(Modifier.width(12.dp))
                        Text("New playlist", color = Accent)
                    }
                }
                items(mine, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onAdd(p.id, p.name) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverArt(p.coverArt, 40.dp, requestSize = 120)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${p.songCount}", color = TextSecondary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
