package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.update.UpdateState
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.SurfaceHigh
import im.flume.hearth.ui.theme.TextSecondary

/** Shown on Home only while there's something to act on. */
@Composable
fun UpdateBanner() {
    val updater = LocalContext.current.container.updater
    val state by updater.state.collectAsStateWithLifecycle()
    val (release, line) = when (val s = state) {
        is UpdateState.Available -> s.release to "Version ${s.release.version} is ready to install"
        is UpdateState.Downloading -> s.release to "Downloading version ${s.release.version}…"
        is UpdateState.Installing -> s.release to "Installing version ${s.release.version}…"
        is UpdateState.NeedsPermission -> s.release to "Allow Hearth to install updates, then come back"
        is UpdateState.Failed -> (s.release ?: return) to s.message
        else -> return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdate, null, tint = Accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Update available", fontWeight = FontWeight.Bold)
                Text(line, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (state is UpdateState.Available || state is UpdateState.Failed || state is UpdateState.NeedsPermission) {
                Button(
                    onClick = { updater.install(release) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                ) { Text("Install") }
            }
        }
        (state as? UpdateState.Downloading)?.let { d ->
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth(), color = Accent)
        }
        if (release.notes.isNotBlank() && state is UpdateState.Available) {
            Spacer(Modifier.height(8.dp))
            Text(release.notes, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Settings row: current version plus a manual check. */
@Composable
fun UpdateSettingsRow() {
    val updater = LocalContext.current.container.updater
    val state by updater.state.collectAsStateWithLifecycle()
    val status = when (val s = state) {
        UpdateState.Checking -> "Checking…"
        UpdateState.UpToDate -> "You're on the latest version"
        is UpdateState.Available -> "Version ${s.release.version} available. Tap to install"
        is UpdateState.Downloading -> "Downloading… ${(s.progress * 100).toInt()}%"
        is UpdateState.Installing -> "Installing…"
        is UpdateState.NeedsPermission -> "Allow installs from Hearth, then tap again"
        is UpdateState.Failed -> s.message
        UpdateState.Idle -> "Tap to check for updates"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                when (val s = state) {
                    is UpdateState.Available -> updater.install(s.release)
                    is UpdateState.NeedsPermission -> updater.install(s.release)
                    else -> updater.checkIfDue(force = true)
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("Version ${updater.currentVersion}")
        Text(status, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}
