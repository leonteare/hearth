package im.flume.hearth.ui.components

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
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A rich but dark-enough colour taken from the cover art, for header gradients. Fades in once known. */
@Composable
fun rememberCoverColor(coverArt: String?, fallback: Color = Color(0xFF5A3A26)): Color {
    val context = LocalContext.current
    val actions = LocalActions.current
    var target by remember(coverArt) { mutableStateOf(fallback) }
    LaunchedEffect(coverArt) {
        val url = actions.coverUrl(coverArt, 120) ?: return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(120).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@LaunchedEffect
        val bitmap = result.image.toBitmap()
        val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).generate() }
        val swatch = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch ?: return@LaunchedEffect
        // Pull towards black so white text on top stays readable.
        target = lerp(Color(swatch.rgb), Color.Black, 0.35f)
    }
    val color by animateColorAsState(target, tween(500), label = "cover")
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
                    modifier = Modifier.size(26.dp),
                    color = Accent,
                    trackColor = TextSecondary.copy(alpha = 0.3f),
                    strokeWidth = 2.dp,
                )
            } else if (downloaded) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowDownward, "Remove download", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            } else {
                Icon(Icons.Outlined.ArrowCircleDown, "Download", tint = TextSecondary, modifier = Modifier.size(28.dp))
            }
        }
    }
}
