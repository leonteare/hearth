package im.flume.hearth.core.ui.components

import im.flume.hearth.core.testing.ScreenshotTest
import im.flume.hearth.core.ui.theme.HearthTheme
import org.junit.Test

class SheetsScreenshots : ScreenshotTest() {
    @Test
    fun `an action sheet with a cover header and menu items`() = snapScreen("action_sheet") {
        ActionSheet(open = true, onDismiss = {}, title = "Northern Lights", subtitle = "The Quiet Hours", fallback = "Long Winter") {
            MenuItem("Like", HearthTheme.icons.like) {}
            MenuItem("Play next", HearthTheme.icons.queue) {}
            MenuItem("Add to playlist", HearthTheme.icons.addToPlaylist) {}
            MenuItem("Download", HearthTheme.icons.downloadFilled, checked = true) {}
            MenuItem("Remove from this playlist", HearthTheme.icons.delete, tint = HearthTheme.colors.destructive, tintText = true) {}
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
