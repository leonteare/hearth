// "Who's listening?": the kiosk phone's profile picker. Each card is a Navidrome login; tap one to switch, tap
// the one listening now for their audiobooks login, or add another. Also the kiosk's sign-in page, which
// offers a way back to whoever was listening before.
package im.flume.hearth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.network.Credentials
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.ui.components.PageHeader
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.feature.settings.LoginScreen

@Composable
internal fun ProfilesScreen() {
    val context = LocalContext.current
    val c = context.container
    val store = remember { Kiosk.profiles(context) }
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val absUsers by store.absUsers.collectAsStateWithLifecycle()
    val creds by c.session.credentials.collectAsStateWithLifecycle()
    var absFor by remember { mutableStateOf<Credentials?>(null) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Who's listening?")
        LazyVerticalGrid(
            GridCells.Fixed(2),
            contentPadding = PaddingValues(HearthTheme.spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(HearthTheme.spacing.m),
        ) {
            items(profiles, key = { Profiles.key(it) }) { p ->
                val current = creds?.let { Profiles.same(it, p) } == true
                val absUser = absUsers[Profiles.key(p)]
                ProfileCard(
                    name = Profiles.displayName(p.username),
                    current = current,
                    audiobooks = when {
                        absUser != null -> "Audiobooks: $absUser"
                        current -> "Tap to add audiobooks"
                        else -> "No audiobooks yet"
                    },
                ) { if (current) absFor = p else Kiosk.switchTo(context, p) }
            }
            // Signing out shows the sign-in page (KioskLogin); the new login is recorded as it completes.
            item { AddCard { c.session.logout() } }
        }
    }
    absFor?.let { KioskAbsDialog(it, onDismiss = { absFor = null }) }
}

@Composable
private fun ProfileCard(name: String, current: Boolean, audiobooks: String, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    SquareCard(if (current) accent.copy(alpha = 0.18f) else HearthTheme.colors.surface, onClick) {
        Box(
            Modifier
                .size(HearthTheme.spacing.avatar * 1.5f)
                .clip(HearthTheme.shapes.round)
                .background(if (current) accent else HearthTheme.colors.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = if (current) HearthTheme.colors.onAccent else HearthTheme.colors.text,
            )
        }
        Spacer(Modifier.height(HearthTheme.spacing.m))
        Text(name, style = MaterialTheme.typography.titleMedium, color = HearthTheme.colors.text, textAlign = TextAlign.Center, maxLines = 1)
        Text(
            if (current) "Listening now" else "Tap to switch",
            style = MaterialTheme.typography.bodySmall,
            color = HearthTheme.colors.textSecondary,
        )
        Text(audiobooks, style = MaterialTheme.typography.bodySmall, color = HearthTheme.colors.textDim, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun AddCard(onClick: () -> Unit) {
    SquareCard(HearthTheme.colors.surface, onClick) {
        Icon(HearthTheme.icons.add, null, tint = HearthTheme.colors.textSecondary, modifier = Modifier.size(HearthTheme.spacing.avatar))
        Spacer(Modifier.height(HearthTheme.spacing.m))
        Text("Add someone", style = MaterialTheme.typography.titleMedium, color = HearthTheme.colors.text)
        Text(
            "Needs their Navidrome password",
            style = MaterialTheme.typography.bodySmall,
            color = HearthTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SquareCard(color: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(HearthTheme.shapes.card)
            .background(color)
            .clickable(onClick = onClick)
            .padding(HearthTheme.spacing.cardPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/** The sign-in page on a kiosk phone: the usual form, plus a way back to whoever was listening before. */
@Composable
internal fun KioskLogin() {
    val context = LocalContext.current
    val profiles by remember { Kiosk.profiles(context) }.profiles.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        LoginScreen()
        profiles.firstOrNull()?.let { back ->
            TextButton(
                onClick = { Kiosk.switchTo(context, back) },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(HearthTheme.spacing.s),
            ) { Text("Back to ${Profiles.displayName(back.username)}") }
        }
    }
}
