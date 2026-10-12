// Adding to the Audiobookshelf server: the new-podcast request, the book upload form, and its folder-name rules.
package im.flume.hearth.core.network

import im.flume.hearth.core.common.AbsException
import java.io.InputStream
import java.text.Normalizer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody

/** The body of [AbsClient.createPodcast]'s request. Throws when neither the feed nor iTunes gives a title. */
internal fun newPodcastBody(
    libraryId: String,
    folderId: String,
    folderPath: String,
    feed: AbsFeedMetadata,
    feedUrl: String,
    itunes: AbsPodcastSearchResult?,
    tags: List<String>,
    autoDownload: Boolean,
): JsonObject {
    val title = feed.title ?: itunes?.title ?: throw AbsException("The feed has no title")
    return buildJsonObject {
        put("libraryId", libraryId)
        put("folderId", folderId)
        put("path", "${folderPath.trimEnd('/')}/${PodcastPaths.sanitizeFilename(title)}")
        putJsonObject("media") {
            putJsonObject("metadata") {
                put("title", title)
                (feed.author ?: itunes?.artistName)?.let { put("author", it) }
                feed.description?.let { put("description", it) }
                put("feedUrl", feedUrl)
                (feed.image ?: itunes?.cover)?.let { put("imageUrl", it) }
                itunes?.pageUrl?.let { put("itunesPageUrl", it) }
                itunes?.id?.let { put("itunesId", it.toString()) }
                feed.language?.let { put("language", it) }
                put("explicit", feed.explicit == "yes" || feed.explicit == "true" || itunes?.explicit == true)
                feed.type?.let { put("type", it) }
                putJsonArray("genres") { (itunes?.genres ?: feed.categories).filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it)) } }
            }
            putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
            put("autoDownloadEpisodes", autoDownload)
        }
    }
}

/**
 * The form [AbsClient.uploadBook] posts. Files are read only as the form is sent, and [onProgress]
 * hears the bytes sent so far across all of them.
 */
internal fun bookUploadForm(
    libraryId: String,
    folderId: String,
    title: String,
    author: String?,
    files: List<AbsUploadFile>,
    onProgress: (Long) -> Unit,
): MultipartBody {
    val sent = LongArray(files.size)
    val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("title", title)
        .apply { author?.takeIf { it.isNotBlank() }?.let { addFormDataPart("author", it) } }
        .addFormDataPart("library", libraryId)
        .addFormDataPart("folder", folderId)
    files.forEachIndexed { i, f ->
        form.addFormDataPart(i.toString(), f.name, object : RequestBody() {
            override fun contentType() = (f.mimeType ?: "application/octet-stream").toMediaType()
            override fun contentLength() = f.size.takeIf { it > 0 } ?: -1L
            override fun writeTo(sink: okio.BufferedSink) {
                sent[i] = 0
                f.open().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent[i] += n
                        onProgress(sent.sum())
                    }
                }
            }
        })
    }
    return form.build()
}

/** One file to upload: its name on the server, size in bytes (0 if unknown) and how to read it. */
class AbsUploadFile(val name: String, val size: Long, val mimeType: String?, val open: () -> InputStream)

/** Mirrors Audiobookshelf's sanitizeFilename, so a podcast lands in the folder its own app would pick. */
object PodcastPaths {
    private val illegal = Regex("""[/?<>\\:*|"]""")
    private val control = Regex("[\\x00-\\x1f\\x80-\\x9f]")
    private val reserved = Regex("^\\.+$")
    private val windowsReserved = Regex("^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?$", RegexOption.IGNORE_CASE)
    private val windowsTrailing = Regex("[. ]+$")

    fun sanitizeFilename(name: String): String {
        val s = Normalizer.normalize(name, Normalizer.Form.NFC)
            .replaceFirst(":", " - ")
            .replace(illegal, "")
            .replace(control, "")
            .replace(reserved, "")
            .replace(Regex("[\\n\\r]"), "")
            .replace(windowsReserved, "")
            .replace(windowsTrailing, "")
            .replace(Regex("\\s+"), " ")
        // 255 bytes of UTF-16 = 127 chars; trim like the server does for very long titles.
        return if (s.length > 127) s.take(127).trimEnd() else s
    }
}
