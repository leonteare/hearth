package im.flume.hearth.ui.components

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import im.flume.hearth.data.DownloadCounts
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.NoticeDot
import im.flume.hearth.ui.theme.SurfaceHigh
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import im.flume.hearth.ui.theme.TextSecondary

/** Back arrow; goes back through the system back dispatcher unless [onBack] is given. */
@Composable
fun BackButton(onBack: (() -> Unit)? = null) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    IconButton(onClick = { if (onBack != null) onBack() else dispatcher?.onBackPressed() }, modifier = Modifier.statusBarsPadding()) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
    }
}

/** Title bar for sub-pages (settings, queue, browse lists, stats): back arrow, title, optional actions. */
@Composable
fun TopBar(
    title: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack)
        Text(
            title.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        DownloadsIndicator()
        actions()
    }
}

/** Big title at the top of a tab page (Home, Search, Your Library), with optional icon actions. */
@Composable
fun PageHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = Dimens.Gutter, end = 4.dp, top = Dimens.PageTitleTop)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
        DownloadsIndicator()
        actions()
    }
}

/**
 * What's on its way to this phone, for the small downloads icon in every page's header. [downloading]
 * and [waiting] count songs, episodes and books (plus music on its way to the server); [failed] counts
 * the ones that need a look.
 */
data class DownloadsBadge(val downloading: Int, val waiting: Int, val failed: Int) {
    val inProgress: Int get() = downloading + waiting

    /** What TalkBack reads for the icon. */
    val description: String get() = buildString {
        append("Downloads, $inProgress in progress")
        if (failed > 0) append(", $failed couldn't download")
    }

    companion object {
        /** Null hides the icon: nothing downloading, waiting or failed. */
        fun of(counts: DownloadCounts, coming: Int, comingFailed: Int): DownloadsBadge? {
            val badge = DownloadsBadge(counts.downloading, counts.queued + coming, counts.failed + comingFailed)
            return badge.takeIf { it.inProgress > 0 || it.failed > 0 }
        }
    }
}

/** Set by the app root; null while nothing is downloading, and on the Downloads page itself. */
val LocalDownloadsBadge = compositionLocalOf<DownloadsBadge?> { null }

/**
 * The small downloads icon at the top right of every page, just left of the page's own buttons so
 * those never move. Only there while something is downloading, waiting or failed: a spinning ring
 * while a download is running, a still ring while they wait (e.g. for Wi-Fi), and a red dot when
 * something couldn't download. Tapping it opens the Downloads page.
 */
@Composable
fun DownloadsIndicator(modifier: Modifier = Modifier) {
    val badge = LocalDownloadsBadge.current ?: return
    val actions = LocalActions.current
    val accent = MaterialTheme.colorScheme.primary
    IconButton(onClick = { actions.open("downloads") }, modifier = modifier.semantics { contentDescription = badge.description }) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (badge.downloading > 0) {
                CircularProgressIndicator(Modifier.fillMaxSize(), color = accent, strokeWidth = 2.dp, trackColor = accent.copy(alpha = 0.2f))
            } else if (badge.waiting > 0) {
                CircularProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxSize(), color = accent, strokeWidth = 2.dp, trackColor = accent.copy(alpha = 0.35f))
            }
            Icon(Icons.Default.ArrowDownward, null, tint = if (badge.inProgress > 0) accent else TextSecondary, modifier = Modifier.size(16.dp))
            if (badge.failed > 0) {
                Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(HearthShapes.Round).background(NoticeDot))
            }
        }
    }
}

/** Heading above a group of rows or a carousel. [trailing] holds e.g. a "Clear" button. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(
            start = Dimens.Gutter,
            end = if (trailing != null) 4.dp else Dimens.Gutter,
            top = 24.dp,
            bottom = 12.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke(this)
    }
}

/**
 * Rounded card for notices (update ready, crash report, repeat explanation): optional icon, a bold
 * title, a muted body, an optional [trailing] control beside the text, extra [content] and a row of
 * [actions] underneath. [tinted] uses a faint accent background instead of the raised surface.
 */
@Composable
fun BannerCard(
    title: String?,
    body: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tinted: Boolean = false,
    bodyMaxLines: Int = Int.MAX_VALUE,
    trailing: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Gutter, vertical = 8.dp)
            .clip(HearthShapes.Card)
            .background(if (tinted) accent.copy(alpha = 0.15f) else SurfaceHigh)
            .padding(Dimens.CardPadding)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = accent)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                title?.let { Text(it, fontWeight = FontWeight.Bold) }
                body?.let {
                    Text(
                        it,
                        // With no title the body is the message, so it gets full contrast.
                        color = if (title != null) TextSecondary else Color.Unspecified,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = bodyMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
        content?.invoke(this)
        if (actions != null) Row(content = actions)
    }
}

/** A sub-page that is still loading: the back bar (with [title] if known) over a centred spinner. */
@Composable
fun LoadingPage(title: String? = null) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

/** A sub-page whose content can't be shown (e.g. a stale link): the back bar over a centred [EmptyState]. */
@Composable
fun UnavailablePage(icon: ImageVector, message: String) {
    Column(Modifier.fillMaxSize()) {
        TopBar(null)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState(icon, message) }
    }
}

/** Empty list placeholder: a muted icon, one line of text and an optional action button. */
@Composable
fun EmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, color = TextSecondary, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}
