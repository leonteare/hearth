// Small pieces for music that isn't on the server: its cover, the cloud or progress icon, and the heart.
package im.flume.hearth.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import im.flume.hearth.core.network.tidalImageUrl
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.data.RequestEntity
import im.flume.hearth.core.data.RequestStatus
import im.flume.hearth.core.ui.theme.HearthTheme

/** Album cover or artist picture of music that isn't on the server (public image CDN). Never dimmed. */
@Composable
fun RemoteCover(uuid: String?, title: String, size: Int = 320) {
    val url = tidalImageUrl(uuid, size)
    val placeholder = @Composable {
        Box(Modifier.fillMaxSize().background(HearthTheme.colors.surfaceHigh), contentAlignment = Alignment.Center) {
            Icon(HearthTheme.icons.song, null, tint = HearthTheme.colors.textSecondary)
        }
    }
    if (url == null) placeholder() else SubcomposeAsyncImage(
        model = url, contentDescription = title, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(), error = { placeholder() }, loading = { placeholder() },
    )
}

/**
 * The small icon before a not-on-server subtitle, the same size as the downloaded tick: a cloud;
 * a ring while it's on its way or previewing; a warning when getting it failed.
 */
@Composable
fun AvailabilityIcon(request: RequestEntity?, previewing: Boolean = false, loading: Boolean = false) {
    val accent = MaterialTheme.colorScheme.primary
    when {
        previewing && loading -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = accent)
        previewing -> {
            val progress by LocalContext.current.container.preview.progress.collectAsStateWithLifecycle()
            CircularProgressIndicator(
                progress = { progress }, modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp,
                color = accent, trackColor = HearthTheme.colors.textSecondary.copy(alpha = 0.3f),
            )
        }
        request?.status == RequestStatus.FAILED -> Icon(HearthTheme.icons.failedOutline, "Couldn't get it", Modifier.size(14.dp), tint = HearthTheme.colors.destructive)
        isComing(request) -> CircularProgressIndicator(
            Modifier.size(12.dp), strokeWidth = 1.5.dp, color = HearthTheme.colors.textSecondary, trackColor = HearthTheme.colors.textSecondary.copy(alpha = 0.3f),
        )
        else -> Icon(HearthTheme.icons.notOnServer, "Not on your server yet", Modifier.size(14.dp), tint = HearthTheme.colors.textSecondary)
    }
}

/** ♡ for music that isn't on the server: outline = get it; filled = on its way (tap to stop). */
@Composable
fun GetHeart(request: RequestEntity?, size: Dp = 22.dp, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val coming = willLike(request)
    IconButton(onClick = {
        haptics.performHapticFeedback(if (coming) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        onClick()
    }) {
        Icon(
            if (coming) HearthTheme.icons.likeOn else HearthTheme.icons.like,
            if (coming) "Don't add to Liked Songs" else "Like",
            tint = if (coming) MaterialTheme.colorScheme.primary else HearthTheme.colors.textSecondary,
            modifier = Modifier.size(size),
        )
    }
}
