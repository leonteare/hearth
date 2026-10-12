// The Follow pill, shared by artist pages and podcast pages.
package im.flume.hearth.core.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.ui.theme.HearthTheme

/** Outlined pill: "Follow" adds the artist to Your Library, "Following" when they're already there. */
@Composable
fun FollowButton(following: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        shape = HearthTheme.shapes.round,
        border = BorderStroke(1.dp, if (following) accent else HearthTheme.colors.textSecondary),
        contentPadding = PaddingValues(horizontal = HearthTheme.spacing.l, vertical = HearthTheme.spacing.xs),
        modifier = Modifier.height(32.dp),
    ) {
        Text(if (following) "Following" else "Follow", color = if (following) accent else HearthTheme.colors.text, style = MaterialTheme.typography.labelLarge)
    }
}
