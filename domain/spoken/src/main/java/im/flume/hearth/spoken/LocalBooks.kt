// Phone audiobooks: the picked folders, the book list for the UI and player, and the entry points for
// scanning and adding to the server (the work itself is in LocalBookScanner and LocalBookUpload).
package im.flume.hearth.spoken

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.WorkManager
import im.flume.hearth.core.data.AppDatabase
import im.flume.hearth.core.common.logHandledError
import im.flume.hearth.core.common.userMessage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import im.flume.hearth.core.data.spoken.ChapterEntity
import im.flume.hearth.core.data.spoken.LocalBookEntity
import im.flume.hearth.core.data.spoken.LocalBookFileEntity
import im.flume.hearth.core.data.spoken.LocalBookRow

/** A folder picked in Settings. [uri] is the document tree URI the app has lasting access to. */
data class LocalFolder(val uri: String, val name: String)

/**
 * Audiobooks from folders on this phone, picked with the system folder picker (no storage
 * permission needed). They're scanned when a folder is added, at app start and on "Rescan", play
 * through the same player as the server's books, and can be added to the server ("Add to server"),
 * after which the server's copy takes their place.
 */
class LocalBooks(
    private val context: Context,
    private val db: AppDatabase,
    private val spoken: SpokenRepository,
    private val scope: CoroutineScope,
) {
    private val dao = db.localBooks()
    private val prefs = context.getSharedPreferences("local-books", Context.MODE_PRIVATE)
    private val coversDir = File(context.filesDir, "local-covers")
    private val scanLock = Mutex()
    /** Proper titles, authors and covers from the server's book search, for badly named files. */
    val details = LocalBookDetails(context, dao, spoken, coversDir, scope)
    private val scanner = LocalBookScanner(context, dao, coversDir)
    private val chapterReader = LocalBookChapters(context)
    private val uploader = LocalBookUpload(context, db, spoken, scope)

    private val _folders = MutableStateFlow(readFolders())
    val folders: StateFlow<List<LocalFolder>> = _folders.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanProblem = MutableStateFlow<String?>(null)
    /** Why the last scan couldn't read a folder, in plain words (null when it went fine). */
    val scanProblem: StateFlow<String?> = _scanProblem.asStateFlow()

    /** Books being sent to the server and how far along (0..1). */
    val uploadProgress: StateFlow<Map<String, Float>> = uploader.uploadProgress

    /** Cover files by book id, kept warm so rows can ask without touching the disk. */
    private val covers = ConcurrentHashMap<String, String>()

    init {
        scope.launch { dao.all().collect { list -> covers.clear(); list.forEach { b -> b.coverPath?.let { covers[b.id] = it } } } }
    }

    /** App start: look for new or changed books, and tidy up uploads the app was closed during. */
    fun start() {
        scope.launch {
            val running = runCatching {
                WorkManager.getInstance(context).getWorkInfosByTag(UPLOAD_TAG).get()
                    .filter { !it.state.isFinished }.flatMap { it.tags }.filter { it.startsWith(LocalBookIds.PREFIX) }
            }.getOrDefault(emptyList())
            dao.failStaleUploads(running, "Adding to the server was interrupted. Try again.")
            rescan()
        }
    }

    // ------------------------------------------------------------------ folders

    private fun readFolders(): List<LocalFolder> =
        prefs.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().map { LocalFolder(it, folderName(it)) }.sortedBy { it.name.lowercase() }

    private fun saveFolders(uris: Set<String>) {
        prefs.edit().putStringSet(KEY_FOLDERS, uris).apply()
        _folders.value = readFolders()
    }

    private fun folderName(uri: String): String = runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Phone storage" }
    }.getOrDefault("Folder")

    private fun treeKey(uri: String): String? = runCatching {
        Uri.parse(uri).let { LocalFolders.key(it.authority, DocumentsContract.getTreeDocumentId(it)) }
    }.getOrNull()

    /**
     * Keeps access to a folder from the system picker and scans it. A folder already inside one you've
     * added isn't added again; one that holds folders you've added takes their place. Returns what to tell you.
     */
    fun addFolder(uri: Uri): String {
        val current = _folders.value.associateBy({ treeKey(it.uri) }, { it })
        val newKey = treeKey(uri.toString()) ?: uri.toString()
        val plan = LocalFolders.planAdd(newKey, current.keys.filterNotNull())
        if (!plan.add) return "That folder is already included in \"${current[plan.coveredBy]?.name ?: "your folders"}\""
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { logHandledError(context, "local books: keep folder access", it) }
        val replaced = plan.replaces.mapNotNull { current[it] }
        replaced.forEach { removeFolder(it.uri) }
        saveFolders(_folders.value.map { it.uri }.toSet() - replaced.map { it.uri }.toSet() + uri.toString())
        scope.launch { rescan() }
        return "Looking for audiobooks in that folder…"
    }

    /** Forgets a folder: its books leave the list (files on the phone are untouched; progress is kept in case it comes back). */
    fun removeFolder(uri: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        saveFolders(_folders.value.map { it.uri }.toSet() - uri)
        scope.launch {
            dao.booksIn(uri).forEach { b -> b.coverPath?.let { File(it).delete() } }
            dao.removeFolder(uri)
        }
    }

    // ------------------------------------------------------------------ scanning

    /** Scans every folder. Returns how many books are on the phone. */
    suspend fun rescan(): Int = scanLock.withLock {
        _scanning.value = true
        var problem: String? = null
        try {
            // A file reachable from two picked folders is listed once (from the first).
            val claimed = HashSet<String>()
            for (folder in _folders.value) {
                try {
                    val report = scanner.scanFolder(folder.uri, claimed)
                    problem = problem ?: LocalBookScan.scanProblem(folder.name, report.audioFiles, report.unreadable)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logHandledError(context, "local books: scan", e)
                    problem = if (e is SecurityException) "Hearth can't open \"${folder.name}\" any more. Remove it and add it again." else "Couldn't read \"${folder.name}\": ${userMessage(e)}"
                }
            }
        } finally {
            _scanning.value = false
            _scanProblem.value = problem
        }
        details.lookUpNew()
        _folders.value.sumOf { dao.booksIn(it.uri).count { b -> !b.hidden && b.serverItemId == null } }
    }

    // ------------------------------------------------------------------ for the UI and player

    /** Phone books still to show (not removed, not yet on the server), A–Z. */
    fun books(): Flow<List<LocalBookRow>> = dao.visible()

    fun book(id: String): Flow<LocalBookRow?> = dao.book(id)

    /**
     * The book's chapters, with times on the whole book: the chapters stored inside its files (a lone
     * .m4b usually has them), otherwise one per file for a book of several files.
     */
    fun chapters(id: String): Flow<List<ChapterEntity>> = dao.filesFlow(id).map { files -> chapterReader.chapterRows(id, files) }.flowOn(Dispatchers.IO)

    /** The book and its files, for the player. Null if it isn't on the phone. */
    suspend fun playable(id: String): Triple<LocalBookEntity, List<LocalBookFileEntity>, List<TimelineChapter>>? {
        val book = dao.bookOnce(id) ?: return null
        val files = dao.files(id).takeIf { it.isNotEmpty() } ?: return null
        val chapters = withContext(Dispatchers.IO) { chapterReader.chapterRows(id, files) }
        return Triple(book, files, chapters.map { TimelineChapter(it.startSec, it.endSec, it.title) })
    }

    /** The cover as a file URL Coil can load, or null when the book has none. */
    fun coverUrl(id: String): String? = covers[id]?.let { Uri.fromFile(File(it)).toString() }

    /** Started phone books for "Continue listening". */
    fun inProgress(limit: Int): Flow<List<ContinueItem>> = dao.inProgress(limit).map { rows ->
        rows.map { ContinueItem(it.book.id, null, it.book.title, it.book.author, it.positionSec ?: 0.0, it.book.durationSec, it.progressUpdatedAt ?: 0) }
    }

    suspend fun durationOf(id: String): Double = dao.bookOnce(id)?.durationSec ?: 0.0

    /** Takes a book off the list (its files stay on the phone). Undo puts it back. */
    suspend fun setHidden(id: String, hidden: Boolean) = dao.setHidden(id, hidden)

    // ------------------------------------------------------------------ add to server

    /** Starts adding a book to the server; it carries on if the app is closed. */
    fun upload(id: String) = uploader.upload(id)

    /** Sends the book to the server and links it; see [LocalBookUpload.runUpload]. Null on success, else what went wrong. */
    internal suspend fun runUpload(id: String): String? = uploader.runUpload(id)

    companion object {
        private const val KEY_FOLDERS = "folders"
        const val UPLOAD_TAG = "local-upload"
    }
}

/** Server books and phone books together for "Continue listening". */
internal fun mergeContinue(server: Flow<List<ContinueItem>>, phone: Flow<List<ContinueItem>>, limit: Int): Flow<List<ContinueItem>> =
    combine(server, phone) { a, b -> (a + b).sortedByDescending { it.lastPlayedAt }.take(limit) }
