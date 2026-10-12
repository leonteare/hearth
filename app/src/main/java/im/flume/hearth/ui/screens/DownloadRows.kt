// Rows on the Downloads page: music coming to the server, a song waiting to download, and the small grey notes.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.api.TidalType
import im.flume.hearth.container
import im.flume.hearth.data.RequestEntity
import im.flume.hearth.data.RequestIntent
import im.flume.hearth.data.RequestStatus
import im.flume.hearth.data.SongEntity
import im.flume.hearth.ui.components.AvailabilityIcon
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.LocalGetMusic
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.components.RemoteCover
import im.flume.hearth.ui.components.SongRow
import im.flume.hearth.ui.components.comingLabel
import im.flume.hearth.ui.components.remoteAlbumId
import im.flume.hearth.ui.components.remoteArtistId
import im.flume.hearth.ui.theme.Destructive
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.NotOnServerAlpha
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Something on its way to the server: where it is, and ⋯ to try again or cancel. */
@Composable
internal fun ComingRow(r: RequestEntity, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val get = LocalGetMusic.current
    val failed = r.status == RequestStatus.FAILED
    val what = when (r.type) {
        TidalType.ALBUM.api -> "Album · ${r.artist}"
        TidalType.ARTIST.api -> "All their music"
        else -> r.artist
    }
    MediaRow(
        title = r.title,
        subtitle = listOfNotNull(if (r.type == TidalType.ARTIST.api) comingLabel(r) else RequestIntent.stageLabel(r), what).joinToString(" · "),
        onClick = {
            when (r.type) {
                TidalType.ALBUM.api -> actions.openAlbum(remoteAlbumId(r.tidalId))
                TidalType.ARTIST.api -> actions.openArtist(remoteArtistId(r.tidalId))
                else -> get.requestMenu = r
            }
        },
        onLongClick = { get.requestMenu = r },
        modifier = modifier,
        round = r.type == TidalType.ARTIST.api,
        cover = { RemoteCover(r.coverUuid, r.title) },
        titleColor = Color.White.copy(alpha = NotOnServerAlpha),
        subtitleColor = if (failed) Destructive else TextSecondary,
        subtitleIcon = { AvailabilityIcon(r) },
        trailing = {
            if (failed) TextButton(onClick = { get.retry(r) }) { Text("Try again") }
            // What it'll do when it arrives: ♡ liked (or saved), ⬇ downloaded to this phone.
            if (!failed && r.type != TidalType.ARTIST.api) {
                val album = r.type == TidalType.ALBUM.api
                if (r.like) Icon(
                    Icons.Default.Favorite, if (album) "Will be saved" else "Will be liked",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 2.dp).size(16.dp),
                )
                if (r.toPhone) Icon(
                    Icons.Outlined.ArrowCircleDown, "Will download to your phone",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 2.dp).size(18.dp),
                )
            }
            IconButton(onClick = { get.requestMenu = r }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
        },
    )
}

@Composable
internal fun MoreLine(text: String) {
    Text(
        text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 8.dp),
    )
}

/** A song waiting to download, with a progress bar while it's on its way. Collects only its own progress. */
@Composable
internal fun PendingSongRow(song: SongEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    // null when not downloading; 0 when the size isn't known yet.
    val progress by remember(song.id) {
        c.downloads.progress.map { if (song.id in it) it[song.id] ?: 0f else null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(null)
    Column(modifier) {
        SongRow(song, onClick = onClick)
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress ?: 0f },
                modifier = Modifier.fillMaxWidth().padding(start = Dimens.Gutter + Dimens.RowCover + Dimens.CoverGap, end = Dimens.Gutter).height(3.dp),
                trackColor = TextSecondary.copy(alpha = 0.25f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}
