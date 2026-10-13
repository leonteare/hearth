// Settings > General: the app, account, library, playback and streaming quality.
package im.flume.hearth.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.playback.QueueStore
import im.flume.hearth.domain.music.sync.SyncState
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlinx.coroutines.launch
import im.flume.hearth.core.ui.screens.UpdateSettingsRow

/**
 * [showSpokenAccount] scrolls straight to the podcasts & audiobooks sign-in, for the "Open Settings"
 * prompts on those tabs, so nobody has to hunt for it.
 */
@Composable
fun GeneralSettings(showSpokenAccount: Boolean = false) {
    val c = LocalContext.current.container
    val context = LocalContext.current
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val creds by c.session.credentials.collectAsStateWithLifecycle()
    val songCount by remember { c.db.library().songCount() }.collectAsStateWithLifecycle(0)
    val sync by c.sync.state.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    val usageCounts by remember { c.usage.changes() }.collectAsStateWithLifecycle(c.usage.all())
    var usageOpen by remember { mutableStateOf(false) }
    val spokenAccountView = remember { BringIntoViewRequester() }
    if (showSpokenAccount) {
        LaunchedEffect(Unit) {
            // Wait for the page to be laid out, so there's somewhere to scroll to.
            withFrameNanos { }
            spokenAccountView.bringIntoView()
        }
    }

    SettingsPage("General") {
        Group("App", { UpdateSettingsRow() })

        Group(
            "Account",
            { Info("Signed in as", creds?.username.orEmpty()) },
            { Info("Server", creds?.serverUrl.orEmpty()) },
            {
                Clickable("Sign out", "Your downloads stay on this phone", color = HearthTheme.colors.destructive) {
                    confirmLogout = true
                }
            },
        )

        Column(Modifier.bringIntoViewRequester(spokenAccountView)) { PodcastsAndAudiobooksAccount() }

        Group(
            "Library",
            { Info("Songs in your library", songCount.toString()) },
            {
                Clickable(
                    title = if (sync is SyncState.Running) "Syncing…" else "Resync library now",
                    subtitle = (sync as? SyncState.Failed)?.message ?: "Fetches any changes from Navidrome",
                ) { c.appScope.launch { c.sync.fullSync(c.api.scanStatus()?.lastScan) } }
            },
        )

        Group(
            "Playback",
            {
                Choice("Volume levelling", settings.volumeLevelling, listOf(0 to "Off", 1 to "Per song", 2 to "Per album")) { v ->
                    c.session.updateSettings { it.copy(volumeLevelling = v) }
                }
            },
        )
        PageNote("Uses the loudness info in your music files so songs play at a similar volume. Per album keeps the intended differences between songs on an album.")
        Group(
            "",
            {
                Toggle("Smart shuffle", settings.smartShuffle, "Avoid playing the same artist twice in a row") { v ->
                    c.session.updateSettings { it.copy(smartShuffle = v) }
                }
            },
            {
                Toggle("Offline mode", settings.offlineMode, "Only play downloaded songs, even with a connection") { v ->
                    c.session.updateSettings { it.copy(offlineMode = v) }
                }
            },
        )

        Group(
            "Streaming",
            { Choice("Quality on Wi-Fi", settings.wifiBitrate, BITRATES) { v -> c.session.updateSettings { it.copy(wifiBitrate = v) } } },
            { Choice("Quality on mobile data", settings.mobileBitrate, BITRATES) { v -> c.session.updateSettings { it.copy(mobileBitrate = v) } } },
        )
        PageNote("Lower qualities are converted on the server, so seeking within a song that isn't cached yet can be slower.")

        // Tucked away and closed: only of interest when deciding what to keep in the app.
        val aboutRows = buildList<@Composable () -> Unit> {
            add {
                Clickable(
                    "Feature use on this phone",
                    if (usageOpen) "How often each feature has been used. Never sent anywhere; it helps decide what to keep."
                    else "Tap to show how often each feature has been used",
                ) { usageOpen = !usageOpen }
            }
            if (usageOpen) add { UsageCounts(usageCounts) }
        }
        Group("About this phone", *aboutRows.toTypedArray())
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out?") },
            text = { Text("Your downloads stay on this phone, ready for when you sign in again. Anything still waiting to download carries on after you sign back in.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    c.player.stopAndClear()
                    c.appScope.launch {
                        // Downloads, pins, lyrics, play history and the library index are kept: signing out
                        // is usually an accident, and signing back in should find everything still there.
                        // Wait for the download worker to stop before the login it uses goes away.
                        c.downloads.stopWorker()
                        QueueStore(context.filesDir).clear()
                        c.nativeApi.forgetSession()
                        c.session.logout()
                        c.downloads.reset()
                    }
                }) { Text("Sign out", color = HearthTheme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

/** How often each feature has been used, listed inside the "About this phone" card once it's open. */
@Composable
private fun UsageCounts(counts: List<Pair<String, Int>>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s)) {
        counts.sortedByDescending { it.second }.forEach { (name, count) ->
            Row(Modifier.fillMaxWidth().padding(vertical = HearthTheme.spacing.xxs)) {
                Text(name, modifier = Modifier.weight(1f))
                Text("$count", color = HearthTheme.colors.textSecondary)
            }
        }
    }
}

/** A quiet note under a card, explaining the setting above it. */
@Composable
private fun PageNote(text: String) {
    Text(
        text,
        color = HearthTheme.colors.textSecondary,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(
            start = HearthTheme.spacing.gutter + HearthTheme.spacing.xs,
            end = HearthTheme.spacing.gutter,
            top = HearthTheme.spacing.s,
        ),
    )
}
