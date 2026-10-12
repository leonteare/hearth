package im.flume.hearth.spoken

import im.flume.hearth.core.network.PodcastPaths
import java.security.MessageDigest
import kotlin.math.abs

// Audiobooks from a folder on the phone: which files make a book, in what order, what it's called,
// and how a book uploaded to the server is found again there. Plain values only, so it's unit tested.

/** Ids of books on the phone: "phone-…", so they never clash with the server's ids and can share the player. */
object LocalBookIds {
    const val PREFIX = "phone-"

    fun isLocal(id: String?): Boolean = id?.startsWith(PREFIX) == true

    /** Stable across rescans (same folder, same place in it), so progress survives a rescan or re-adding the folder. */
    fun idFor(treeUri: String, relPath: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$treeUri\n$relPath".toByteArray(Charsets.UTF_8))
        return PREFIX + digest.take(10).joinToString("") { "%02x".format(it) }
    }
}

/** What adding a folder does, given the folders already added (all as [LocalFolders.key]s). */
data class FolderPlan(val add: Boolean, val replaces: List<String>, val coveredBy: String?)

/**
 * Picked folders can overlap (a folder and one inside it), which would list every book in both twice.
 * Folders are compared by storage and path: "primary:Audiobooks" holds "primary:Audiobooks/Sadie".
 */
object LocalFolders {
    fun key(authority: String?, documentId: String): String = "${authority.orEmpty()}|$documentId"

    fun isInside(child: String, parent: String): Boolean {
        if (child == parent) return false
        val (ca, cp) = child.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val (pa, pp) = parent.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (ca != pa) return false
        // A storage root is "primary:"; anything under it starts with that.
        return if (pp.endsWith(":")) cp.startsWith(pp) else cp.startsWith("$pp/")
    }

    fun planAdd(new: String, existing: List<String>): FolderPlan {
        if (new in existing) return FolderPlan(add = false, replaces = emptyList(), coveredBy = new)
        existing.firstOrNull { isInside(new, it) }?.let { return FolderPlan(add = false, replaces = emptyList(), coveredBy = it) }
        return FolderPlan(add = true, replaces = existing.filter { isInside(it, new) }, coveredBy = null)
    }
}

/** A file found in a picked folder. [relPath] is "/"-separated, relative to the picked folder. */
data class LocalFileEntry(val relPath: String, val uri: String, val size: Long, val lastModified: Long) {
    val name: String get() = relPath.substringAfterLast('/')
    val dir: String get() = relPath.substringBeforeLast('/', "")
}

/** One book: its folder (or lone file) [key], a name to fall back on, its audio in play order and a cover image. */
data class LocalBookGroup(val key: String, val name: String, val files: List<LocalFileEntry>, val cover: LocalFileEntry?)

object LocalBookScan {
    val AUDIO = setOf("m4b", "m4a", "mp3", "aac", "ogg", "opus", "flac")
    private val IMAGE = setOf("jpg", "jpeg", "png")
    /** Cover file names, best first. */
    private val COVER_NAMES = listOf("cover", "folder")
    /** "CD 1", "Disc 2", "Part 03": a book split over sub-folders stays one book. */
    private val DISC = Regex("""^(cd|disc|disk|part)[\s._-]*\d+$""", RegexOption.IGNORE_CASE)

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()
    fun isAudio(name: String): Boolean = !name.startsWith(".") && extension(name) in AUDIO
    fun isImage(name: String): Boolean = !name.startsWith(".") && extension(name) in IMAGE
    /**
     * What Settings says after a scan when something's off: no audio at all (wrong folder, or files of
     * a kind Hearth can't play, like Audible's), or files that couldn't be read. Null when all is well.
     */
    fun scanProblem(folder: String, audioFiles: Int, unreadable: List<String>): String? = when {
        audioFiles == 0 -> "No audiobook files in \"$folder\". Hearth looks for ${AUDIO.sorted().joinToString(", ") { ".$it" }} files, in the folder and its sub-folders. Audible's own files (.aax) only play in the Audible app."
        unreadable.isEmpty() -> null
        unreadable.size == audioFiles -> "Found ${plural(audioFiles)} in \"$folder\" but couldn't read any of them (for example \"${unreadable.first()}\"). They may still be downloading, or be damaged."
        else -> "Couldn't read ${unreadable.size} of ${plural(audioFiles)} in \"$folder\" (for example \"${unreadable.first()}\"). The rest are in your library."
    }

    private fun plural(n: Int) = if (n == 1) "1 audio file" else "$n audio files"

    fun withoutExtension(name: String): String = if ('.' in name) name.substringBeforeLast('.') else name

    /**
     * Groups a folder's files into books: every folder holding audio is one book (disc sub-folders
     * join their parent), and each audio file directly in the picked folder is a book of its own.
     * Files play in natural name order ("Chapter 2" before "Chapter 10").
     */
    fun group(entries: List<LocalFileEntry>): List<LocalBookGroup> {
        val audio = entries.filter { isAudio(it.name) }
        val images = entries.filter { isImage(it.name) }
        val books = ArrayList<LocalBookGroup>()
        audio.filter { it.dir.isEmpty() }.forEach { f -> books += LocalBookGroup(f.relPath, withoutExtension(f.name), listOf(f), null) }
        audio.filter { it.dir.isNotEmpty() }.groupBy { bookDir(it.dir) }.forEach { (dir, files) ->
            val ordered = files.sortedWith { a, b -> naturalCompare(a.relPath.removePrefix("$dir/"), b.relPath.removePrefix("$dir/")) }
            books += LocalBookGroup(dir, dir.substringAfterLast('/'), ordered, coverIn(dir, images))
        }
        return books.sortedWith { a, b -> naturalCompare(a.key, b.key) }
    }

