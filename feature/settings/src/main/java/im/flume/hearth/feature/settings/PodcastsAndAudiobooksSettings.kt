// Podcasts and audiobooks in Settings: the Audiobookshelf sign-in, what's on the phone, and the sign-in form shared with first sign-in.
package im.flume.hearth.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.data.spoken.AbsAccountStore
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.formatBytes
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlinx.coroutines.launch

/**
 * Podcasts and audiobooks on this phone (in Storage): what's downloaded, whether new episodes download
 * by themselves, and folders of audiobooks on the phone. The sign-in lives in General → Account.
 */
@Composable
internal fun PodcastsAndAudiobooksStorage() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val account by c.spoken.account.collectAsStateWithLifecycle()
    val spokenSettings by c.spoken.settings.collectAsStateWithLifecycle()
    val spokenBytes by remember { c.spoken.downloads.totalBytes }.collectAsStateWithLifecycle(0L)

    val rows = buildList<@Composable () -> Unit> {
        if (spokenBytes > 0) add { Info("Downloaded to this phone", formatBytes(spokenBytes)) }
        if (account != null) {
            add {
                Toggle("Download new episodes on Wi-Fi", spokenSettings.autoDownloadOnWifi, "The newest 3 unplayed episodes of shows you follow. Played episodes are removed from this phone.") { v ->
                    c.spoken.store.updateSettings { it.copy(autoDownloadOnWifi = v) }
                    if (v) scope.launch { c.spoken.refresh(force = true) }
                }
            }
        }
    }
    // Signed out and nothing downloaded: no heading over an empty card, just the folders group below.
    if (rows.isNotEmpty()) Group("Podcasts & audiobooks", *rows.toTypedArray())
    PhoneAudiobookFolders()
}

/**
 * The Audiobookshelf login for podcasts and audiobooks (in General → Account): who's signed in, a
 * refresh, a connection test and sign out; or the sign-in form.
 */
@Composable
internal fun PodcastsAndAudiobooksAccount() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val account by c.spoken.account.collectAsStateWithLifecycle()
    val syncError by c.spoken.lastError.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    val a = account
    val syncing by c.spoken.syncing.collectAsStateWithLifecycle()
    if (a != null && !a.needsSignIn) {
        Group(
            "Podcasts & audiobooks",
            { Info("Signed in as ${a.username}", a.serverUrl.removePrefix("https://").removePrefix("http://")) },
            {
                Row(Modifier.padding(horizontal = HearthTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !syncing, onClick = { scope.launch { c.spoken.refresh(force = true) } }) {
                        Text(if (syncing) "Refreshing…" else "Refresh now")
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true; result = null
                            scope.launch {
                                val error = c.spoken.testConnection()
                                result = if (error == null) "Connected" else "Failed: $error"
                                busy = false
                            }
                        },
                    ) { Text(if (busy) "Testing…" else "Test connection") }
                    TextButton(onClick = { confirmSignOut = true }) { Text("Sign out", color = HearthTheme.colors.destructive) }
                }
            },
        )
    } else {
        Group(
            "Podcasts & audiobooks",
            {
                SpokenSignInForm(Modifier.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.l)) {
                    actions.showMessage("Signed in. Your podcasts and audiobooks are on their way")
                }
            },
        )
        Text(
            if (a?.needsSignIn == true) "Your sign-in for podcasts & audiobooks expired. Sign in again; nothing on this phone is lost."
            // The one place the server software is named, so you know which login it wants.
            else "Sign in with your Audiobookshelf account to listen to podcasts and audiobooks. Each of you has your own, so progress and followed shows are yours.",
            color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = HearthTheme.spacing.gutter, end = HearthTheme.spacing.gutter, top = HearthTheme.spacing.s),
        )
    }
    (result ?: syncError?.takeIf { a != null && !a.needsSignIn }?.let { "Failed to refresh: $it" })?.let {
        Text(
            it, color = if (it.startsWith("Failed")) HearthTheme.colors.destructive else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = HearthTheme.spacing.gutter, end = HearthTheme.spacing.gutter, top = HearthTheme.spacing.s),
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of podcasts & audiobooks?") },
            text = { Text("Downloaded episodes and books stay on this phone. Your progress is kept on the server.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    scope.launch { c.spoken.signOut(); result = null }
                }) { Text("Sign out", color = HearthTheme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Server, username and password for podcasts & audiobooks, and a filled Sign in button. Shared by
 * Settings and the optional step after the first sign-in. The password is only sent to sign in, never
 * stored. [secondary] sits beside the button (e.g. "Skip"); [onSignedIn] runs once it worked.
 */
@Composable
internal fun SpokenSignInForm(
    modifier: Modifier = Modifier,
    secondary: (@Composable () -> Unit)? = null,
    onSignedIn: () -> Unit,
) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(c.spoken.store.lastServerUrl) }
    var username by remember { mutableStateOf(c.spoken.store.lastUsername) }
    // Plain remember: a saveable password would sit unencrypted in the saved-state Bundle.
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HearthTheme.spacing.s)) {
        OutlinedTextField(
            value = url, onValueChange = { url = it; error = null },
            label = { Text("Server address") }, singleLine = true,
            placeholder = { Text(AbsAccountStore.DEFAULT_SERVER_URL) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username, onValueChange = { username = it; error = null },
            label = { Text("Username") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it; error = null },
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text("Couldn't sign in: $it", color = HearthTheme.colors.destructive, style = MaterialTheme.typography.bodyMedium) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.s)) {
            Button(
                enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        val failure = c.spoken.signIn(url, username, password)
                        busy = false
                        if (failure == null) { password = ""; onSignedIn() } else error = failure
                    }
                },
            ) { Text(if (busy) "Signing in…" else "Sign in") }
            secondary?.invoke()
        }
    }
}
