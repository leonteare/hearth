package im.flume.hearth.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException

/**
 * Resolves `hearth://song/{id}` at the moment a song starts loading: a downloaded file if there is
 * one, otherwise the (cached) stream at the bitrate that fits the current connection. Resolving late
 * means queued songs pick up downloads and quality changes made after they were queued.
 * [streamTarget] must return the same bitrate for every open() of a song while it plays (retries
 * resume at a byte offset), which PlaybackService ensures by pinning it per song.
 *
 * When [cannotStream] (offline mode, or no network at all) nothing is streamed: a song that is fully
 * in the cache ([cachedTarget]) plays from there through [cacheOnlyFactory], anything else fails at
 * once with [OfflineException] so playback can move on to a song that is on the phone.
 */
@UnstableApi
class SongDataSource(
    private val fileFactory: DataSource.Factory,
    private val streamFactory: DataSource.Factory,
    private val cacheOnlyFactory: DataSource.Factory,
    private val localFile: (String) -> File?,
    private val streamTarget: (String) -> StreamTarget?,
    private val cachedTarget: (String) -> StreamTarget?,
    private val cannotStream: () -> Boolean,
) : DataSource {

    data class StreamTarget(val url: String, val cacheKey: String)

    /** The song isn't on the phone and streaming isn't possible right now. Reported as a connection failure. */
    class OfflineException(songId: String) :
        DataSourceException("Offline and $songId isn't downloaded", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

    class Factory(
        private val fileFactory: DataSource.Factory,
        private val streamFactory: DataSource.Factory,
        private val cacheOnlyFactory: DataSource.Factory,
        private val localFile: (String) -> File?,
        private val streamTarget: (String) -> StreamTarget?,
        private val cachedTarget: (String) -> StreamTarget?,
        private val cannotStream: () -> Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SongDataSource(fileFactory, streamFactory, cacheOnlyFactory, localFile, streamTarget, cachedTarget, cannotStream)
    }

    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val songId = songId(dataSpec.uri) ?: throw IOException("Not a song URI: ${dataSpec.uri}")
        val file = localFile(songId)
        val (source, spec) = if (file != null) {
            fileFactory.createDataSource() to dataSpec.buildUpon().setUri(Uri.fromFile(file)).build()
        } else if (cannotStream()) {
            val target = cachedTarget(songId) ?: throw OfflineException(songId)
            cacheOnlyFactory.createDataSource() to dataSpec.buildUpon().setUri(target.url).setKey(target.cacheKey).build()
        } else {
            val target = streamTarget(songId) ?: throw IOException("Not logged in")
            streamFactory.createDataSource() to dataSpec.buildUpon().setUri(target.url).setKey(target.cacheKey).build()
        }
        listeners.forEach(source::addTransferListener)
        delegate = source
        return source.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }

    companion object {
        const val SCHEME = "hearth"
        fun uriFor(songId: String): Uri = Uri.parse("$SCHEME://song/$songId")
        fun songId(uri: Uri): String? = if (uri.scheme == SCHEME) uri.lastPathSegment else null
    }
}
