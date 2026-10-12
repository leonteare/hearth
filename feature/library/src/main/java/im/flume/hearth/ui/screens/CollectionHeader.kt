// The top of a collection page: the big header (art, title, find-in-list box), its buttons, and the slim bar.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.ui.components.AvailabilityIcon
import im.flume.hearth.core.ui.components.BackButton
import im.flume.hearth.core.ui.components.CoverArt
import im.flume.hearth.core.ui.components.CoverGradientHeader
import im.flume.hearth.core.ui.components.DownloadToggle
import im.flume.hearth.core.ui.components.DownloadsIndicator
import im.flume.hearth.core.ui.components.PlayShuffleButtons
import im.flume.hearth.core.ui.components.RemoteCover
import im.flume.hearth.core.ui.theme.CoverSize
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.ui.theme.NotOnServerAlpha

/**
 * The big header at the top of the list, over a wash of the cover's colour. Landscape puts the art
 * on the left with [buttons] beside it; portrait centres everything and the caller shows [buttons] below.
 */
@Composable
internal fun CollectionHeader(
    title: String,
    subtitleLine: String,
    cover: String?,
    headerColor: Color,
    headerIcon: (@Composable (Dp) -> Unit)?,
    remote: RemoteHeader?,
    wide: Boolean,
    searchable: Boolean,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    searchFocus: FocusRequester,
    onHeightChanged: (Int) -> Unit,
    headerActions: @Composable () -> Unit,
    buttons: @Composable () -> Unit,
) {
    val titleColor = if (remote != null) HearthTheme.colors.onCover.copy(alpha = NotOnServerAlpha) else Color.Unspecified
    CoverGradientHeader(headerColor, Modifier.fillMaxWidth().onSizeChanged { onHeightChanged(it.height) }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BackButton()
                Spacer(Modifier.weight(1f))
                Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    DownloadsIndicator()
                    // Same search bar the pull-down opens, for anyone who doesn't know the gesture.
                    if (searchable && !searchOpen) {
                        IconButton(onClick = onOpenSearch) { Icon(HearthTheme.icons.search, "Find in ${if (title.isBlank()) "this list" else title}") }
                    }
                    headerActions()
                }
            }
            // Fully qualified on purpose: inside a Column the imported name would pick ColumnScope's
            // version, which animates differently.
            androidx.compose.animation.AnimatedVisibility(searchOpen) {
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                OutlinedTextField(
                    value = query, onValueChange = onQueryChange,
                    placeholder = { Text("Find in ${if (title.isBlank()) "this list" else title}") },
                    leadingIcon = { Icon(HearthTheme.icons.search, null) },
                    trailingIcon = {
                        IconButton(onClick = onCloseSearch) { Icon(HearthTheme.icons.close, "Close search") }
                    },
                    singleLine = true,
                    shape = HearthTheme.shapes.card,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s).focusRequester(searchFocus),
                )
            }
            if (wide) {
                // Landscape: art on the left, title and buttons beside it, so the list stays in view.
                Row(Modifier.fillMaxWidth().padding(start = HearthTheme.spacing.gutter), verticalAlignment = Alignment.CenterVertically) {
                    CollectionArt(160.dp, title, cover, headerIcon, remote)
                    Column(Modifier.weight(1f).padding(start = HearthTheme.spacing.s)) {
                        Text(title, style = MaterialTheme.typography.headlineMedium, color = titleColor, modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter))
                        Text(
                            subtitleLine, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.xs),
                        )
                        RemoteStatusLine(remote, TextAlign.Start)
                        buttons()
                    }
                }
            } else {
                CollectionArt(220.dp, title, cover, headerIcon, remote)
                Spacer(Modifier.height(HearthTheme.spacing.l))
                Text(title, style = MaterialTheme.typography.headlineMedium, color = titleColor, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter))
                Text(
                    subtitleLine,
                    color = HearthTheme.colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.xs),
                )
                RemoteStatusLine(remote, TextAlign.Center)
            }
        }
    }
}

/** The header's artwork: remote artwork, the caller's icon, or the cover, in that order. */
@Composable
private fun CollectionArt(size: Dp, title: String, cover: String?, headerIcon: (@Composable (Dp) -> Unit)?, remote: RemoteHeader?) {
    when {
        remote != null -> Box(Modifier.size(size).clip(HearthTheme.shapes.cover)) { RemoteCover(remote.coverUuid, title, 640) }
        headerIcon != null -> headerIcon(size)
        else -> CoverArt(cover, size, requestSize = CoverSize.LARGE, fallback = title)
    }
}

/** For music not on the server: whether it's on its way, under the title. Nothing otherwise. */
@Composable
private fun RemoteStatusLine(remote: RemoteHeader?, align: TextAlign) {
    remote?.let { r ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (align == TextAlign.Center) Arrangement.Center else Arrangement.Start,
        ) {
            AvailabilityIcon(r.request)
            Spacer(Modifier.width(HearthTheme.spacing.xs))
            Text(r.status, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Play, Shuffle, the download toggle and (albums only) the heart. Music not on the server gets a
 * preview instead of Shuffle, and its own download toggle from [remote].
 */
@Composable
internal fun CollectionButtons(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    remote: RemoteHeader?,
    downloaded: Boolean,
    downloadProgress: Float?,
    onDownloadToggle: () -> Unit,
    saved: Boolean?,
    onSavedChange: (Boolean) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    PlayShuffleButtons(
        onPlay = onPlay,
        onShuffle = if (remote != null) null else onShuffle,
        playLabel = if (remote != null) "Play preview" else "Play",
        extra = {
            if (remote == null) DownloadToggle(downloaded = downloaded, progress = downloadProgress, onClick = onDownloadToggle)
            remote?.onDownload?.let { onDownload -> DownloadToggle(downloaded = remote.toPhone, progress = null, onClick = onDownload) }
            if (saved != null) {
                IconButton(onClick = { onSavedChange(!saved) }) {
                    Icon(
                        if (saved) HearthTheme.icons.likeOn else HearthTheme.icons.like,
                        when {
                            remote != null && saved -> "Don't save it when it arrives"
                            remote != null -> "Save this album"
                            saved -> "Remove from Your Library"
                            else -> "Save to Your Library"
                        },
                        tint = if (saved) accent else HearthTheme.colors.textSecondary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        },
    )
}

/** Slim bar with the title and a play button, fading in as the big header scrolls away. */
@Composable
internal fun CollectionTopBar(title: String, headerColor: Color, alpha: () -> Float, onPlay: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha() }
            .background(lerp(headerColor, HearthTheme.colors.background, 0.35f))
            .statusBarsPadding()
            .padding(end = HearthTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton()
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        DownloadsIndicator()
        FilledIconButton(
            onClick = onPlay,
            // 40dp circle inside a 48dp touch target.
            modifier = Modifier.minimumInteractiveComponentSize().size(40.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary),
        ) { Icon(HearthTheme.icons.play, "Play") }
    }
}
