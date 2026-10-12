// Cover artwork for rows, cards and the player, with a coloured initials tile when there's none.
package im.flume.hearth.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import coil3.compose.SubcomposeAsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import im.flume.hearth.data.SearchIndex
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.LocalReducedMotion
import im.flume.hearth.ui.theme.HearthTheme

@Composable
fun CoverArt(
    coverArt: String?,
    size: Dp?,
    modifier: Modifier = Modifier,
    corner: Dp = Dimens.CoverCorner,
    requestSize: Int = 300,
    /** Album / playlist / artist name, shown as initials on a coloured tile when there's no artwork. */
    fallback: String? = null,
    /** Artwork from elsewhere (a podcast or audiobook cover), used instead of [coverArt]. */
    url: String? = null,
) {
    val actions = LocalActions.current
    val m = (if (size != null) modifier.size(size) else modifier).clip(RoundedCornerShape(corner)).background(HearthTheme.colors.surfaceHigh)
    val url = url ?: actions.coverUrl(coverArt, requestSize)
    if (url == null) {
        Box(m) { Placeholder(fallback) }
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = m,
        error = { Placeholder(fallback) },
        loading = { Placeholder(null) },
    )
}

/**
 * The cover in the mini player and Now Playing. On a skip the previous cover stays up until the new
 * one is ready and then fades across in 150 ms, rather than flashing an empty tile. List rows keep
 * [CoverArt], where a row's cover never changes in place. [onPixelSize] reports the drawn size, so
 * [CoverPrefetcher] can load upcoming covers at exactly this size.
 */
@Composable
fun PlayerCoverArt(
    coverArt: String?,
    size: Dp?,
    modifier: Modifier = Modifier,
    corner: Dp = Dimens.CoverCorner,
    requestSize: Int = 300,
    fallback: String? = null,
    url: String? = null,
    onPixelSize: (IntSize) -> Unit = {},
) {
    val actions = LocalActions.current
    val context = LocalContext.current
    val reducedMotion = LocalReducedMotion.current
    // The memory-cache key of the cover on screen now, used as the next one's placeholder.
    var showing by remember { mutableStateOf<MemoryCache.Key?>(null) }
    val m = (if (size != null) modifier.size(size) else modifier)
        .onSizeChanged(onPixelSize)
        .clip(RoundedCornerShape(corner))
        .background(HearthTheme.colors.surfaceHigh)
    val data = url ?: actions.coverUrl(coverArt, requestSize)
    if (data == null) {
        Box(m) { Placeholder(fallback) }
        return
    }
    // Built once per cover, with whatever was showing at that moment as its placeholder.
    val request = remember(data) {
        ImageRequest.Builder(context).data(data).placeholderMemoryCacheKey(showing).apply { if (!reducedMotion) crossfade(150) }.build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = m,
        error = { Placeholder(fallback) },
        // With nothing shown before (the player just opened), the usual blank tile while it loads.
        loading = if (request.placeholderMemoryCacheKey != null) null else { _ -> Placeholder(null) },
        onSuccess = { showing = it.result.memoryCacheKey },
    )
}

@Composable
private fun Placeholder(text: String?) {
    val initials = text?.let { t ->
        SearchIndex.norm(t).split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
    }.orEmpty()
    if (initials.isEmpty()) {
        Box(Modifier.fillMaxSize().background(HearthTheme.colors.surfaceHigh), contentAlignment = Alignment.Center) {
            Icon(HearthTheme.icons.song, null, tint = HearthTheme.colors.textSecondary)
        }
        return
    }
    val tiles = HearthTheme.colors.coverPlaceholders
    val colour = tiles[(text.hashCode() and 0x7fffffff) % tiles.size]
    BoxWithConstraints(Modifier.fillMaxSize().background(colour), contentAlignment = Alignment.Center) {
        val fontSize = with(LocalDensity.current) { (maxWidth * 0.36f).toSp() }
        Text(initials, color = HearthTheme.colors.onCover.copy(alpha = 0.9f), fontWeight = FontWeight.Bold, fontSize = fontSize)
    }
}
