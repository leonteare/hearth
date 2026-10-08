package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.api.WrongPasswordException
import im.flume.hearth.container
import im.flume.hearth.data.PlaylistItem
import im.flume.hearth.data.PlaylistRules
import im.flume.hearth.data.ShareRole
import im.flume.hearth.data.Sharing
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.BannerCard
import im.flume.hearth.ui.components.ChoiceSheet
import im.flume.hearth.ui.components.IconTile
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private fun roleLabel(role: ShareRole) = when (role) {
    ShareRole.ADD -> "Can add songs"
    ShareRole.VIEW -> "Can listen"
}

/** Shown on a playlist someone invited you to: accept to keep it, decline to make it go away. */
@Composable
fun InviteBanner(item: PlaylistItem) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val invite = item.access.invite ?: return
    val owner = PlaylistRules.displayName(item.access.owner)
    val what = if (invite == ShareRole.ADD) "add songs to this playlist" else "listen to this playlist"
    BannerCard(
        title = null,
        body = "$owner invited you to $what",
        icon = Icons.Default.GroupAdd,
        tinted = true,
        actions = {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                c.appScope.launch {
                    c.library.answerInvite(item.playlist.id, accept = false)
                    actions.showMessage("Invite declined")
                }
            }) { Text("Decline", color = TextSecondary) }
            TextButton(onClick = {
                c.appScope.launch {
                    c.library.answerInvite(item.playlist.id, accept = true)
                    actions.showMessage("Added to Your Library")
                }
            }) { Text("Accept") }
        },
    )
}

/**
 * The owner's share sheet: everyone on the server (or a typed username, when the account can't list
 * users), each shared as "can add", "can listen" or not at all. New people get an invite to accept.
 */
@Composable
fun ShareSheet(item: PlaylistItem, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val me = c.session.credentials.collectAsStateWithLifecycle().value?.username?.lowercase()
    val sharing = item.access.sharing ?: Sharing()
    var users by remember { mutableStateOf<List<String>?>(null) }
    var listFailed by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { c.api.users() }
            .onSuccess { list -> users = list.map { it.username.lowercase() } }
            .onFailure { users = emptyList(); listFailed = true } // Only admins can list accounts.
    }
    val names = (users.orEmpty() + sharing.members.keys + sharing.invites.keys)
        .filter { it.isNotBlank() && it != me && it != item.access.owner }
        .distinct().sorted()

    fun status(u: String): String = sharing.members[u]?.let(::roleLabel)
        ?: sharing.invites[u]?.let { "Invited • ${roleLabel(it).lowercase()}" }
        ?: "Not shared"

    fun apply(u: String, role: ShareRole?) {
        val name = PlaylistRules.displayName(u)
        val wasShared = u in sharing.members || u in sharing.invites
        c.appScope.launch {
            runCatching { c.library.updateSharing(item.playlist.id) { it.withRole(u, role) } }
                .onSuccess {
                    actions.showMessage(
                        when {
                            role == null -> "Stopped sharing with $name"
                            wasShared -> "Updated $name"
                            else -> "Invited $name"
                        }
                    )
                }
                .onFailure { actions.showMessage("Couldn't update sharing (offline?)") }
        }
    }

    ActionSheet(
        open = true, onDismiss = onDismiss, title = "Share \"${item.playlist.name}\"",
        subtitle = "They'll see it in Hearth once they accept", coverArt = item.playlist.coverArt, fallback = item.playlist.name,
    ) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            if (users == null) {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
            names.forEach { u ->
                MediaRow(
                    PlaylistRules.displayName(u), status(u), onClick = { choosing = u }, round = true,
                    cover = { IconTile(Icons.Default.Person, Color(0xFF3E3E3E)) },
                )
            }
            if (users != null && names.isEmpty() && !listFailed) {
                Text(
                    "No one else has an account on this server yet.",
                    color = TextSecondary, modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 12.dp),
                )
            }
            if (listFailed) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        typed, { typed = it }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text("Their Navidrome username") },
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(enabled = typed.isNotBlank(), onClick = { choosing = typed.trim().lowercase(); typed = "" }) { Text("Share") }
                }
            }
        }
    }

    choosing?.let { u ->
        ChoiceSheet(
            open = true,
            title = PlaylistRules.displayName(u),
            options = listOf<Pair<ShareRole?, String>>(ShareRole.ADD to "Can add songs", ShareRole.VIEW to "Can listen only", null to "Not shared"),
            selected = sharing.members[u] ?: sharing.invites[u],
            onDismiss = { choosing = null },
            onPick = { role -> if (role != (sharing.members[u] ?: sharing.invites[u])) apply(u, role) },
        )
    }
}

/**
 * Asks for the Navidrome password once, checks it with the server, then keeps it encrypted on the
 * phone. Navidrome only accepts playlist photos through its own API, which needs a real login.
 */
@Composable
fun PasswordDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text("Your Navidrome password") },
        text = {
            Column {
                Text("Hearth needs your password once to change playlist photos. It's kept encrypted on this phone.")
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    password, { password = it; error = null }, singleLine = true, enabled = !checking,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    placeholder = { Text("Password") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() && !checking, onClick = {
                val creds = c.session.credentials.value ?: return@TextButton
                checking = true
                scope.launch {
                    val result = runCatching {
                        c.nativeApi.login(creds.serverUrl, creds.username, password)
                        c.session.vault.save(password)
                    }
                    checking = false
                    result.onSuccess { onSaved() }.onFailure { e ->
                        error = when (e) {
                            is WrongPasswordException -> "That password didn't work"
                            is java.io.IOException -> "Couldn't reach the server"
                            else -> "Couldn't save the password on this phone"
                        }
                    }
                }
            }) { Text(if (checking) "Checking…" else "Continue") }
        },
        dismissButton = { TextButton(enabled = !checking, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Error text for a failed photo change; a stale saved password is forgotten so the next try asks again. */
internal fun photoError(e: Throwable, clearPassword: () -> Unit): String = when (e) {
    is WrongPasswordException, is im.flume.hearth.api.PasswordNeededException -> {
        clearPassword()
        "Navidrome didn't accept your saved password. Try again to enter it."
    }
    is java.io.IOException -> e.message?.takeIf { it.startsWith("That photo") } ?: "Couldn't change the photo (offline?)"
    else -> "Couldn't use that photo"
}
