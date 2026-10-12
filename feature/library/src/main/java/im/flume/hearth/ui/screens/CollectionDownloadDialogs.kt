// The two questions a collection page asks before undoing a download: stop it, or remove it from the phone.
package im.flume.hearth.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import im.flume.hearth.download.DownloadRepository
import im.flume.hearth.ui.components.formatBytes
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.theme.HearthTheme

/** Asked when the download toggle is tapped while the collection is still downloading. */
@Composable
internal fun StopDownloadingDialog(title: String, onDismiss: () -> Unit, onStop: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop downloading?") },
        text = { Text("This removes \"$title\" from your downloads, including songs that already finished. Songs your other downloads need are kept.") },
        confirmButton = { TextButton(onClick = onStop) { Text("Stop") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep downloading") } },
    )
}

/** Removing a finished download always asks first, saying how much goes. [plan] is null while it's worked out. */
@Composable
internal fun RemoveDownloadsDialog(plan: DownloadRepository.RemovalPlan?, onDismiss: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove downloads?") },
        text = {
            Text(
                when {
                    plan == null -> "Checking…"
                    plan.songIds.isEmpty() -> "Nothing will be deleted: your other downloads or Liked Songs need all of these songs."
                    else -> "${plural(plan.songIds.size, "song")}, ${formatBytes(plan.bytes)} will be removed from this phone. " +
                        "Songs other downloads or Liked Songs need are kept."
                }
            )
        },
        confirmButton = {
            TextButton(enabled = plan != null, onClick = onRemove) { Text("Remove", color = HearthTheme.colors.destructive) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
