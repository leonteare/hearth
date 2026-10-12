// Working out a phone book's chapters, and reading the chapters stored inside its files.
package im.flume.hearth.domain.spoken

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import im.flume.hearth.core.common.logHandledError
import java.util.concurrent.ConcurrentHashMap
import im.flume.hearth.core.data.spoken.ChapterEntity
import im.flume.hearth.core.data.spoken.LocalBookFileEntity

/** Chapters of a phone book, from the chapters stored inside its files or else one per file. */
internal class LocalBookChapters(private val context: Context) {
    fun chapterRows(id: String, files: List<LocalBookFileEntity>): List<ChapterEntity> =
        EmbeddedChapters.timeline(
            files.map { it.durationSec },
            LocalBookNaming.chapterTitles(files.map { it.relPath.substringAfterLast('/') }, files.map { it.title }),
            files.map { embeddedChapters(it) },
        ).mapIndexed { i, ch -> ChapterEntity(id, "", i, ch.startSec, ch.endSec, ch.title) }

    /**
     * Chapters stored inside a file, kept by file and modified time: read when first needed rather than
     * saved with the scan, so books scanned earlier get them too, and a changed file is read again.
     */
    private val embedded = ConcurrentHashMap<String, List<EmbeddedChapter>>()

    private fun embeddedChapters(f: LocalBookFileEntity): List<EmbeddedChapter> {
        if (!EmbeddedChapters.mayHaveChapters(f.relPath)) return emptyList()
        val key = "${f.uri}|${f.lastModified}"
        embedded[key]?.let { return it }
        return try {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(f.uri), "r") ?: return emptyList()
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { s -> EmbeddedChapters.read(f.relPath, FileChannelSource(s.channel)) }
                .also { embedded[key] = it }
        } catch (e: Exception) {
            logHandledError(context, "local books: read chapters", e)
            emptyList()
        }
    }
}
