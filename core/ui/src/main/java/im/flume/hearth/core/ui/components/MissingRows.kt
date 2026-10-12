// Songs, albums and artists that aren't on the server, as rows and cards that look like the usual ones, dimmed.
package im.flume.hearth.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.network.TidalAlbum
import im.flume.hearth.core.network.TidalArtist
import im.flume.hearth.core.network.TidalTrack
import im.flume.hearth.core.data.RequestStatus
import im.flume.hearth.core.ui.theme.NotOnServerAlpha
import im.flume.hearth.core.ui.theme.HearthTheme

/**
 * A song that isn't on the server, in the same layout as [SongRow]: tap previews it, ♡ gets it,
 * ⋯ (or a long press) has the rest.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MissingSongRow(
    track: TidalTrack,
    modifier: Modifier = Modifier,
    leading: String? = null,
    showCover: Boolean = true,
    subtitle: String = track.artist,
) {
    val get = LocalGetMusic.current
    val request = LocalRequests.current[trackKey(track.id)]
    val preview = LocalPreview.current?.takeIf { it.track.id == track.id }
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { get.preview(track) },
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); get.menuFor = track },
            )
            .padding(start = HearthTheme.spacing.gutter, end = HearthTheme.spacing.xs, top = HearthTheme.spacing.rowPaddingV, bottom = HearthTheme.spacing.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Text(leading, Modifier.width(28.dp), color = HearthTheme.colors.textSecondary.copy(alpha = NotOnServerAlpha), style = MaterialTheme.typography.bodyMedium)
        }
        if (showCover) {
            Box(Modifier.size(HearthTheme.spacing.rowCover).clip(HearthTheme.shapes.cover)) { RemoteCover(track.cover, track.displayTitle, 160) }
            Spacer(Modifier.width(HearthTheme.spacing.coverGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (preview != null) accent else HearthTheme.colors.text.copy(alpha = NotOnServerAlpha),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvailabilityIcon(request, previewing = preview != null, loading = preview?.loading == true)
                Spacer(Modifier.width(HearthTheme.spacing.xs))
                Text(
                    if (preview != null) "Preview" else comingLabel(request) ?: subtitle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = (if (request?.status == RequestStatus.FAILED) HearthTheme.colors.destructive else HearthTheme.colors.textSecondary).copy(alpha = NotOnServerAlpha),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        GetHeart(request, size = 20.dp) { get.heartTrack(track, request) }
        IconButton(onClick = { get.menuFor = track }) { Icon(HearthTheme.icons.more, "More", tint = HearthTheme.colors.textSecondary) }
    }
}

/** An album that isn't on the server, as a carousel card. */
@Composable
fun MissingAlbumCard(album: TidalAlbum, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val get = LocalGetMusic.current
    val request = LocalRequests.current[albumKey(album.id)]
    MediaCard(
        album.title,
        comingLabel(request) ?: subtitle,
        onClick = onClick,
        onLongClick = { get.albumMenuFor = album },
        modifier = modifier,
        dimmed = true,
        subtitleIcon = { AvailabilityIcon(request) },
        cover = { Box(Modifier.size(HearthTheme.spacing.cardWidth).clip(HearthTheme.shapes.cover)) { RemoteCover(album.cover, album.title) } },
    )
}

/** An album that isn't on the server, as a list row (Search). */
@Composable
fun MissingAlbumRow(album: TidalAlbum, onClick: () -> Unit) {
    val get = LocalGetMusic.current
    val request = LocalRequests.current[albumKey(album.id)]
    MediaRow(
        album.title,
        comingLabel(request) ?: listOfNotNull(albumKind(album), album.artist.ifBlank { null }, album.year).joinToString(" • "),
        onClick = onClick,
        onLongClick = { get.albumMenuFor = album },
        cover = { RemoteCover(album.cover, album.title) },
        titleColor = HearthTheme.colors.text.copy(alpha = NotOnServerAlpha),
        subtitleColor = HearthTheme.colors.textSecondary.copy(alpha = NotOnServerAlpha),
        subtitleIcon = { AvailabilityIcon(request) },
    )
}

/** An artist the server doesn't have, as a list row (Search). */
@Composable
fun MissingArtistRow(artist: TidalArtist, onClick: () -> Unit) {
    val request = LocalRequests.current[artistKey(artist.id)]
    MediaRow(
        artist.name,
        comingLabel(request) ?: "Artist",
        onClick = onClick,
        round = true,
        cover = { RemoteCover(artist.picture, artist.name) },
        titleColor = HearthTheme.colors.text.copy(alpha = NotOnServerAlpha),
        subtitleColor = HearthTheme.colors.textSecondary.copy(alpha = NotOnServerAlpha),
        subtitleIcon = { AvailabilityIcon(request) },
    )
}
