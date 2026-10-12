// Settings → Podcasts & audiobooks: picking, removing and rescanning folders of audiobooks on this phone.
package im.flume.hearth.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import im.flume.hearth.container
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.theme.Destructive
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** Settings → Podcasts & audiobooks: folders on the phone that hold audiobooks. */
@Composable
fun PhoneAudiobookFolders() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val local = c.spoken.local
    val folders by local.folders.collectAsStateWithLifecycle()
    val scanning by local.scanning.collectAsStateWithLifecycle()
    val problem by local.scanProblem.collectAsStateWithLifecycle()
    val books by remember { local.books() }.collectAsStateWithLifecycle(emptyList())
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            actions.showMessage(local.addFolder(uri))
        }
    }

    Text(
        "Audiobooks on this phone",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = Dimens.Gutter, end = Dimens.Gutter, top = Dimens.Gutter),
    )
    Text(
        if (folders.isEmpty()) "Pick a folder of audiobooks on this phone to listen to them here. Each sub-folder is one book; a single file (like an .m4b) is a book on its own."
        else "${plural(books.size, "book")} found. Each sub-folder is one book. You can add any of them to the server from its page.",
        color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 4.dp),
    )
    folders.forEach { f ->
        MediaRow(
            f.name, null, onClick = null,
            cover = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Folder, null, tint = TextSecondary) } },
            trailing = { TextButton(onClick = { confirmRemove = f.uri }) { Text("Remove", color = Destructive) } },
        )
    }
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { runCatching { picker.launch(null) }.onFailure { actions.showMessage("This phone has no folder picker") } }) {
            Text(if (folders.isEmpty()) "Add a folder on this phone" else "Add another folder")
        }
        if (folders.isNotEmpty()) {
            TextButton(enabled = !scanning, onClick = {
                scope.launch {
                    val n = local.rescan()
                    actions.showMessage("${plural(n, "audiobook")} on this phone")
                }
            }) { Text(if (scanning) "Scanning…" else "Rescan") }
        }
    }
    problem?.let {
        Text(it, color = Destructive, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Dimens.Gutter))
    }

    confirmRemove?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove this folder?") },
            text = { Text("Its books leave your library. The files stay on your phone, and anything already added to the server stays there.") },
            confirmButton = { TextButton(onClick = { confirmRemove = null; local.removeFolder(uri) }) { Text("Remove", color = Destructive) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}
