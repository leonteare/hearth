package im.flume.hearth.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import im.flume.hearth.api.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Artwork for the media session without the login in it. The real cover URL carries the Subsonic
 * token and salt, and a session's metadata reaches system UI, Bluetooth and any other app with a
 * MediaController, so the session only ever publishes `hearth-cover://art/{coverArtId}` and
 * [CoverBitmapLoader] turns that into a bitmap inside the app.
 */
object CoverArt {
    const val SCHEME = "hearth-cover"

    fun uriFor(coverArtId: String?): Uri? =
        if (coverArtId.isNullOrEmpty()) null
        else Uri.Builder().scheme(SCHEME).authority("art").appendPath(coverArtId).build()

    fun idFrom(uri: Uri): String? = if (uri.scheme == SCHEME) uri.lastPathSegment?.takeIf { it.isNotEmpty() } else null
}

/**
 * Coil cache keys for cover requests without the token: "cover:{server}:{id}:{size}". Null for
 * anything that isn't a Subsonic getCoverArt URL. Pure string work so it can be unit tested.
 */
fun coverCacheKey(url: String): String? {
    if (!url.contains("/rest/getCoverArt")) return null
    val base = url.substringBefore("/rest/getCoverArt").substringAfter("://")
    val params = url.substringAfter('?', "").split('&').mapNotNull {
        val k = it.substringBefore('=', "")
        if (k.isEmpty()) null else k to it.substringAfter('=')
    }.toMap()
    val id = params["id"]?.takeIf { it.isNotEmpty() } ?: return null
    return "cover:$base:$id:${params["size"].orEmpty()}"
}

/** Gives cover requests token-free memory and disk cache keys (see [coverCacheKey]). */
object CoverCacheKeys : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.memoryCacheKey != null || request.diskCacheKey != null) return chain.proceed()
        val key = (request.data as? String ?: (request.data as? Uri)?.toString() ?: (request.data as? coil3.Uri)?.toString())
            ?.let(::coverCacheKey) ?: return chain.proceed()
        val keyed = request.newBuilder()
            .memoryCacheKey(key)
            .memoryCacheKeyExtra("size", chain.size.toString())
            .diskCacheKey(key)
            .build()
        return chain.withRequest(keyed).proceed()
    }
}

/**
 * Loads `hearth-cover://` artwork for the notification, lock screen and Bluetooth through Coil, so it
 * uses the authenticated client and the cover cache (and works offline for covers seen before).
 */
@UnstableApi
class CoverBitmapLoader(
    private val context: Context,
    private val api: SubsonicClient,
    private val scope: CoroutineScope,
) : BitmapLoader {
    private val decoder = DataSourceBitmapLoader(context)

    override fun supportsMimeType(mimeType: String): Boolean = decoder.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = decoder.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        // Only our own artwork: never fetch arbitrary URIs a controller might hand us.
        val id = CoverArt.idFrom(uri) ?: return Futures.immediateFailedFuture(IOException("Unsupported artwork URI"))
        // Large enough to stay sharp on the lock screen and car displays.
        val url = api.coverArtUrl(id, 1000) ?: return Futures.immediateFailedFuture(IOException("Not logged in"))
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            val result = runCatching {
                SingletonImageLoader.get(context).execute(
                    ImageRequest.Builder(context).data(url).size(1000).allowHardware(false).build()
                )
            }.getOrNull()
            val bitmap = (result as? SuccessResult)?.image?.toBitmap()
            if (bitmap != null) future.set(bitmap) else future.setException(IOException("Cover not available"))
        }
        return future
    }
}
