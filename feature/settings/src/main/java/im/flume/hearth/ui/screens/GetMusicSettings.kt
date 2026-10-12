// Settings for getting music you don't have: the Tidarr address and key, a connection test, and showing it in Search.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import im.flume.hearth.container
import im.flume.hearth.data.Settings
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.theme.HearthTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Getting music you don't have: where Tidarr is (it fetches music onto the server), its optional API
 * key, a connection test, and whether Search and artist pages show music that isn't on the server.
 * The one place the service is named.
 */
@Composable
internal fun GetMusicSettings() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val settings by c.session.settings.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(settings.tidarrUrl) }
    var key by remember { mutableStateOf("") }
    var keyLoaded by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        key = withContext(Dispatchers.IO) { c.requests.apiKey().orEmpty() }
        keyLoaded = true
    }
    val dirty = keyLoaded && (url.trim() != settings.tidarrUrl || key.trim() != c.requests.apiKey().orEmpty())

    fun save() {
        val u = url.trim()
        c.session.updateSettings { it.copy(tidarrUrl = u) }
        scope.launch { withContext(Dispatchers.IO) { c.requests.setApiKey(key) } }
    }

    Section("Get music you don't have")
    Text(
        "Search and artist pages can show music that isn't on your server yet. Tap a song to hear a preview; tap ♡ and Tidarr fetches it onto your server, then it's added to your library and downloaded to this phone.",
        color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter),
    )
    OutlinedTextField(
        value = url, onValueChange = { url = it; result = null },
        label = { Text("Tidarr address") }, singleLine = true,
        placeholder = { Text(Settings.DEFAULT_TIDARR_URL) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.xs),
    )
    OutlinedTextField(
        value = key, onValueChange = { key = it; result = null },
        label = { Text("API key (optional)") }, singleLine = true, enabled = keyLoaded,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.xs),
    )
    Row(Modifier.padding(horizontal = HearthTheme.spacing.s), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { save(); actions.showMessage("Tidarr settings saved") }, enabled = dirty) { Text("Save") }
        TextButton(
            enabled = !testing && url.isNotBlank(),
            onClick = {
                testing = true; result = null
                scope.launch {
                    val error = c.requests.testConnection(url, key)
                    result = if (error == null) "Connected to Tidarr" else "Failed: $error"
                    testing = false
                    if (error == null && dirty) save()
                }
            },
        ) { Text(if (testing) "Testing…" else "Test connection") }
    }
    result?.let {
        Text(
            it, color = if (it.startsWith("Failed")) HearthTheme.colors.destructive else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter),
        )
    }
    Toggle("Show music you don't have in Search and artist pages", settings.tidalInSearch, "Shown dimmed, after your own music") { v ->
        c.session.updateSettings { it.copy(tidalInSearch = v) }
    }
}
