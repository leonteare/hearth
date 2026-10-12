package im.flume.hearth.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import im.flume.hearth.testing.ScreenshotTest
import im.flume.hearth.ui.theme.Destructive
import org.junit.Test

class SheetsScreenshots : ScreenshotTest() {
    @Test
    fun `an action sheet with a cover header and menu items`() = snapScreen("action_sheet") {
        ActionSheet(open = true, onDismiss = {}, title = "Northern Lights", subtitle = "The Quiet Hours", fallback = "Long Winter") {
            MenuItem("Like", Icons.Default.FavoriteBorder) {}
            MenuItem("Play next", Icons.AutoMirrored.Filled.QueueMusic) {}
            MenuItem("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd) {}
            MenuItem("Download", Icons.Default.Download, checked = true) {}
            MenuItem("Remove from this playlist", Icons.Default.Delete, tint = Destructive, tintText = true) {}
        }
    }

    @Test
    fun `a choice sheet with the current choice ticked`() = snapScreen("choice_sheet") {
        ChoiceSheet(
            open = true, title = "Sort by", options = listOf(0 to "Recently added", 1 to "Alphabetical", 2 to "Artist"),
            selected = 1, onDismiss = {}, onPick = {},
        )
    }
}
