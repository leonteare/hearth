// Small pieces shared by many screens: artist and album rows, the sort menu, and window-size helpers.
package im.flume.hearth.core.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.data.AlbumEntity
import im.flume.hearth.core.data.ArtistEntity
import im.flume.hearth.core.ui.components.ChoiceSheet
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.MediaRow
import im.flume.hearth.core.ui.components.plural
import im.flume.hearth.core.ui.theme.HearthTheme

@Composable
fun ArtistRow(a: ArtistEntity) {
    val actions = LocalActions.current
    MediaRow(a.name, "Artist • ${plural(a.albumCount, "album")}", onClick = { actions.openArtist(a.id) }, coverArt = a.coverArt, round = true)
}

@Composable
fun AlbumListRow(a: AlbumEntity) {
    val actions = LocalActions.current
    MediaRow(
        a.name,
        listOfNotNull("Album", a.artist, a.year?.toString()).joinToString(" • "),
        onClick = { actions.openAlbum(a.id) },
        coverArt = a.coverArt,
    )
}

@Composable
fun SortMenu(options: List<String>, sort: Int, modifier: Modifier = Modifier, onSort: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Shortened with "…" rather than squeezing whatever sits beside it when space is tight.
    TextButton(onClick = { open = true }, modifier = modifier) {
        Icon(HearthTheme.icons.sort, null, tint = HearthTheme.colors.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(HearthTheme.spacing.xs))
        Text(
            options.getOrElse(sort) { options[0] },
            color = HearthTheme.colors.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    ChoiceSheet(open, "Sort by", options.mapIndexed { i, label -> i to label }, sort, onDismiss = { open = false }, onPick = onSort)
}

/** Columns for tile grids (genres, Home's quick tiles): 2 on phones, 3-4 on wide windows. */
@Composable
fun gridColumns(): Int {
    val width = LocalConfiguration.current.screenWidthDp
    return when {
        width > 840 -> 4
        width > 600 -> 3
        else -> 2
    }
}

/** True when the window is wider than tall (landscape), so tall headers go side by side. */
@Composable
fun isWideWindow(): Boolean {
    val config = LocalConfiguration.current
    return config.screenWidthDp > config.screenHeightDp
}
