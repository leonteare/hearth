// Adding a phone audiobook to the server, and linking it to the server's copy once it arrives.
package im.flume.hearth.spoken

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import im.flume.hearth.core.network.AbsUploadFile
import im.flume.hearth.core.data.AppDatabase
import im.flume.hearth.core.common.logHandledError
import im.flume.hearth.core.common.userMessage
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import im.flume.hearth.core.data.spoken.LocalBookEntity
import im.flume.hearth.core.data.spoken.LocalUploadState
import im.flume.hearth.core.data.spoken.SpokenPlayPrefEntity

/** Adding a phone book to the server: sending its files, waiting for the server to add it, and linking the two. */
internal class LocalBookUpload(
    private val context: Context,
    private val db: AppDatabase,
    private val spoken: SpokenRepository,
    private val scope: CoroutineScope,
) {
    private val dao = db.localBooks()

    private val _uploadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Books being sent to the server and how far along (0..1). */
    val uploadProgress: StateFlow<Map<String, Float>> = _uploadProgress.asStateFlow()

    /** Starts adding a book to the server; it carries on if the app is closed. */
    fun upload(id: String) {
        scope.launch {
            dao.setUpload(id, LocalUploadState.UPLOADING.name, null)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "local-upload-$id", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LocalBookUploadWorker>()
                    .setInputData(workDataOf(LocalBookUploadWorker.ARG_ID to id))
                    .addTag(LocalBooks.UPLOAD_TAG).addTag(id)
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

    private companion object {
        const val WAIT_FOR_SCAN_MS = 4 * 60_000L
        const val POLL_MS = 8_000L
    }
}
