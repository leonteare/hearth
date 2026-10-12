package im.flume.hearth.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.testing.ScreenshotTest
import im.flume.hearth.core.ui.theme.HearthTheme
import org.junit.Test

class MediaItemsScreenshots : ScreenshotTest() {
    @Test
    fun `media rows for an album, a round artist, a liked songs tile and a ranked row`() = snap("media_rows") {
        Column {
            MediaRow("Long Winter", "Album • The Quiet Hours • 2021", onClick = {})
            MediaRow("The Quiet Hours", "Artist • 3 albums", onClick = {}, round = true)
            MediaRow("Liked Songs", "Playlist • 120 songs", onClick = {}, cover = { IconTile(HearthTheme.icons.likeOn, HearthTheme.colors.liked) })
            MediaRow(
                "Glasshouse", "41 plays", onClick = {},
                leading = { androidx.compose.material3.Text("1", Modifier.padding(end = HearthTheme.spacing.coverGap), color = HearthTheme.colors.textSecondary) },
            )
            MediaRow(
                "Not Here Yet", "Album • Somebody Else", onClick = {}, subtitleColor = HearthTheme.colors.textSecondary.copy(alpha = 0.7f),
                subtitleIcon = { Icon(HearthTheme.icons.offline, null, Modifier.padding(end = 4.dp), tint = HearthTheme.colors.textSecondary) },
            )
        }
    }

    @Test
    fun `media cards in a carousel, square, round and dimmed`() = snap("media_cards") {
        Row(Modifier.padding(HearthTheme.spacing.gutter), horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.carouselSpacing)) {
            MediaCard("Long Winter", "The Quiet Hours", onClick = {})
            MediaCard("The Quiet Hours", "Artist", onClick = {}, round = true)
            MediaCard("Not Here Yet", "Somebody Else", onClick = {}, dimmed = true)
        }
    }

    @Test
    fun `cover placeholders show initials on a colour, or a note with no name`() = snap("cover_placeholders") {
        Row(Modifier.padding(HearthTheme.spacing.gutter), horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.coverGap)) {
            CoverArt(null, 96.dp, fallback = "Long Winter")
            CoverArt(null, 96.dp, fallback = "Sunday Morning")
            CoverArt(null, 96.dp, fallback = "Ödland")
            CoverArt(null, 96.dp, fallback = null)
            CoverArt(null, HearthTheme.spacing.rowCover, fallback = "Paper Boats")
        }
    }
}
