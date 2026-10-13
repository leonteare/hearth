// Settings home: who you're signed in as, then the groups of settings, each its own page.
package im.flume.hearth.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.screens.UpdateSettingsRow
import im.flume.hearth.core.ui.theme.HearthTheme

/** The server a person signed into, as they'd say it out loud: "music.home", not the whole URL. */
private fun hostOf(url: String?): String =
    url.orEmpty().substringAfter("//").substringBefore('/').substringBefore(':')

@Composable
fun SettingsScreen() {
    val actions = LocalActions.current
    val creds by LocalContext.current.container.session.credentials.collectAsStateWithLifecycle()
    val username = creds?.username.orEmpty()
    val host = hostOf(creds?.serverUrl)

    SettingsPage("Settings") {
        ProfileHeader(
            name = username.replaceFirstChar { it.uppercase() },
            subtitle = if (host.isEmpty()) "" else "Signed in as $username · $host",
        )
        Group(
            "Your music",
            {
                NavRow("General", "Account, library, playback, updates", HearthTheme.icons.settingsGeneral) {
                    actions.open("settings/general")
                }
            },
            {
                NavRow("Storage", "Downloads, cache, getting music you don't have", HearthTheme.icons.storage) {
                    actions.open("settings/storage")
                }
            },
        )
        Group(
            "This phone",
            {
                NavRow("Appearance", "Accent colour, lyrics", HearthTheme.icons.appearance) {
                    actions.open("settings/appearance")
                }
            },
            {
                NavRow("Downloads", "What's saved, and what's still coming", HearthTheme.icons.downloadFilled) {
                    actions.open("downloads")
                }
            },
        )
        Group("App", { UpdateSettingsRow() })
    }
}
