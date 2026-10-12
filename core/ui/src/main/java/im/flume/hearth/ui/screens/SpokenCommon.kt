// Shared podcast and audiobook pieces: routes, play helpers, the download icon and listened bar, the
// cover-tinted page header, expandable description, round play button and the sign-in prompt.
package im.flume.hearth.ui.screens

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import im.flume.hearth.AppContainer
import im.flume.hearth.playback.SpokenNowPlaying
import im.flume.hearth.spoken.LocalBookIds
import im.flume.hearth.spoken.SpokenDownloadState
import im.flume.hearth.ui.components.Actions
import im.flume.hearth.ui.components.BackButton
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.CoverGradientHeader
import im.flume.hearth.ui.components.DownloadsIndicator
import im.flume.hearth.ui.components.EmptyState
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.rememberCoverColor
import im.flume.hearth.ui.theme.CoverSize
import im.flume.hearth.ui.theme.HearthTheme

/** The episode or book in the player (null while music plays), so rows can show what's playing. */
val LocalSpokenNow = compositionLocalOf<SpokenNowPlaying?> { null }

fun podcastRoute(id: String) = "podcast/${Uri.encode(id)}"
fun bookRoute(id: String) = "book/${Uri.encode(id)}"
/** The show page for a feed that may not be on the server yet (from Search). */
fun podcastFeedRoute(feedUrl: String) = "podcast-feed/${Uri.encode(feedUrl)}"

/** Cover of a podcast or book (by its id on the server), or null when signed out. */
fun AppContainer.spokenCover(libraryItemId: String, size: Int = 300): String? = spoken.coverUrl(libraryItemId, size)

/** Plays an episode, unless it can't be: offline and not downloaded. */
fun playEpisode(c: AppContainer, actions: Actions, libraryItemId: String, episodeId: String, downloaded: Boolean, startSec: Double? = null) {
    if (c.network.cannotStream && !downloaded) {
        actions.showMessage("You're offline and this episode isn't downloaded")
        return
    }
    c.player.playEpisode(libraryItemId, episodeId, startSec)
}

/**
 * Removes one downloaded episode straight away with Undo, the same as a single song: one episode is
 * cheap to get back, so asking first would only slow people down.
 */
fun removeEpisodeDownload(c: AppContainer, actions: Actions, libraryItemId: String, episodeId: String) {
    c.spoken.downloads.remove(libraryItemId, episodeId)
    actions.showUndo("Download removed") { c.spoken.downloads.downloadEpisode(libraryItemId, episodeId) }
}

fun playBook(c: AppContainer, actions: Actions, libraryItemId: String, downloaded: Boolean, startSec: Double? = null) {
    if (c.network.cannotStream && !downloaded && !LocalBookIds.isLocal(libraryItemId)) {
        actions.showMessage("You're offline and this book isn't downloaded")
        return
    }
    c.player.playBook(libraryItemId, startSec)
}

/** A small icon for an episode's or book's download: ticked when it's on the phone. */
@Composable
fun SpokenDownloadIcon(state: SpokenDownloadState?, modifier: Modifier = Modifier) {
    when (state) {
        SpokenDownloadState.DONE -> Icon(HearthTheme.icons.downloaded, "Downloaded", modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
        SpokenDownloadState.QUEUED, SpokenDownloadState.DOWNLOADING -> Icon(HearthTheme.icons.downloading, "Downloading", modifier.size(14.dp), tint = HearthTheme.colors.textSecondary)
        SpokenDownloadState.FAILED -> Icon(HearthTheme.icons.failed, "Couldn't download", modifier.size(14.dp), tint = HearthTheme.colors.destructive)
        else -> {}
    }
}

/** A thin progress bar for how far through an episode or book you are. */
@Composable
fun ListenedBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.height(3.dp).clip(HearthTheme.shapes.round),
        color = color,
        trackColor = HearthTheme.colors.textSecondary.copy(alpha = 0.25f),
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

fun downloadingMessage(c: AppContainer): String =
    if (c.session.settings.value.wifiOnlyDownloads && !c.network.wifi.value) "Downloading when you're on Wi-Fi" else "Downloading"

// ---------------------------------------------------------------------------------- page header

/** Expandable description: three lines, tap for the rest. */
@Composable
fun Description(text: String?) {
    // Feeds send HTML (or plain text with entities like &amp;): show it as text.
    val clean = remember(text) {
        text?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }
    }?.takeIf { it.isNotEmpty() } ?: return
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.animateContentSize()
            .padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s)
    ) {
        Text(
            clean, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
        )
        Text(if (expanded) "Less" else "More", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = HearthTheme.spacing.xxs))
    }
}

/** Cover-tinted header shared by the show and book pages. */
@Composable
fun SpokenHeader(
    coverUrl: String?, title: String, lines: List<String>, artSize: Dp = 200.dp,
    /** Opens the page's ⋮ menu, shown top right opposite the back button. */
    onMenu: (() -> Unit)? = null,
) {
    val headerColor = rememberCoverColor(null, imageUrl = coverUrl)
    CoverGradientHeader(headerColor) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BackButton()
                Spacer(Modifier.weight(1f))
                // Same status-bar inset as the back button, so the two line up.
                DownloadsIndicator(Modifier.statusBarsPadding())
                if (onMenu != null) IconButton(onClick = onMenu, modifier = Modifier.statusBarsPadding()) { Icon(HearthTheme.icons.more, "More") }
            }
            CoverArt(null, artSize, requestSize = CoverSize.LARGE, fallback = title, url = coverUrl)
            Spacer(Modifier.height(HearthTheme.spacing.l))
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = HearthTheme.spacing.l))
            lines.filter { it.isNotBlank() }.forEach { line ->
                Text(
                    line, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = HearthTheme.spacing.l, vertical = HearthTheme.spacing.xxs),
                )
            }
        }
    }
}

/** Big round play button, as on album pages. */
@Composable
fun RoundPlayButton(label: String, playing: Boolean = false, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = HearthTheme.shapes.round,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        Icon(if (playing) HearthTheme.icons.pause else HearthTheme.icons.play, label, modifier = Modifier.size(32.dp))
    }
}

/** Your Library's Podcasts tab when there's nothing to show, and its "add" rows. */
@Composable
fun SpokenSignInPrompt(what: String) {
    val actions = LocalActions.current
    EmptyState(
        HearthTheme.icons.podcast,
        "Sign in to podcasts & audiobooks in Settings to see your $what here.",
        action = "Open Settings",
        // Lands on the sign-in itself, not the top of Settings.
        onAction = { actions.open("settings/general/podcasts") },

    )
}
