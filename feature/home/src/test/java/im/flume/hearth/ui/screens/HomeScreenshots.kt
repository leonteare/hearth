package im.flume.hearth.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.MaterialTheme
import im.flume.hearth.testing.ScreenshotTest
import im.flume.hearth.ui.theme.AudiobooksColor
import im.flume.hearth.ui.theme.LikedColor
import im.flume.hearth.ui.theme.PodcastsColor
import org.junit.Test

/** Home's pieces. Moves to :feature:home with HomeScreen. */
class HomeScreenshots : ScreenshotTest() {
    @Test
    fun `the quick tiles at the top of Home`() = snap("quick_tiles") {
        // The same four tiles HomeScreen builds.
        QuickGrid(
            listOf(
                QuickTile("Shuffle my library", Icons.Default.Shuffle, MaterialTheme.colorScheme.primary) {},
                QuickTile("Liked Songs", Icons.Default.Favorite, LikedColor) {},
                QuickTile("Podcasts", Icons.Default.Podcasts, PodcastsColor) {},
                QuickTile("Audiobooks", Icons.AutoMirrored.Filled.MenuBook, AudiobooksColor) {},
            )
        )
    }
}
