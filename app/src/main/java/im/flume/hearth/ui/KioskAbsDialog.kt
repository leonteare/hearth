// The kiosk's "Audiobooks for <name>" dialog: the Audiobookshelf login kept for one profile, so switching
// profiles switches audiobooks (and their progress) too.
package im.flume.hearth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import im.flume.hearth.core.data.spoken.AbsAccountStore
import im.flume.hearth.core.network.Credentials
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlinx.coroutines.launch

@Composable
internal fun KioskAbsDialog(profile: Credentials, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val store = remember { Kiosk.profiles(context) }
    val existing = remember(profile) { store.absLogin(profile) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(existing?.serverUrl ?: AbsAccountStore.DEFAULT_SERVER_URL) }
    var user by remember { mutableStateOf(existing?.username ?: profile.username) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        busy = true
        error = null
        scope.launch {
            // Whoever was signed in to Audiobookshelf makes way; this profile's login takes over from now on.
            runCatching { c.spoken.signOut() }
            val problem = c.spoken.signIn(url.trim(), user.trim(), password)
            busy = false
            if (problem == null) {
                store.saveAbsLogin(profile, AbsLogin(url.trim(), user.trim(), password))
                onDismiss()
            } else {
                error = problem
            }
        }
    }

    fun remove() {
        store.clearAbsLogin(profile)
        c.appScope.launch { runCatching { c.spoken.signOut() } }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Audiobooks for ${Profiles.displayName(profile.username)}") },
        text = {
            Column {
                Text(
                    "Their Audiobookshelf login. It is kept on this phone so switching to them brings their books and progress.",
                    color = HearthTheme.colors.textSecondary,
                )
                Spacer(Modifier.height(HearthTheme.spacing.m))
                OutlinedTextField(url, { url = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(HearthTheme.spacing.s))
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(HearthTheme.spacing.s))
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(HearthTheme.spacing.s))
                    Text(it, color = HearthTheme.colors.destructive)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = !busy && user.isNotBlank() && password.isNotEmpty()) {
                Text(if (busy) "Signing in…" else "Save")
            }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { remove() }, enabled = !busy) { Text("Remove") }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
            }
        },
    )
}
