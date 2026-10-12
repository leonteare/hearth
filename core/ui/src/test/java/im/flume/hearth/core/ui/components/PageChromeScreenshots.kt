package im.flume.hearth.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.testing.ScreenshotTest
import org.junit.Test

class PageChromeScreenshots : ScreenshotTest() {
    @Test
    fun `a tab page header with its title and buttons`() = snap("page_header") {
        PageHeader("Your Library") {
            IconButton(onClick = {}) { Icon(Icons.Default.Search, "Search") }
            IconButton(onClick = {}) { Icon(Icons.Default.Settings, "Settings") }
        }
    }

    @Test
    fun `a page top bar with back, title and a button`() = snap("top_bar") {
        TopBar("Downloads", onBack = {}) { IconButton(onClick = {}) { Icon(Icons.Default.Settings, "Settings") } }
    }

    @Test
    fun `section headers, plain and with a trailing button`() = snap("section_headers") {
        Column {
            SectionHeader("Recently played")
            SectionHeader("Recent searches") { TextButton(onClick = {}) { Text("Clear") } }
        }
    }

    @Test
    fun `a banner card, plain and tinted with actions`() = snap("banner_cards") {
        Column {
            BannerCard("Couldn't reach your server", "Showing what's on this phone. We'll keep trying.", icon = Icons.Default.Info)
            BannerCard(
                "Update ready", "Version 3.0 is ready to install.", tinted = true,
                actions = { TextButton(onClick = {}) { Text("Later") }; TextButton(onClick = {}) { Text("Install") } },
            )
        }
    }

    @Test
    fun `an empty state with a message and an action`() = snap("empty_state") {
        Column(Modifier.height(320.dp)) {
            EmptyState(Icons.Default.LibraryMusic, "Nothing saved yet. Albums you save show up here.", action = "Browse music", onAction = {})
        }
    }

    @Test
    fun `the downloads icon while downloading, waiting, and with a failure`() = snap("downloads_indicator") {
        Row {
            CompositionLocalProvider(LocalDownloadsBadge provides DownloadsBadge(downloading = 2, waiting = 3, failed = 0)) { DownloadsIndicator() }
            CompositionLocalProvider(LocalDownloadsBadge provides DownloadsBadge(downloading = 0, waiting = 3, failed = 0)) { DownloadsIndicator() }
            CompositionLocalProvider(LocalDownloadsBadge provides DownloadsBadge(downloading = 0, waiting = 0, failed = 1)) { DownloadsIndicator() }
            CompositionLocalProvider(LocalDownloadsBadge provides DownloadsBadge(downloading = 1, waiting = 0, failed = 2)) { DownloadsIndicator() }
        }
    }
}
