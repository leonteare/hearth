// The artist page's layout (header, Popular, album carousels).
package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import im.flume.hearth.data.AlbumItem
import im.flume.hearth.data.Discography
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.data.SongItem
import im.flume.hearth.core.ui.components.BackButton
import im.flume.hearth.core.ui.components.CoverGradientHeader
import im.flume.hearth.core.ui.components.DownloadsIndicator
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.MediaCard
import im.flume.hearth.core.ui.components.MissingAlbumCard
import im.flume.hearth.core.ui.components.MissingSongRow
import im.flume.hearth.core.ui.components.SectionHeader
import im.flume.hearth.core.ui.components.SongRow
import im.flume.hearth.core.ui.components.albumKind
import im.flume.hearth.core.ui.components.remoteAlbumId
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.ui.theme.NotOnServerAlpha
import im.flume.hearth.core.ui.screens.coverArt
import im.flume.hearth.core.ui.screens.isWideWindow

/** The artist page layout, shared by artists on the server and ones that aren't. */
@Composable
internal fun ArtistLayout(
    name: String,
    headerColor: Color,
    art: @Composable (Dp) -> Unit,
    counts: String,
    buttons: @Composable () -> Unit,
    popular: List<SongItem>,
    onPlayOwned: (SongEntity) -> Unit,
    discography: Discography,
    dimmed: Boolean = false,
    status: (@Composable () -> Unit)? = null,
    more: LazyListScope.() -> Unit = {},
) {
    val actions = LocalActions.current
    val wide = isWideWindow()
    val titleColor = if (dimmed) HearthTheme.colors.onCover.copy(alpha = NotOnServerAlpha) else Color.Unspecified
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = HearthTheme.spacing.xl)) {
        item(key = "header") {
            CoverGradientHeader(headerColor) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BackButton()
                        Spacer(Modifier.weight(1f))
                        DownloadsIndicator(Modifier.statusBarsPadding())
                    }
                    if (wide) {
                        Row(Modifier.fillMaxWidth().padding(start = HearthTheme.spacing.gutter), verticalAlignment = Alignment.CenterVertically) {
                            art(160.dp)
                            Column(Modifier.weight(1f).padding(start = HearthTheme.spacing.s)) {
                                Text(name, style = MaterialTheme.typography.headlineMedium, color = titleColor, modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter))
                                Text(counts, color = HearthTheme.colors.textSecondary, modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.xs))
                                status?.let { Box(Modifier.padding(horizontal = HearthTheme.spacing.gutter)) { it() } }
                                buttons()
                            }
                        }
                    } else {
                        art(180.dp)
                        Spacer(Modifier.height(HearthTheme.spacing.l))
                        Text(name, style = MaterialTheme.typography.headlineMedium, color = titleColor, textAlign = TextAlign.Center)
                        if (counts.isNotBlank()) Text(counts, color = HearthTheme.colors.textSecondary, modifier = Modifier.padding(HearthTheme.spacing.xs))
                        status?.invoke()
                    }
                }
            }
        }
        if (!wide) item(key = "buttons") { buttons() }
        if (popular.isNotEmpty()) {
            item(key = "popular-head") { SectionHeader("Popular") }
            itemsIndexed(popular, key = { _, it -> "top:${it.key}" }) { i, item ->
                Column(Modifier.animateItem()) {
                    when (item) {
                        is SongItem.Owned -> SongRow(item.song, onClick = { onPlayOwned(item.song) })
                        is SongItem.Missing -> MissingSongRow(item.track, subtitle = item.track.albumTitle ?: item.track.artist)
                    }
                }
            }
        }
        albumCarousel("albums", "Albums", discography.albums) { actions.openAlbum(it) }
        albumCarousel("singles", "Singles & EPs", discography.singles) { actions.openAlbum(it) }
        more()
    }
}

private fun LazyListScope.albumCarousel(key: String, title: String, albums: List<AlbumItem>, onOpen: (String) -> Unit) {
    if (albums.isEmpty()) return
    item(key = key) {
        Column(Modifier.animateItem()) {
            SectionHeader(title)
            LazyRow(contentPadding = PaddingValues(horizontal = HearthTheme.spacing.gutter), horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.carouselSpacing)) {
                items(albums, key = { it.key }) { a ->
                    when (a) {
                        is AlbumItem.Owned -> MediaCard(
                            a.album.name, a.album.year?.toString().orEmpty(), onClick = { onOpen(a.album.id) },
                            coverArt = a.album.coverArt, modifier = Modifier.animateItem(),
                        )
                        is AlbumItem.Missing -> MissingAlbumCard(
                            a.album, a.album.year ?: albumKind(a.album), modifier = Modifier.animateItem(),
                        ) { onOpen(remoteAlbumId(a.album.id)) }
                    }
                }
            }
        }
    }
}
