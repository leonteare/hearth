package im.flume.hearth.ui.components

import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import androidx.compose.material3.MaterialTheme
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Neutral dark start colour for cover gradients, so pages don't flash the accent before the real colour is known. */
val CoverColorFallback = Color(0xFF2A2A2A)

/**
 * Extracted colours per cover id, shared by the mini player, Now Playing and page headers so a
 * cover seen once starts at its real colour everywhere.
 */
private val coverColorCache = LruCache<String, Color>(200)

/** A rich but dark-enough colour taken from the cover art, for header gradients. Fades in once known. */
@Composable
fun rememberCoverColor(coverArt: String?, fallback: Color = CoverColorFallback): Color {
    val context = LocalContext.current
    val actions = LocalActions.current
    var found by remember(coverArt) { mutableStateOf(coverArt?.let { coverColorCache.get(it) }) }
    LaunchedEffect(coverArt) {
        if (coverArt == null || found != null) return@LaunchedEffect
        val url = actions.coverUrl(coverArt, 120) ?: return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(120).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@LaunchedEffect
        val bitmap = result.image.toBitmap()
        val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).generate() }
        val swatch = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch ?: return@LaunchedEffect
        // Pull well towards black so white and secondary text on top stay readable, even on bright covers.
        val colour = lerp(Color(swatch.rgb), Color.Black, 0.55f)
        coverColorCache.put(coverArt, colour)
        found = colour
    }
    val color by animateColorAsState(found ?: fallback, tween(500), label = "cover")
    return color
}

/** Download toggle for albums / playlists: outlined arrow, progress ring while downloading, solid when done. */
@Composable
fun DownloadToggle(downloaded: Boolean, progress: Float?, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            if (progress != null) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(26.dp).semantics {
                        contentDescription = "Downloading, ${(progress * 100).toInt()}%"
                    },
                    trackColor = TextSecondary.copy(alpha = 0.3f),
                    strokeWidth = 2.dp,
                )
            } else if (downloaded) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowDownward, "Remove download", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                }
            } else {
                Icon(Icons.Outlined.ArrowCircleDown, "Download", tint = TextSecondary, modifier = Modifier.size(28.dp))
            }
        }
    }
}
