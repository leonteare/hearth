package im.flume.hearth.ui.components

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.SurfaceHigh
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
        modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack)
        Text(
            title.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        actions()
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
        Text(title, style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.weight(1f))
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
