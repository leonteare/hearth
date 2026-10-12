// A row of equal-width choice chips, used for Search's filters and Your Library's sections.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.SurfaceHigh

/** Android's recommended smallest touch target. */
private val MIN_TOUCH_TARGET = 48.dp

/** Above this text size (set in the phone's settings) equal-width chips would cut their labels off. */
private const val LARGE_FONT_SCALE = 1.15f

/**
 * Choices side by side at equal width across the page, so none of them ever scrolls off or hides
 * under another control. Used for Search's filters and Your Library's Music · Podcasts · Audiobooks.
 * With large text they scroll sideways instead, at their natural width.
 */
@Composable
fun <T> EqualWidthChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    textStyle: TextStyle,
    minHeight: Dp,
    onSelect: (T) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    // With large text the labels can't fit side by side, so the row scrolls instead of cutting words off.
    val scrolls = LocalDensity.current.fontScale > LARGE_FONT_SCALE
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = Dimens.Gutter, vertical = 4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { f ->
            val on = f == selected
            // The tappable area is at least 48dp tall even when the chip drawn inside it is smaller.
            Box(
                Modifier
                    .then(if (scrolls) Modifier else Modifier.weight(1f))
                    .heightIn(min = MIN_TOUCH_TARGET)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(f) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .clip(HearthShapes.Chip)
                        .background(if (on) accent else SurfaceHigh)
                        .padding(horizontal = if (scrolls) 12.dp else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = if (scrolls) TextOverflow.Visible else TextOverflow.Clip,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else Color.White,
                        style = textStyle,
                    )
                }
            }
        }
    }
}
