// The building blocks every settings page uses: the page itself, section headings, and info, tap, switch and choice rows.
package im.flume.hearth.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import im.flume.hearth.core.ui.components.ChoiceSheet
import im.flume.hearth.core.ui.components.TopBar
import im.flume.hearth.core.ui.theme.HearthTheme

/** Group headings are small, so a little tracking keeps them readable. */
private val GroupLabelTracking = 0.8.sp

internal val BITRATES = listOf(0 to "Original quality", 320 to "320 kbps", 256 to "256 kbps", 192 to "192 kbps", 128 to "128 kbps (saves data)")

@Composable
internal fun SettingsPage(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(title)
        content()
        Spacer(Modifier.height(HearthTheme.spacing.xl))
    }
}

/** A small heading above a group of rows, in the quiet style of the rest of the app. */
@Composable
internal fun Section(title: String) {
    if (title.isEmpty()) {
        Spacer(Modifier.height(HearthTheme.spacing.l))
        return
    }
    Text(
        title.uppercase(),
        color = HearthTheme.colors.textSecondary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = GroupLabelTracking,
        modifier = Modifier.padding(
            start = HearthTheme.spacing.gutter + HearthTheme.spacing.xs,
            top = HearthTheme.spacing.xl,
            bottom = HearthTheme.spacing.s,
        ),
    )
}

/**
 * A heading and its rows on one rounded card, hairlines between them: the shape the whole of Settings is
 * built from. Rows are passed one by one so the card knows where each one ends and can rule a line there.
 */
@Composable
internal fun Group(title: String = "", vararg rows: @Composable () -> Unit) {
    Section(title)
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = HearthTheme.spacing.gutter)
            .clip(HearthTheme.shapes.card)
            .background(HearthTheme.colors.surface),
    ) {
        rows.forEachIndexed { i, row ->
            if (i > 0) {
                HorizontalDivider(
                    Modifier.padding(start = HearthTheme.spacing.gutter),
                    color = HearthTheme.colors.divider,
                )
            }
            row()
        }
    }
}

/** Who you are signed in as, at the top of Settings: initial, name, and where the music comes from. */
@Composable
internal fun ProfileHeader(name: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(HearthTheme.spacing.avatar).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            // Signed out, or a name that starts with something that isn't a letter: a figure reads better
            // than an empty circle or a stray punctuation mark.
            val initial = name.firstOrNull { it.isLetterOrDigit() }
            if (initial == null) {
                Icon(HearthTheme.icons.artist, null, tint = HearthTheme.colors.onAccent)
            } else {
                Text(
                    initial.uppercase(),
                    color = HearthTheme.colors.onAccent,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.width(HearthTheme.spacing.l))
        Column(Modifier.weight(1f)) {
            Text(
                name.ifEmpty { "Not signed in" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** A row that opens another page: icon, what it holds, and a chevron. */
@Composable
internal fun NavRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.settingsRowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(HearthTheme.spacing.l))
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Icon(HearthTheme.icons.chevron, null, tint = HearthTheme.colors.textSecondary)
    }
}

@Composable
internal fun Info(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.settingsRowV)) {
        Text(title)
        Text(value, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun Clickable(title: String, subtitle: String? = null, color: Color = HearthTheme.colors.text, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.settingsRowV)) {
        Text(title, color = color)
        subtitle?.let { Text(it, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
internal fun Toggle(title: String, value: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s).padding(vertical = HearthTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium) }
        }
        Switch(value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}

@Composable
internal fun Choice(title: String, value: Int, options: List<Pair<Int, String>>, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.settingsRowV)) {
        Text(title)
        Text(options.firstOrNull { it.first == value }?.second ?: "$value", color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
    ChoiceSheet(open, title, options, value, onDismiss = { open = false }, onPick = onChange)
}
