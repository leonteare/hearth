// Scanning a phone folder for audiobooks: listing its files, reading their length and tags, and saving covers.
package im.flume.hearth.domain.spoken

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.MetadataRetriever
import im.flume.hearth.core.common.logHandledError
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import im.flume.hearth.core.data.spoken.LocalBookDao
import im.flume.hearth.core.data.spoken.LocalBookEntity
import im.flume.hearth.core.data.spoken.LocalBookFileEntity

/** Reads a picked folder: finds the books in it, measures and tags their files, and saves their covers. */
internal class LocalBookScanner(
    private val context: Context,
    private val dao: LocalBookDao,
    private val coversDir: File,
) {
    /** What a folder scan saw, so Settings can say why nothing turned up. */
    class ScanReport(val audioFiles: Int, val unreadable: List<String>)

    suspend fun scanFolder(treeUri: String, claimed: MutableSet<String>): ScanReport = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val entries = listTree(tree)
        val unreadable = ArrayList<String>()
        val groups = LocalBookScan.group(entries).filter { g ->
            val keys = g.files.map { fileKey(it.uri) ?: it.uri }
            if (keys.any { it in claimed }) false else { claimed += keys; true }
        }
        val oldBooks = dao.booksIn(treeUri).associateBy { it.id }
        val oldFiles = dao.filesIn(treeUri).groupBy { it.bookId }
        val now = System.currentTimeMillis()
        val books = ArrayList<LocalBookEntity>()
        val files = ArrayList<LocalBookFileEntity>()
        for (g in groups) {
            val id = LocalBookIds.idFor(treeUri, g.key)
            val old = oldBooks[id]
            val known = oldFiles[id].orEmpty().associateBy { it.uri }
            var tags: LocalTags? = null
            val rows = g.files.mapNotNull { f ->
                val same = known[f.uri]?.takeIf { it.sizeBytes == f.size && it.lastModified == f.lastModified && it.durationSec > 0 }
                val read = if (same == null) readFile(Uri.parse(f.uri)) else null
                if (f === g.files.first()) tags = read?.tags
                val duration = same?.durationSec ?: read?.durationSec ?: 0.0
                if (duration <= 0) { unreadable += f.name; return@mapNotNull null } // not playable audio
                LocalBookFileEntity(id, 0, f.uri, f.relPath, f.size, f.lastModified, duration, same?.mimeType ?: read?.mimeType, same?.title ?: read?.tags?.title)
            }.mapIndexed { i, r -> r.copy(fileIndex = i) }
            if (rows.isEmpty()) continue
            val firstChanged = old == null || oldFiles[id]?.firstOrNull()?.let { it.uri != rows.first().uri || it.lastModified != rows.first().lastModified } != false
            // What the files call the book. Details found online are kept over this when the folder is saved (LocalBookMerge).
            val (title, author) = if (!firstChanged && old != null) (old.fileTitle ?: old.title) to (if (old.fileTitle != null) old.fileAuthor else old.author) else {
                LocalBookNaming.describe(g, tags ?: readFile(Uri.parse(rows.first().uri))?.tags)
            }
            val cover = saveCover(id, g.cover, Uri.parse(rows.first().uri), refresh = firstChanged || (g.cover != null && g.cover.lastModified > (old?.scannedAt ?: 0)))
                ?: old?.coverPath?.takeIf { File(it).isFile }
            books += LocalBookEntity(
                id = id, treeUri = treeUri, relPath = g.key, title = title, author = author,
                durationSec = rows.sumOf { it.durationSec }, sizeBytes = rows.sumOf { it.sizeBytes }, numFiles = rows.size,
                coverPath = cover, addedAt = old?.addedAt ?: now, scannedAt = now,
                hidden = old?.hidden ?: false, serverItemId = old?.serverItemId,
                uploadState = old?.uploadState, uploadError = old?.uploadError,
                fileTitle = title, fileAuthor = author,
            )
            files += rows
        }
        // Covers of books that are gone.
        (oldBooks.keys - books.mapTo(HashSet()) { it.id }).forEach { gone -> oldBooks[gone]?.coverPath?.let { File(it).delete() } }
        dao.replaceFolder(treeUri, books, files)
        ScanReport(entries.count { LocalBookScan.isAudio(it.name) }, unreadable)
    }

    private fun fileKey(uri: String): String? = runCatching {
        Uri.parse(uri).let { LocalFolders.key(it.authority, DocumentsContract.getDocumentId(it)) }
    }.getOrNull()

    /** Every audio and image file in the folder and its sub-folders (a few levels deep). */
    private fun listTree(tree: Uri): List<LocalFileEntry> {
        val out = ArrayList<LocalFileEntry>()
        val queue = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to ""))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (queue.isNotEmpty()) {
            val (docId, prefix) = queue.removeFirst()
            val depth = if (prefix.isEmpty()) 0 else prefix.count { it == '/' } + 1
            context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH) queue.addLast(id to rel)
                    } else if (LocalBookScan.isAudio(name) || LocalBookScan.isImage(name)) {
                        out += LocalFileEntry(rel, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
                    }
                }
            }
        }
        return out
    }

    private class FileInfo(val durationSec: Double, val mimeType: String?, val tags: LocalTags)

    /**
     * Length and tags. Android's own reader comes first (it also gives tags); when it can't read the
     * file or reports no length (some large or unusual .m4b files), the player's reader measures it,
     * since that's what will play it.
     */
    private fun readFile(uri: Uri): FileInfo? {
        val android = readWithAndroid(uri)
        if (android != null && android.durationSec > 0) return android
        val seconds = durationWithPlayer(uri) ?: return android
        return FileInfo(seconds, android?.mimeType, android?.tags ?: LocalTags())
    }

    private fun durationWithPlayer(uri: Uri): Double? = try {
        MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { r ->
            r.retrieveDurationUs().get(60, TimeUnit.SECONDS)
                .takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1_000_000.0 }
        }
    } catch (e: Exception) {
        logHandledError(context, "local books: measure file", e)
        null
    }

    private fun readWithAndroid(uri: Uri): FileInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            fun key(k: Int) = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            FileInfo(
                durationSec = (key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0,
                mimeType = key(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                tags = LocalTags(
                    title = key(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    albumArtist = key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    author = key(MediaMetadataRetriever.METADATA_KEY_AUTHOR) ?: key(MediaMetadataRetriever.METADATA_KEY_WRITER),
                ),
            )
        } catch (e: Exception) {
            logHandledError(context, "local books: read file", e)
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Copies the folder's cover image (or the first file's embedded art) into app storage. */
    private fun saveCover(id: String, coverFile: LocalFileEntry?, firstAudio: Uri, refresh: Boolean): String? {
        val dest = File(coversDir.apply { mkdirs() }, "$id.img")
        if (dest.isFile && !refresh) return dest.path
        val bytes = runCatching {
            coverFile?.let { context.contentResolver.openInputStream(Uri.parse(it.uri))?.use { s -> s.readBytes() } }
        }.getOrNull() ?: run {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(context, firstAudio); r.embeddedPicture } catch (_: Exception) { null } finally { runCatching { r.release() } }
        } ?: return dest.takeIf { it.isFile }?.path
        return runCatching { dest.writeBytes(bytes); dest.path }.getOrNull()
    }

    private companion object {
        const val MAX_DEPTH = 5
    }
}
