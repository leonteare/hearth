// The song list on a collection page: search matches, disc headings, and songs the server doesn't have.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.data.SongItem
import im.flume.hearth.core.ui.components.MissingSongRow
import im.flume.hearth.core.ui.components.formatDuration
import im.flume.hearth.core.ui.theme.HearthTheme

/**
 * The songs, one of three ways: just the matches while searching ([filtered] holds their indexes),
 * the plain list, or [tracks] with songs the server doesn't have dimmed among the rest in album order.
 * [songRow] draws an owned song given its index in [songs].
 */
internal fun LazyListScope.collectionSongs(
    songs: List<SongEntity>,
    keys: List<String>,
    tracks: List<SongItem>?,
    filtered: List<Int>?,
    query: String,
    showTrackNumbers: Boolean,
    songRow: @Composable (Int, SongEntity) -> Unit,
) {
    if (filtered != null) {
        // Searching: just the matching songs (tapping one still plays the whole list from it).
        if (filtered.isEmpty()) item(key = "no-match") {
            Text("No songs match \"$query\"", color = HearthTheme.colors.textSecondary, modifier = Modifier.padding(HearthTheme.spacing.gutter))
        }
        items(filtered, key = { keys[it] }) { index -> Column(Modifier.animateItem()) { songRow(index, songs[index]) } }
    } else if (tracks == null) {
        val multiDisc = showTrackNumbers && songs.any { it.disc != songs[0].disc }
        itemsIndexed(songs, key = { i, _ -> keys[i] }) { index, song ->
            Column(Modifier.animateItem()) {
                if (multiDisc && (index == 0 || songs[index - 1].disc != song.disc)) DiscHeader(song.disc, index == 0)
                songRow(index, song)
            }
        }
    } else {
        // Songs the server doesn't have sit among the ones it does, dimmed, in album order.
        fun disc(item: SongItem) = when (item) {
            is SongItem.Owned -> item.song.disc
            is SongItem.Missing -> item.track.volumeNumber ?: 1
        }
        val multiDisc = showTrackNumbers && tracks.any { disc(it) != disc(tracks[0]) }
        val indexById = songs.withIndex().associate { it.value.id to it.index }
        itemsIndexed(tracks, key = { _, item -> item.key }) { index, item ->
            Column(Modifier.animateItem()) {
                if (multiDisc && (index == 0 || disc(tracks[index - 1]) != disc(item))) DiscHeader(disc(item), index == 0)
                when (item) {
                    is SongItem.Owned -> indexById[item.song.id]?.let { songRow(it, item.song) }
                    is SongItem.Missing -> MissingSongRow(
                        item.track,
                        leading = if (showTrackNumbers) item.track.trackNumber?.toString() ?: "–" else null,
                        showCover = !showTrackNumbers,
                        subtitle = listOfNotNull(
                            item.track.artist.ifBlank { null },
                            item.track.durationSec?.takeIf { it > 0 }?.let { formatDuration(it.toLong()) },
                        ).joinToString(" · "),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscHeader(disc: Int, first: Boolean) {
    Text(
        "Disc $disc",
        style = MaterialTheme.typography.titleMedium,
        color = HearthTheme.colors.textSecondary,
        modifier = Modifier.padding(start = HearthTheme.spacing.gutter, top = if (first) HearthTheme.spacing.xs else HearthTheme.spacing.l, bottom = HearthTheme.spacing.xs),
    )
}
