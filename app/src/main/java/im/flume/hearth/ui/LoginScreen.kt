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
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("http://") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(Icons.Default.LocalFireDepartment, null, tint = Accent, modifier = Modifier.size(64.dp))
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
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    val creds = Credentials.fromPassword(url, user, pass)
                    val result = runCatching { c.api.ping(creds) }
                    busy = false
                    result.onSuccess {
                        c.session.saveCredentials(creds)
                        c.network.reportServerSuccess()
                        c.appScope.launch { c.sync.fullSync(c.api.scanStatus()?.lastScan) }
                    }.onFailure { e ->
                        error = "Couldn't sign in: ${e.message ?: e.javaClass.simpleName}.\n" +
                            "Check the address and that Tailscale is connected."
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
