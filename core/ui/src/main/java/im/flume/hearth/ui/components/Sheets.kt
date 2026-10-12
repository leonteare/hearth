// Bottom sheets: the options sheet, its menu rows, and a sheet of choices.
package im.flume.hearth.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.flume.hearth.ui.theme.HearthTheme

/** Bottom sheet for song / player options, with an optional header showing what it's about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    coverArt: String? = null,
    fallback: String? = subtitle,
    /** Artwork from elsewhere (podcast / audiobook covers), used instead of [coverArt]. */
    coverUrl: String? = null,
    /** False for plain text headers, e.g. "Sort by". */
    showCover: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!open) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = HearthTheme.colors.surfaceHigh,
    ) {
        if (title != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = HearthTheme.spacing.s), verticalAlignment = Alignment.CenterVertically) {
                if (showCover) {
                    CoverArt(coverArt, 52.dp, requestSize = 150, fallback = fallback, url = coverUrl)
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let { Text(it, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = HearthTheme.colors.divider)
        }
        Column(Modifier.padding(bottom = HearthTheme.spacing.l), content = content)
    }
}

/**
 * One row of an [ActionSheet]. [icon] may be null for plain choice lists; [checked] shows a tick on
 * the current choice. [tint] colours the icon (and the text, for destructive items).
 */
@Composable
fun MenuItem(
    text: String,
    icon: ImageVector?,
    tint: Color = HearthTheme.colors.text,
    checked: Boolean = false,
    tintText: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint)
            Spacer(Modifier.width(20.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                tintText -> tint
                checked -> MaterialTheme.colorScheme.primary
                else -> Color.Unspecified
            },
            modifier = Modifier.weight(1f),
        )
        if (checked) Icon(HearthTheme.icons.check, "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

/** A sheet of mutually exclusive choices, with a tick on the current one. */
@Composable
fun <T> ChoiceSheet(open: Boolean, title: String, options: List<Pair<T, String>>, selected: T, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    ActionSheet(open, onDismiss, title = title, showCover = false) {
        options.forEach { (value, label) ->
            MenuItem(label, null, checked = value == selected) { onPick(value); onDismiss() }
        }
    }
}