    private fun bookDir(dir: String): String {
        val last = dir.substringAfterLast('/')
        return if ('/' in dir && DISC.matches(last.trim())) dir.substringBeforeLast('/') else dir
    }

    private fun coverIn(dir: String, images: List<LocalFileEntry>): LocalFileEntry? {
        val here = images.filter { it.dir == dir }
        for (name in COVER_NAMES) here.filter { withoutExtension(it.name).equals(name, ignoreCase = true) }.minByOrNull { if (extension(it.name) == "png") 1 else 0 }?.let { return it }
        return here.sortedWith { a, b -> naturalCompare(a.name, b.name) }.firstOrNull()
    }

    /** Case-insensitive, with runs of digits compared as numbers. */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i; val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0'); val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }
}

/** Tags read from a book's first audio file. */
data class LocalTags(val title: String? = null, val album: String? = null, val artist: String? = null, val albumArtist: String? = null, val author: String? = null)

object LocalBookNaming {
    /**
     * Title and author for a book. A folder of files is named by its album tag (each file's own title
     * is a chapter); a lone file by its title. The folder or file name is the fallback.
     */
    fun describe(group: LocalBookGroup, tags: LocalTags?): Pair<String, String?> {
        fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
        val loneFile = group.files.size == 1 && group.key == group.files.single().relPath
        val title = if (loneFile) tags?.title.clean() ?: tags?.album.clean() else tags?.album.clean() ?: tags?.title.clean()?.takeIf { group.files.size == 1 }
        val author = tags?.albumArtist.clean() ?: tags?.author.clean() ?: tags?.artist.clean()
        return (title ?: group.name) to author
    }

    /** Chapter names for a book's files: their title tags when those differ, otherwise the file names. */
    fun chapterTitles(fileNames: List<String>, fileTitles: List<String?>): List<String> {
        val titles = fileTitles.map { it?.trim()?.takeIf { t -> t.isNotEmpty() } }
        val useTags = titles.all { it != null } && titles.toSet().size == titles.size
        return fileNames.mapIndexed { i, n -> if (useTags) titles[i]!! else LocalBookScan.withoutExtension(n) }
    }

    /** Chapters for a multi-file book: one per file, back to back. None for a single file. */
    fun chapters(durations: List<Double>, titles: List<String>): List<TimelineChapter> {
        if (durations.size < 2) return emptyList()
        var start = 0.0
        return durations.mapIndexed { i, d -> TimelineChapter(start, start + d, titles.getOrElse(i) { "Chapter ${i + 1}" }).also { start += d } }
    }
}

/** A book in the server's audiobook library, as far as matching an upload goes. */
data class ServerBookRef(val id: String, val title: String, val author: String?, val path: String?, val addedAt: Long = 0)

object UploadMatch {
    private fun norm(s: String?): String = s.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** The folder the server puts an upload in, below its library folder: "Author/Title" (no series). */
    fun expectedFolder(title: String, author: String?): String =
        listOfNotNull(author?.takeIf { it.isNotBlank() }, title).joinToString("/") { PodcastPaths.sanitizeFilename(it.trim()) }

    /**
     * Finds the server's copy of a book uploaded from this phone: first by the folder the upload went
     * to, then by title and author. With no author, a title only counts when exactly one book has it.
     */
    fun find(title: String, author: String?, candidates: List<ServerBookRef>): ServerBookRef? {
        val folder = "/" + expectedFolder(title, author).lowercase()
        candidates.filter { it.path?.replace('\\', '/')?.trimEnd('/')?.lowercase()?.endsWith(folder) == true }
            .maxByOrNull { it.addedAt }?.let { return it }
        val sameTitle = candidates.filter { norm(it.title) == norm(title) }
        if (author.isNullOrBlank()) return sameTitle.singleOrNull()
        return sameTitle.filter { norm(it.author) == norm(author) || norm(it.author).contains(norm(author)) }.maxByOrNull { it.addedAt }
    }
}

object ProgressCarry {
    /**
     * What to save on the server copy of an uploaded book: this phone's progress, unless nothing was
     * heard yet or the server already has newer (or the same) progress. The position is kept within
     * the server's length.
     */
    fun decide(local: ProgressSnapshot?, server: ProgressSnapshot?, serverDurationSec: Double): ProgressSnapshot? {
        if (local == null || (!local.isFinished && local.currentTime < 1.0)) return null
        if (server != null && server.lastUpdate >= local.lastUpdate) return null
        if (server != null && server.isFinished == local.isFinished && abs(server.currentTime - local.currentTime) < 1.0) return null
        val duration = serverDurationSec.takeIf { it > 0 } ?: local.duration
        return local.copy(currentTime = if (duration > 0) local.currentTime.coerceIn(0.0, duration) else local.currentTime, duration = duration)
    }
}
