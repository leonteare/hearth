// Importing podcasts from an OPML file exported by another podcast app.
package im.flume.hearth.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import im.flume.hearth.container
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.plural
import im.flume.hearth.util.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks an OPML file (exported from another podcast app) and adds and follows every show in it.
 * Returns the function that opens the file picker.
 */
@Composable
fun rememberOpmlImport(): () -> Unit {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        actions.showMessage("Adding your podcasts…")
        c.appScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text.isNullOrBlank()) { actions.showMessage("Couldn't read that file"); return@launch }
            val r = runCatching { c.spoken.importOpml(text) }.getOrElse { actions.showMessage("Couldn't import: ${userMessage(it)}"); return@launch }
            val total = r.added.size + r.followedExisting.size
            actions.showMessage(
                when {
                    total == 0 && r.failed.isEmpty() -> "No podcasts found in that file"
                    total == 0 -> "Couldn't add any of the ${r.failed.size} podcasts"
                    r.failed.isEmpty() -> "Following ${plural(total, "podcast")}"
                    else -> "Following ${plural(total, "podcast")} · ${r.failed.size} couldn't be added"
                }
            )
        }
    }
    return { launcher.launch(arrayOf("text/xml", "application/xml", "text/x-opml", "application/octet-stream", "text/plain")) }
}
