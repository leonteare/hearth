// The slim bar above the mini player while a 30-second preview plays.
package im.flume.hearth.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.SurfaceHigh
import im.flume.hearth.ui.theme.TextSecondary

/** Slim bar above the mini player while a preview plays: what it is, ♡ to get it, and stop. */
@Composable
fun PreviewBar() {
    val c = LocalContext.current.container
    val get = LocalGetMusic.current
    val current = LocalPreview.current
    // Keep the last preview while the bar animates away.
    var shown by remember { mutableStateOf(current) }
    if (current != null) shown = current
    AnimatedVisibility(current != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val p = shown ?: return@AnimatedVisibility
        val t = p.track
        val request = LocalRequests.current[trackKey(t.id)]
        val progress by c.preview.progress.collectAsStateWithLifecycle()
        Column(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp).clip(HearthShapes.Card).background(SurfaceHigh)
        ) {
            Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(HearthShapes.Cover)) { RemoteCover(t.cover, t.displayTitle, 160) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOf(if (p.loading) "Loading preview…" else "Preview", t.artist).filter { it.isNotBlank() }.joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary, style = MaterialTheme.typography.labelMedium,
                    )
                }
                GetHeart(request) { get.heartTrack(t, request) }
                IconButton(onClick = { c.preview.stop() }) { Icon(Icons.Default.Stop, "Stop preview") }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.15f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}
