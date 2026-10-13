// Rows on the Downloads page: its small-caps headings, music coming to the server, a song waiting to download, and the small grey notes.
package im.flume.hearth.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.network.TidalType
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.data.RequestEntity
import im.flume.hearth.domain.requests.RequestIntent
import im.flume.hearth.core.data.RequestStatus
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.core.ui.components.AvailabilityIcon
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.LocalGetMusic
import im.flume.hearth.core.ui.components.MediaRow
import im.flume.hearth.core.ui.components.RemoteCover
import im.flume.hearth.core.ui.components.SongRow
import im.flume.hearth.core.ui.components.comingLabel
import im.flume.hearth.core.ui.components.remoteAlbumId
import im.flume.hearth.core.ui.components.remoteArtistId
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.ui.theme.NotOnServerAlpha
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Headings are small, so a little tracking keeps them readable (as in Settings' groups). */
private val HeaderTracking = 0.8.sp

/**
 * A heading over a run of download rows, in the same quiet small-caps style as the groups in Settings,
 * with room for a button (such as "Try again") on the right.
 */
@Composable
internal fun DownloadsHeader(title: String, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(
            start = HearthTheme.spacing.gutter + HearthTheme.spacing.xs,
            end = if (trailing != null) HearthTheme.spacing.xs else HearthTheme.spacing.gutter,
            top = HearthTheme.spacing.xl,
            bottom = HearthTheme.spacing.s,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            color = HearthTheme.colors.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = HeaderTracking,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke(this)
    }
}

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
        titleColor = HearthTheme.colors.text.copy(alpha = NotOnServerAlpha),
        subtitleColor = if (failed) HearthTheme.colors.destructive else HearthTheme.colors.textSecondary,
        subtitleIcon = { AvailabilityIcon(r) },
        trailing = {
            if (failed) TextButton(onClick = { get.retry(r) }) { Text("Try again") }
            // What it'll do when it arrives: ♡ liked (or saved), ⬇ downloaded to this phone.
            if (!failed && r.type != TidalType.ARTIST.api) {
                val album = r.type == TidalType.ALBUM.api
                if (r.like) Icon(
                    HearthTheme.icons.likeOn, if (album) "Will be saved" else "Will be liked",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = HearthTheme.spacing.xxs).size(16.dp),
                )
                if (r.toPhone) Icon(
                    HearthTheme.icons.download, "Will download to your phone",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = HearthTheme.spacing.xxs).size(18.dp),
                )
            }
            IconButton(onClick = { get.requestMenu = r }) { Icon(HearthTheme.icons.more, "More", tint = HearthTheme.colors.textSecondary) }
        },
    )
}

@Composable
internal fun MoreLine(text: String) {
    Text(
        text, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s),
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
                modifier = Modifier.fillMaxWidth().padding(start = HearthTheme.spacing.gutter + HearthTheme.spacing.rowCover + HearthTheme.spacing.coverGap, end = HearthTheme.spacing.gutter).height(3.dp),
                trackColor = HearthTheme.colors.textSecondary.copy(alpha = 0.25f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}
