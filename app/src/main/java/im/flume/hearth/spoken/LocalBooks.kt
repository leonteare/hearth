package im.flume.hearth.spoken

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import im.flume.hearth.HearthApp
import im.flume.hearth.R
import im.flume.hearth.api.AbsUploadFile
import im.flume.hearth.data.AppDatabase
import im.flume.hearth.logHandledError
import im.flume.hearth.util.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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

    private val _folders = MutableStateFlow(readFolders())
    val folders: StateFlow<List<LocalFolder>> = _folders.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanProblem = MutableStateFlow<String?>(null)
    /** Why the last scan couldn't read a folder, in plain words (null when it went fine). */
    val scanProblem: StateFlow<String?> = _scanProblem.asStateFlow()

    private val _uploadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Books being sent to the server and how far along (0..1). */
    val uploadProgress: StateFlow<Map<String, Float>> = _uploadProgress.asStateFlow()

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

    /** Keeps access to a folder from the system picker and scans it. */
    fun addFolder(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { logHandledError(context, "local books: keep folder access", it) }
        saveFolders(_folders.value.map { it.uri }.toSet() + uri.toString())
        scope.launch { rescan() }
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
            for (folder in _folders.value) {
                try {
                    val report = scanFolder(folder.uri)
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
        _folders.value.sumOf { dao.booksIn(it.uri).count { b -> !b.hidden && b.serverItemId == null } }
    }

    /** What a folder scan saw, so Settings can say why nothing turned up. */
    private class ScanReport(val audioFiles: Int, val unreadable: List<String>)

    private suspend fun scanFolder(treeUri: String): ScanReport = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val entries = listTree(tree)
        val unreadable = ArrayList<String>()
        val groups = LocalBookScan.group(entries)
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
            val (title, author) = if (!firstChanged && old != null) old.title to old.author else {
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
            )
            files += rows
        }
        // Covers of books that are gone.
        (oldBooks.keys - books.mapTo(HashSet()) { it.id }).forEach { gone -> oldBooks[gone]?.coverPath?.let { File(it).delete() } }
        dao.replaceFolder(treeUri, books, files)
        ScanReport(entries.count { LocalBookScan.isAudio(it.name) }, unreadable)
    }

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
        androidx.media3.exoplayer.MetadataRetriever.Builder(context, androidx.media3.common.MediaItem.fromUri(uri)).build().use { r ->
            r.retrieveDurationUs().get(60, java.util.concurrent.TimeUnit.SECONDS)
                .takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 }?.let { it / 1_000_000.0 }
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

    // ------------------------------------------------------------------ for the UI and player

    /** Phone books still to show (not removed, not yet on the server), A–Z. */
    fun books(): Flow<List<LocalBookRow>> = dao.visible()

    fun book(id: String): Flow<LocalBookRow?> = dao.book(id)

    /** One chapter per file for a book of several files, with times on the whole book. */
    fun chapters(id: String): Flow<List<ChapterEntity>> = dao.filesFlow(id).map { files -> chapterRows(id, files) }

    private fun chapterRows(id: String, files: List<LocalBookFileEntity>): List<ChapterEntity> =
        LocalBookNaming.chapters(files.map { it.durationSec }, LocalBookNaming.chapterTitles(files.map { it.relPath.substringAfterLast('/') }, files.map { it.title }))
            .mapIndexed { i, ch -> ChapterEntity(id, "", i, ch.startSec, ch.endSec, ch.title) }

    /** The book and its files, for the player. Null if it isn't on the phone. */
    suspend fun playable(id: String): Triple<LocalBookEntity, List<LocalBookFileEntity>, List<TimelineChapter>>? {
        val book = dao.bookOnce(id) ?: return null
        val files = dao.files(id).takeIf { it.isNotEmpty() } ?: return null
        return Triple(book, files, chapterRows(id, files).map { TimelineChapter(it.startSec, it.endSec, it.title) })
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
    fun upload(id: String) {
        scope.launch {
            dao.setUpload(id, LocalUploadState.UPLOADING.name, null)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "local-upload-$id", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LocalBookUploadWorker>()
                    .setInputData(workDataOf(LocalBookUploadWorker.ARG_ID to id))
                    .addTag(UPLOAD_TAG).addTag(id)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }

    /**
     * Sends the book's files to the server's audiobook library, has the server scan for it, then
     * links the two: listening so far carries over and the server's copy takes the phone book's place.
     * A book the server already has (e.g. a retry after the upload went through) is linked without
     * sending it again. Returns null on success, or what went wrong in plain words.
     */
    internal suspend fun runUpload(id: String): String? {
        val book = dao.bookOnce(id) ?: return null
        val account = spoken.account.value
        if (account == null || account.needsSignIn) return fail(id, "Sign in to podcasts & audiobooks first")
        return try {
            dao.setUpload(id, LocalUploadState.UPLOADING.name, null)
            val library = spoken.api.libraries().firstOrNull { it.mediaType == "book" } ?: return fail(id, "The server has no audiobook library")
            val folder = library.folders.firstOrNull() ?: return fail(id, "The server's audiobook library has no folder")
            var match = findOnServer(library.id, book)
            if (match == null) {
                val files = dao.files(id)
                val total = files.sumOf { it.sizeBytes }.coerceAtLeast(1)
                _uploadProgress.update { it + (id to 0f) }
                spoken.api.uploadBook(
                    library.id, folder.id, book.title, book.author,
                    files.map { f ->
                        AbsUploadFile(f.relPath.substringAfterLast('/'), f.sizeBytes, f.mimeType) {
                            context.contentResolver.openInputStream(Uri.parse(f.uri)) ?: throw IOException("Couldn't open ${f.relPath}")
                        }
                    },
                ) { sent ->
                    val f = (sent.toFloat() / total).coerceIn(0f, 1f)
                    // Every whole percent is plenty for the progress bar.
                    if ((f * 100).toInt() != ((_uploadProgress.value[id] ?: -1f) * 100).toInt()) _uploadProgress.update { it + (id to f) }
                }
                _uploadProgress.update { it - id }
                dao.setUpload(id, LocalUploadState.WAITING_FOR_SERVER.name, null)
                // The server may notice the new folder itself; asking for a scan makes sure.
                runCatching { spoken.api.scanLibrary(library.id) }
                val deadline = System.currentTimeMillis() + WAIT_FOR_SCAN_MS
                while (match == null && System.currentTimeMillis() < deadline) {
                    delay(POLL_MS)
                    match = findOnServer(library.id, book)
                }
            }
            if (match == null) return fail(id, "Uploaded, but the server hasn't added the book yet. Try again in a few minutes to finish.")
            link(id, match.id)
            null
        } catch (e: CancellationException) {
            withContext(NonCancellable) { _uploadProgress.update { it - id }; dao.setUpload(id, LocalUploadState.FAILED.name, "Adding to the server was stopped. Try again.") }
            throw e
        } catch (e: Exception) {
            logHandledError(context, "local books: upload", e)
            _uploadProgress.update { it - id }
            fail(id, userMessage(e))
        }
    }

    private suspend fun fail(id: String, message: String): String {
        dao.setUpload(id, LocalUploadState.FAILED.name, message)
        return message
    }

    private suspend fun findOnServer(libraryId: String, book: LocalBookEntity): ServerBookRef? {
        val items = spoken.api.allLibraryItems(libraryId).filter { !it.isMissing }
        val refs = items.map { i ->
            val m = i.media.metadata
            ServerBookRef(i.id, m.title.orEmpty(), m.authorName ?: m.authors.joinToString(", ") { it.name }.ifEmpty { null }, i.path, i.addedAt)
        }
        return UploadMatch.find(book.title, book.author, refs)
    }

    /** The server has the book: fetch it, carry listening over, and hide the phone copy from the list. */
    private suspend fun link(localId: String, serverId: String) {
        spoken.fetchItem(serverId)
        val serverBook = db.spoken().bookOnce(serverId)
        val server = runCatching { spoken.api.progress(serverId, null) }.getOrNull()
            ?.let { ProgressSnapshot(it.currentTime, it.duration, it.isFinished, it.lastUpdate, it.finishedAt) }
        ProgressCarry.decide(spoken.progress.localBookSnapshot(localId), server, serverBook?.durationSec ?: 0.0)?.let { spoken.progress.adopt(serverId, it) }
        db.spoken().speed(localId)?.let { db.spoken().setSpeed(SpokenPlayPrefEntity(serverId, it)) }
        dao.link(localId, serverId)
    }

    companion object {
        private const val KEY_FOLDERS = "folders"
        private const val MAX_DEPTH = 5
        const val UPLOAD_TAG = "local-upload"
        private const val WAIT_FOR_SCAN_MS = 4 * 60_000L
        private const val POLL_MS = 8_000L
    }
}

/** Adds one phone book to the server, showing a notification so it can finish with the app closed. */
class LocalBookUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(ARG_ID) ?: return Result.success()
        val local = (applicationContext as HearthApp).container.spoken.local
        runCatching { setForeground(foregroundInfo()) }
        local.runUpload(id)
        return Result.success()
    }

    private fun foregroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Adding an audiobook to the server")
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    companion object {
        const val ARG_ID = "id"
        private const val CHANNEL = "uploads"
        private const val NOTIFICATION_ID = 44
    }
}

/** Server books and phone books together for "Continue listening". */
internal fun mergeContinue(server: Flow<List<ContinueItem>>, phone: Flow<List<ContinueItem>>, limit: Int): Flow<List<ContinueItem>> =
    combine(server, phone) { a, b -> (a + b).sortedByDescending { it.lastPlayedAt }.take(limit) }
