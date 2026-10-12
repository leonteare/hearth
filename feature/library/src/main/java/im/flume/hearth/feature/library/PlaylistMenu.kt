// A playlist's options menu: queueing, rename, reorder, photo, share, delete and leave.
package im.flume.hearth.feature.library

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import im.flume.hearth.core.common.SubsonicException
import im.flume.hearth.core.playback.container
import im.flume.hearth.domain.music.PhotoPrep
import im.flume.hearth.domain.music.PlaylistItem
import im.flume.hearth.domain.music.PlaylistRules
import im.flume.hearth.domain.music.download.DownloadRepository
import im.flume.hearth.core.ui.components.ActionSheet
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.MenuItem
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.common.couldNot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import im.flume.hearth.core.ui.screens.coverArt

/** The playlist's ⋯ menu. What's in it depends on whether you own it, can add to it, or only listen. */
@Composable
internal fun PlaylistMenu(item: PlaylistItem, screenScope: CoroutineScope, songIds: List<String>) {
    val c = LocalContext.current.container
    val context = LocalContext.current
    val actions = LocalActions.current
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val id = item.playlist.id
    val name = item.playlist.name
    val access = item.access
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // A photo action waiting for the password dialog to finish.
    var afterPassword by remember { mutableStateOf<(() -> Unit)?>(null) }
    var newName by remember(name) { mutableStateOf(name) }

    fun withPassword(action: () -> Unit) {
        if (c.session.vault.hasPassword) action() else afterPassword = action
    }
    fun photoChange(progress: String, done: String, change: suspend () -> Unit) {
        c.appScope.launch {
            actions.showMessage(progress)
            runCatching { change() }
                .onSuccess { actions.showMessage(done) }
                .onFailure { e ->
                    actions.showMessage(
                        if (e is SubsonicException) c.library.editError(e, access.owner, couldNot("Couldn't change the photo", e, !c.network.isOnline.value))
                        else photoError(e) { c.session.vault.clear(); c.nativeApi.forgetSession() }
                    )
                }
        }
    }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) withPassword {
            photoChange("Uploading photo…", "Photo updated") {
                c.library.setPlaylistPhoto(id, PhotoPrep.jpeg(context, uri))
            }
        }
    }

    IconButton(onClick = { open = true }) { Icon(HearthTheme.icons.more, "Playlist options") }
    ActionSheet(open, { open = false }, title = name, subtitle = "Playlist", coverArt = item.playlist.coverArt, fallback = name) {
        if (songIds.isNotEmpty()) {
            MenuItem("Play next", HearthTheme.icons.playNext) { open = false; actions.playNext(songIds) }
            MenuItem("Add to queue", HearthTheme.icons.queue) { open = false; actions.addToQueue(songIds) }
            MenuItem("Add to playlist", HearthTheme.icons.addToPlaylist) { open = false; actions.addToPlaylist(songIds) }
        }
        if (access.canEdit) {

            MenuItem("Rename", HearthTheme.icons.edit) { open = false; renaming = true }
            MenuItem("Edit order", HearthTheme.icons.reorder) { open = false; actions.open("playlist-edit/$id") }
            MenuItem("Change photo", HearthTheme.icons.changePhoto) {
                open = false
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            // Navidrome doesn't say whether the photo is custom; removing an automatic one is harmless.
            MenuItem("Remove photo", HearthTheme.icons.removePhoto) {
                open = false
                withPassword { photoChange("Removing photo…", "Photo removed") { c.library.removePlaylistPhoto(id) } }
            }
        }
        if (access.isOwner) {
            MenuItem("Share", HearthTheme.icons.share) { open = false; sharing = true }
            MenuItem("Delete playlist", HearthTheme.icons.delete, tint = HearthTheme.colors.destructive, tintText = true) { open = false; deleting = true }
        }
        if (access.isMember) {
            MenuItem("Leave playlist", HearthTheme.icons.leave, tint = HearthTheme.colors.destructive, tintText = true) { open = false; leaving = true }
        }
    }
    if (sharing) ShareSheet(item) { sharing = false }
    afterPassword?.let { action ->
        PasswordDialog(onDismiss = { afterPassword = null }, onSaved = { afterPassword = null; action() })
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    renaming = false
                    c.appScope.launch {
                        runCatching { c.library.renamePlaylist(id, newName.trim()) }
                            .onFailure { actions.showMessage(c.library.editError(it, access.owner, couldNot("Couldn't rename the playlist", it, !c.network.isOnline.value))) }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave \"$name\"?") },
            text = { Text("It disappears from your library. ${PlaylistRules.displayName(access.owner)} can invite you again.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    screenScope.launch {
                        c.appScope.async { c.library.answerInvite(id, accept = false) }.await()
                        back?.onBackPressed()
                    }
                }) { Text("Leave", color = HearthTheme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"$name\"?") },
            text = { Text("The playlist is removed from Navidrome for good. The songs stay in your library.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    // The request runs in the app scope so leaving the screen can't cut it short; going
                    // back is tied to the screen (this menu vanishes once the playlist row is deleted).
                    screenScope.launch {
                        val ok = c.appScope.async {
                            // Read the songs first: deleting the playlist drops them, and its downloads would stay forever.
                            val songIds = c.db.library().playlistSongsOnce(id).map { it.id }
                            runCatching { c.library.deletePlaylist(id) }
                                .onSuccess { c.downloads.unpinAndRemove(DownloadRepository.KIND_PLAYLIST, id, songIds) }
                                .isSuccess
                        }.await()
                        if (ok) back?.onBackPressed() else actions.showMessage(couldNot("Couldn't delete the playlist", null, !c.network.isOnline.value))
                    }
                }) { Text("Delete", color = HearthTheme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}
