// Settings home: links to the General, Storage and Appearance pages.
package im.flume.hearth.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.theme.HearthTheme

/** Settings home: three groups, each its own page. */
@Composable
fun SettingsScreen() {
    val actions = LocalActions.current
    SettingsPage("Settings") {
        listOf(
            Triple("General", "Account, podcasts & audiobooks sign-in, library, playback, updates", HearthTheme.icons.settingsGeneral) to "settings/general",
            Triple("Storage", "Downloads, cache, getting music you don't have", HearthTheme.icons.storage) to "settings/storage",
            Triple("Appearance", "Accent colour, lyrics", HearthTheme.icons.appearance) to "settings/appearance",
        ).forEach { (row, route) ->
            val (title, subtitle, icon) = row
            Row(
                Modifier.fillMaxWidth().clickable { actions.open(route) }.padding(horizontal = HearthTheme.spacing.gutter, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(HearthTheme.spacing.l))
                Column(Modifier.weight(1f)) {
                    Text(title)
                    Text(subtitle, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(HearthTheme.icons.chevron, null, tint = HearthTheme.colors.textSecondary)
            }
        }
    }
}
