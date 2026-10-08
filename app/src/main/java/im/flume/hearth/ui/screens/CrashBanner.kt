package im.flume.hearth.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import im.flume.hearth.crashFile
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.SurfaceHigh
import im.flume.hearth.ui.theme.TextSecondary

/** Shown on Home after a crash, so the details can be copied and sent on. */
@Composable
fun CrashBanner() {
    val context = LocalContext.current
    val file = remember { crashFile(context) }
    var report by remember { mutableStateOf(runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()) }
    val text = report ?: return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp)).background(SurfaceHigh).padding(14.dp)
    ) {
        Text("Hearth crashed last time", fontWeight = FontWeight.Bold)
        Text("Copy the details and send them on so it can be fixed.", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hearth crash", text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy details", color = Accent) }
            TextButton(onClick = { file.delete(); report = null }) { Text("Dismiss", color = TextSecondary) }
        }
    }
}
