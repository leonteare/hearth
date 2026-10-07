package im.flume.hearth.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException

/**
 * Resolves `hearth://song/{id}` at the moment a song starts loading: a downloaded file if there is
 * one, otherwise the (cached) stream at the bitrate that fits the current connection. Resolving late
 * means queued songs pick up downloads and quality changes made after they were queued.
 */
@UnstableApi
class SongDataSource(
    private val fileFactory: DataSource.Factory,
    private val streamFactory: DataSource.Factory,
    private val localFile: (String) -> File?,
    private val streamTarget: (String) -> StreamTarget?,
) : DataSource {

    data class StreamTarget(val url: String, val cacheKey: String)

    class Factory(
        private val fileFactory: DataSource.Factory,
        private val streamFactory: DataSource.Factory,
        private val localFile: (String) -> File?,
        private val streamTarget: (String) -> StreamTarget?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SongDataSource(fileFactory, streamFactory, localFile, streamTarget)
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
