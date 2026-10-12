// The song row used in every list, and a carousel row of albums.
package im.flume.hearth.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.data.AlbumEntity
import im.flume.hearth.core.data.DownloadState
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.core.ui.theme.HearthTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: SongEntity,
    onClick: () -> Unit,
    showCover: Boolean = true,
    leading: String? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** Null when not selecting; otherwise whether this row is selected. */
    selected: Boolean? = null,
    onLongPress: (() -> Unit)? = null,
    /** The line under the title; Search puts "Song • " in front so it reads apart from albums and shows. */
    subtitle: String = song.artist,
) {
    val rowState by rememberSongRowState(song.id)
    val actions = LocalActions.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val dl = rowState.downloadState
    val playable = rowState.playable
    val isPlaying = rowState.isPlaying
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected == true) accent.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(
                // Still tappable when it can't play, so a tap explains why instead of doing nothing.
                onClick = { if (playable || selected != null) onClick() else actions.showMessage(NOT_DOWNLOADED_OFFLINE) },
                onLongClickLabel = if (onLongPress != null) "Select songs" else "Song options",
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (onLongPress != null) onLongPress() else menu = true
                },
            )
            .padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Icon(
                if (selected) HearthTheme.icons.selected else HearthTheme.icons.unselected,
                if (selected) "Selected" else "Not selected",
                tint = if (selected) accent else HearthTheme.colors.textSecondary,
                modifier = Modifier.padding(end = HearthTheme.spacing.coverGap).size(24.dp),
            )
        }
        if (leading != null) {
            Text(leading, Modifier.width(28.dp), color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        if (showCover) {
            CoverArt(song.coverArt, HearthTheme.spacing.rowCover, requestSize = 150, fallback = song.album)
            Spacer(Modifier.width(HearthTheme.spacing.coverGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    isPlaying -> accent
                    !playable -> HearthTheme.colors.textDim
                    else -> HearthTheme.colors.text
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (dl) {
                    DownloadState.DONE -> Icon(HearthTheme.icons.downloaded, "Downloaded", Modifier.size(11.dp), tint = accent)
                    DownloadState.QUEUED, DownloadState.DOWNLOADING -> Icon(HearthTheme.icons.downloading, "Downloading", Modifier.size(14.dp), tint = HearthTheme.colors.textSecondary)
                    // Not just dimmer text: an icon says why it can't play.
                    else -> if (!playable) Icon(HearthTheme.icons.offline, "Not downloaded", Modifier.size(14.dp), tint = HearthTheme.colors.textDim)
                }
                if ((dl != null && dl != DownloadState.FAILED) || !playable) Spacer(Modifier.width(HearthTheme.spacing.xs))
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (playable) HearthTheme.colors.textSecondary else HearthTheme.colors.textDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (song.starred) Icon(HearthTheme.icons.likeOn, "Liked", Modifier.size(16.dp), tint = accent)
        if (selected == null) {
            IconButton(onClick = { menu = true }) { Icon(HearthTheme.icons.more, "More", tint = HearthTheme.colors.textSecondary) }
            SongMenu(song, menu, onDismiss = { menu = false }, onRemoveFromPlaylist = onRemoveFromPlaylist)
        }
    }
}

@Composable
fun AlbumRow(title: String, albums: List<AlbumEntity>) {
    if (albums.isEmpty()) return
    val actions = LocalActions.current
    SectionHeader(title)
    LazyRow(contentPadding = PaddingValues(horizontal = HearthTheme.spacing.gutter), horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.carouselSpacing)) {
        items(albums, key = { it.id }) { a -> MediaCard(a.name, a.artist, onClick = { actions.openAlbum(a.id) }, coverArt = a.coverArt) }
    }
}
