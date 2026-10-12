// Settings home: links to the General, Storage and Appearance pages.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.theme.TextSecondary

/** Settings home: three groups, each its own page. */
@Composable
fun SettingsScreen() {
    val actions = LocalActions.current
    SettingsPage("Settings") {
        listOf(
            Triple("General", "Account, podcasts & audiobooks sign-in, library, playback, updates", Icons.Outlined.Settings) to "settings/general",
            Triple("Storage", "Downloads, cache, getting music you don't have", Icons.Outlined.Storage) to "settings/storage",
            Triple("Appearance", "Accent colour, lyrics", Icons.Outlined.Palette) to "settings/appearance",
        ).forEach { (row, route) ->
            val (title, subtitle, icon) = row
            Row(
                Modifier.fillMaxWidth().clickable { actions.open(route) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title)
                    Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextSecondary)
            }
        }
    }
}
