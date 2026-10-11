package im.flume.hearth.spoken

import android.content.Context
import im.flume.hearth.api.AbsAuthException
import im.flume.hearth.api.AbsHttpException
import im.flume.hearth.logHandledError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

/**
 * Proper titles, authors and covers for phone books with poor file names, from the audiobook
 * server's book search (Audible). Each new book is looked up once, a request at a time; a match is
 * applied by itself only when the title and length agree (see [BookMatch.rank]). Otherwise the
 * results are kept for the book page's "Find details", and nothing about the book changes.
 */
class LocalBookDetails(
    private val context: Context,
    private val dao: LocalBookDao,
    private val spoken: SpokenRepository,
    private val coversDir: File,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val savedDir = File(context.filesDir, "local-book-matches")
    private val lookUpLock = Mutex()
    private val requestLock = Mutex()
    private var lastRequestAt = 0L

    private fun canAsk(): Boolean {
        val account = spoken.account.value
        return account != null && !account.needsSignIn && !spoken.offline
    }

    /**
     * Looks up books the server hasn't been asked about (new ones, and those from before this
     * feature). Stops quietly when offline, signed out or the server is having trouble; the rest
     * are tried after the next scan.
     */
    fun lookUpNew() {
        scope.launch {
            if (!lookUpLock.tryLock()) return@launch
            try {
                val tried = HashSet<String>()
                while (canAsk()) {
                    val book = dao.notLookedUp().firstOrNull { it.id !in tried } ?: break
                    tried += book.id
                    if (!lookUp(book)) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Looking up details is a nicety: whatever goes wrong, the app carries on.
                logHandledError(context, "local books: look up details", e)
            } finally {
                lookUpLock.unlock()
            }
        }
    }

    /** One book. False when the server can't be asked right now, so the run should stop. */
    private suspend fun lookUp(book: LocalBookEntity): Boolean {
        val query = BookMatch.query(book.fileTitle ?: book.title, if (book.fileTitle != null) book.fileAuthor else book.author)
        val result = try {
            search(book.id, query, book.durationSec)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AbsHttpException) {
            logHandledError(context, "local books: look up details", e)
            // The server didn't like this one search: don't ask about this book again, carry on with the rest.
            if (e.code in 400..499 && e.code !in setOf(401, 403, 408, 429)) { dao.setMatchChecked(book.id, System.currentTimeMillis()); return true }
            return false
        } catch (e: AbsAuthException) {
            return false
        } catch (e: Exception) {
            logHandledError(context, "local books: look up details", e)
            return false
        }
        val fresh = dao.bookOnce(book.id) ?: return true
        // The user picked or declined something meanwhile: theirs wins.
        if (fresh.matchState != null) return true
        val best = result.best
        if (result.confidence == MatchConfidence.HIGH && best != null) apply(fresh, best.candidate, LocalMatchState.AUTO)
        else dao.setMatchChecked(book.id, System.currentTimeMillis())
        return true
    }

    /**
     * Searches the UK Audible store, then the US one unless the first already found a sure match.
     * The results are saved for the book page.
     */
    private suspend fun search(id: String, query: BookQuery, durationSec: Double): BookMatchResult {
        val found = ArrayList<BookCandidate>()
        var result = BookMatchResult(emptyList(), MatchConfidence.NONE)
        for (provider in BookMatch.PROVIDERS) {
            found += politely { spoken.api.searchBooks(query.title, null, provider) }.map { it.toCandidate(provider) }
            result = BookMatch.rank(query, durationSec, found)
            if (result.confidence == MatchConfidence.HIGH) break
        }
        save(id, SavedBookSearch(query.title, result.ranked.map { it.candidate }))
        return result
    }

    /** One request at a time, a moment apart, so a big folder doesn't flood the server (or Audible). */
    private suspend fun <T> politely(block: suspend () -> T): T = requestLock.withLock {
        val wait = lastRequestAt + REQUEST_GAP_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try { block() } finally { lastRequestAt = System.currentTimeMillis() }
    }

    // ------------------------------------------------------------------ for the book page

    /** What the last search for this book found, or null if it was never looked up. */
    suspend fun saved(id: String): SavedBookSearch? = withContext(Dispatchers.IO) {
        runCatching { File(savedDir, "$id.json").takeIf { it.isFile }?.readText()?.let { json.decodeFromString(SavedBookSearch.serializer(), it) } }.getOrNull()
    }

    private suspend fun save(id: String, search: SavedBookSearch) = withContext(Dispatchers.IO) {
        runCatching { File(savedDir.apply { mkdirs() }, "$id.json").writeText(json.encodeToString(SavedBookSearch.serializer(), search)) }
            .onFailure { logHandledError(context, "local books: save search", it) }
    }

    /** "Search again" on the book page, with the user's own wording. Throws when the server can't be reached. */
    suspend fun searchAgain(id: String, title: String): List<BookCandidate> {
        val book = dao.bookOnce(id) ?: return emptyList()
        val result = search(id, BookMatch.query(title, null), book.durationSec)
        if (book.matchCheckedAt == 0L) dao.setMatchChecked(id, System.currentTimeMillis())
        return result.ranked.map { it.candidate }
    }

    /** The user picked a result: use its details (and its cover, if the book has none of its own). */
    suspend fun choose(id: String, candidate: BookCandidate) {
        val book = dao.bookOnce(id) ?: return
        apply(book, candidate, LocalMatchState.CONFIRMED)
    }

    /** "Use the file's own name": back to what the files say, and no more automatic look-ups. */
    suspend fun useFileNames(id: String) {
        val book = dao.bookOnce(id) ?: return
        val cover = if (BookMatch.isOnlineCover(book.coverPath)) { book.coverPath?.let { File(it).delete() }; null } else book.coverPath
        save(BookMatch.declined(book, cover, System.currentTimeMillis()))
    }

    private suspend fun apply(book: LocalBookEntity, candidate: BookCandidate, state: LocalMatchState) {
        var cover = book.coverPath
        if (BookMatch.wantsOnlineCover(book.coverPath)) {
            val downloaded = candidate.cover?.let { downloadCover(book.id, it) }
            if (downloaded != book.coverPath) book.coverPath?.let { File(it).delete() }
            cover = downloaded
        }
        save(BookMatch.applied(book, candidate, state, cover, System.currentTimeMillis()))
    }

    private suspend fun save(b: LocalBookEntity) =
        dao.setDetails(b.id, b.title, b.author, b.narrator, b.description, b.publishedYear, b.series, b.coverPath, b.matchState, b.matchSource, b.matchCheckedAt)

    /** Saves a found cover beside the others. A new name per picture, so the old one is never shown from cache. */
    private suspend fun downloadCover(id: String, url: String): String? = withContext(Dispatchers.IO) {
        val dest = File(coversDir.apply { mkdirs() }, "$id-online-${Integer.toHexString(url.hashCode())}.img")
        if (dest.isFile && dest.length() > 0) return@withContext dest.path
        try {
            spoken.api.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful || body == null || body.contentType()?.type != "image") return@withContext null
                val bytes = body.bytes().takeIf { it.isNotEmpty() && it.size <= MAX_COVER_BYTES } ?: return@withContext null
                dest.writeBytes(bytes)
                dest.path
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logHandledError(context, "local books: download cover", e)
            null
        }
    }

    companion object {
        private const val REQUEST_GAP_MS = 1_500L
        private const val MAX_COVER_BYTES = 10 * 1024 * 1024
    }
}
