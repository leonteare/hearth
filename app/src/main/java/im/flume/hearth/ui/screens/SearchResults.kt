// The list under the search box: recent searches and genres before you type, then the ranked results.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.flume.hearth.data.AlbumItem
import im.flume.hearth.data.ArtistItem
import im.flume.hearth.data.GenreCount
import im.flume.hearth.data.SearchFilter
import im.flume.hearth.data.SearchHit
import im.flume.hearth.data.SearchIndex
import im.flume.hearth.data.SongItem
import im.flume.hearth.spoken.PodcastEntity
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MissingAlbumRow
import im.flume.hearth.ui.components.MissingArtistRow
import im.flume.hearth.ui.components.MissingSongRow
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.SongRow
import im.flume.hearth.ui.components.remoteAlbumId
import im.flume.hearth.ui.components.remoteArtistId
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.TextSecondary

/** Before anything is typed: recent searches (with Clear), then a grid of genres to browse. */
internal fun LazyListScope.searchLanding(
    recent: List<String>,
    onClearRecent: () -> Unit,
    onPickRecent: (String) -> Unit,
    genres: List<GenreCount>,
    gridColumns: Int,
    onOpenGenre: (String) -> Unit,
) {
    if (recent.isNotEmpty()) {
        item {
            SectionHeader("Recent searches") {
                TextButton(onClick = onClearRecent) { Text("Clear", color = TextSecondary) }
            }
        }
        items(recent.take(RECENT_SEARCHES), key = { "r:$it" }) { r ->
            Row(
                Modifier.fillMaxWidth().clickable { onPickRecent(r) }.padding(horizontal = Dimens.Gutter, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.History, null, tint = TextSecondary)
                Spacer(Modifier.width(16.dp))
                Text(r, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (genres.isNotEmpty()) item { SectionHeader("Browse genres") }
    items(genres.chunked(gridColumns)) { pair ->
        Row(Modifier.padding(horizontal = Dimens.Gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { g ->
                Box(
                    Modifier.weight(1f).heightIn(min = 72.dp).clip(HearthShapes.Card)
                        .background(genreColor(g.genre)).clickable { onOpenGenre(g.genre) }.padding(12.dp)
                ) {
                    Text(g.genre, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat(gridColumns - pair.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** With something typed: progress notes, every hit best first, and a line when nothing matched. */
internal fun LazyListScope.searchResults(
    query: String,
    filter: SearchFilter,
    indexing: Boolean,
    library: SearchIndex.Results,
    hits: List<SearchHit>,
    shown: List<SearchHit>,
    directoryLoading: Boolean,
    podcasts: List<PodcastEntity>,
    username: String?,
    playSong: (String) -> Unit,
) {
    if (indexing) {
        item(key = "indexing") { SearchProgressNote("Getting your library ready to search…") }
    }
    if (library.fuzzy && !library.isEmpty) {
        item(key = "close") {
            Text(
                "No exact matches. Showing close matches.",
                color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    items(shown, key = { it.key }) { hit ->
        Column(Modifier.animateItem()) {
            SearchHitRow(hit, podcasts, username, playSong)
        }
    }
    val lookingForPodcasts = directoryLoading && (filter == SearchFilter.ALL || filter == SearchFilter.PODCASTS)
    if (lookingForPodcasts) {
        item(key = "directory-loading") { SearchProgressNote("Looking for more podcasts…") }
    }
    if (!indexing && !directoryLoading && shown.isEmpty()) {
        item(key = "none") {
            Text(
                if (hits.isEmpty()) "No results for \"$query\"" else "No ${filter.label.lowercase()} for \"$query\"",
                color = TextSecondary, modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** A small spinner with a line saying what's still being looked up. */
@Composable
private fun SearchProgressNote(text: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One row of Search's list, in the same row the rest of the app uses for that kind of thing. */
@Composable
private fun SearchHitRow(
    hit: SearchHit,
    podcasts: List<PodcastEntity>,
    username: String?,
    playSong: (String) -> Unit,
) {
    val actions = LocalActions.current
    when (hit) {
        is SearchHit.Artist -> when (val a = hit.item) {
            is ArtistItem.Owned -> ArtistRow(a.artist)
            is ArtistItem.Missing -> MissingArtistRow(a.artist) { actions.openArtist(remoteArtistId(a.artist.id)) }
        }
        is SearchHit.Album -> when (val a = hit.item) {
            is AlbumItem.Owned -> AlbumListRow(a.album)
            is AlbumItem.Missing -> MissingAlbumRow(a.album) { actions.openAlbum(remoteAlbumId(a.album.id)) }
        }
        is SearchHit.Song -> when (val s = hit.item) {
            is SongItem.Owned -> SongRow(s.song, onClick = { playSong(s.song.id) }, subtitle = "Song • ${s.song.artist}")
            is SongItem.Missing -> MissingSongRow(s.track, subtitle = "Song • ${s.track.artist}")
        }
        is SearchHit.Podcast -> PodcastListRow(hit.podcast)
        is SearchHit.DirectoryPodcast -> DirectoryRow(hit.result, podcasts, username)
        is SearchHit.Episode -> EpisodeListRow(hit.row, showCover = true, withShow = true, label = "Episode")
        is SearchHit.Book -> BookListRow(hit.row)
    }
}

private fun genreColor(name: String): Color {
    val palette = listOf(0xFF8C1932, 0xFF1E3264, 0xFF477D95, 0xFF8D67AB, 0xFFE8115B, 0xFF148A08, 0xFFBA5D07, 0xFF503750, 0xFF0D73EC, 0xFF777777)
    return Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
}
