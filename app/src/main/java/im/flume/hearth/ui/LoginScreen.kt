package im.flume.hearth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import im.flume.hearth.api.Credentials
import im.flume.hearth.container
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("http://") }
    var user by rememberSaveable { mutableStateOf("") }
    // Plain remember: a saveable password would sit unencrypted in the saved-state Bundle.
    var pass by remember { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var errorHelp by rememberSaveable { mutableStateOf<String?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(Icons.Default.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Text("Hearth", style = MaterialTheme.typography.headlineLarge)
        Text("Sign in to your Navidrome server", color = TextSecondary)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it }, label = { Text("Server address") },
            placeholder = { Text("http://100.x.y.z:4533") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it }, label = { Text("Username") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it }, label = { Text("Password") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        // The technical hints (Tailscale, certificates) are there for whoever set up the server, not up front.
        errorHelp?.let { help ->
            androidx.compose.material3.TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "Less info" else "More info") }
            if (showHelp) Text(help, color = im.flume.hearth.ui.theme.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = {
                busy = true
                error = null
                errorHelp = null
                scope.launch {
                    val creds = Credentials.fromPassword(url, user, pass)
                    val result = runCatching { c.api.ping(creds) }
                    busy = false
                    result.onSuccess {
                        c.session.saveCredentials(creds)
                        // Kept encrypted (Keystore) so playlist photos don't ask for it again.
                        runCatching { c.session.vault.save(pass) }
                        c.network.reportServerSuccess()
                        c.appScope.launch { c.sync.fullSync(c.api.scanStatus()?.lastScan) }
                    }.onFailure { e ->
                        showHelp = false
                        if (generateSequence(e) { it.cause }.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }) {
                            error = "Couldn't sign in: this phone doesn't trust the server's security certificate."
                            errorHelp = "If it's your own certificate (e.g. Caddy's local one), install its root certificate on the phone, " +
                                "or use a plain http:// address over Tailscale."
                        } else {
                            error = "Couldn't sign in. ${im.flume.hearth.util.userMessage(e)}"
                            errorHelp = "Check the server address, and that Tailscale is connected if your server uses it."
                        }
                    }
                }
            },
            enabled = !busy && url.length > 8 && user.isNotBlank() && pass.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Sign in")
        }
    }
}
