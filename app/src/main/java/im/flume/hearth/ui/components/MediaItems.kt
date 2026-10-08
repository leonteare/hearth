package im.flume.hearth.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import im.flume.hearth.ui.theme.CardTitleStyle
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.TextSecondary

/**
 * A list row: 48dp cover, title and one-line subtitle. Used for artists, albums, playlists, ranked
 * stats, queue rows and pickers. [SongRow] is its own component but uses the same metrics.
 *
 * - [cover] replaces the artwork (e.g. a coloured icon tile); it is clipped to the cover shape.
 * - [leading] sits before the cover (e.g. a rank number).
 * - [trailing] sits at the end (e.g. a drag handle); the row's end padding shrinks to make room.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    coverArt: String? = null,
    fallback: String? = title,
    round: Boolean = false,
    cover: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    subtitleColor: Color = TextSecondary,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val click = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(enabled = enabled, onClick = onClick ?: {}, onLongClick = onLongClick)
    } else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .then(click)
            .padding(
                start = Dimens.Gutter,
                end = if (trailing != null) 4.dp else Dimens.Gutter,
                top = Dimens.RowPaddingV,
                bottom = Dimens.RowPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (cover != null) {
            Box(Modifier.size(Dimens.RowCover).clip(if (round) HearthShapes.Round else HearthShapes.Cover)) { cover() }
        } else {
            CoverArt(
                coverArt, Dimens.RowCover,
                corner = if (round) Dimens.RowCover / 2 else Dimens.CoverCorner,
                requestSize = 150, fallback = fallback,
            )
        }
        Spacer(Modifier.width(Dimens.CoverGap))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = titleColor, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = subtitleColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
        trailing?.invoke(this)
    }
}

/** A solid colour tile with a white icon, for use as a [MediaRow] cover (Liked Songs, Downloads). */
@Composable
fun IconTile(icon: ImageVector, color: Color) {
    Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White)
    }
}

/**
 * A carousel card: square cover, then a semi-bold title and a muted subtitle. The ripple is clipped
 * to the card. [round] makes the cover a circle and centres the text (artists). [cover] replaces the
 * artwork (e.g. a mix's 2x2 grid).
 */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverArt: String? = null,
    width: Dp = Dimens.CardWidth,
    round: Boolean = false,
    subtitleLines: Int = 1,
    cover: (@Composable () -> Unit)? = null,
) {
    val align = if (round) TextAlign.Center else TextAlign.Start
    Column(modifier.width(width).clip(HearthShapes.Card).clickable(onClick = onClick)) {
        if (cover != null) cover()
        else CoverArt(coverArt, width, corner = if (round) width / 2 else Dimens.CoverCorner, fallback = title)
        Spacer(Modifier.height(8.dp))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = CardTitleStyle, textAlign = align, modifier = Modifier.fillMaxWidth())
        subtitle?.let {
            Text(
                it, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium, color = TextSecondary,
                textAlign = align, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
