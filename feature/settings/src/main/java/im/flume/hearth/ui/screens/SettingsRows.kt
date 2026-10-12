// The building blocks every settings page uses: the page itself, section headings, and info, tap, switch and choice rows.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import im.flume.hearth.core.ui.components.ChoiceSheet
import im.flume.hearth.core.ui.components.TopBar
import im.flume.hearth.core.ui.theme.HearthTheme

internal val BITRATES = listOf(0 to "Original quality", 320 to "320 kbps", 256 to "256 kbps", 192 to "192 kbps", 128 to "128 kbps (saves data)")

@Composable
internal fun SettingsPage(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(title)
        content()
        Spacer(Modifier.height(HearthTheme.spacing.xl))
    }
}

@Composable
internal fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = HearthTheme.spacing.l), color = HearthTheme.colors.divider)
    if (title.isNotEmpty()) {
        Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = HearthTheme.spacing.gutter, top = HearthTheme.spacing.l, bottom = HearthTheme.spacing.xs))
    }
}

@Composable
internal fun Info(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s)) {
        Text(title)
        Text(value, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun Clickable(title: String, subtitle: String? = null, color: Color = HearthTheme.colors.text, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.m)) {
        Text(title, color = color)
        subtitle?.let { Text(it, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
internal fun Toggle(title: String, value: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s),
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
    Column(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.m)) {
        Text(title)
        Text(options.firstOrNull { it.first == value }?.second ?: "$value", color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
    ChoiceSheet(open, title, options, value, onDismiss = { open = false }, onPick = onChange)
}
