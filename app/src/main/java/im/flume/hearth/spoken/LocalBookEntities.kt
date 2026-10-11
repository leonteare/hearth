package im.flume.hearth.spoken

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Audiobooks found in folders the user picked on this phone. They play like the server's books but
// their progress stays on the phone, until the book is added to the server.

/**
 * A book in a picked folder ([treeUri]); [relPath] is its sub-folder, or the lone file, inside it.
 * [hidden]: removed from the list by the user (a rescan doesn't bring it back). [serverItemId]: added
 * to the server, so the server's copy is shown instead.
 */
@Entity(tableName = "local_books")
data class LocalBookEntity(
    @PrimaryKey val id: String,
    val treeUri: String,
    val relPath: String,
    val title: String,
    val author: String?,
    val durationSec: Double,
    val sizeBytes: Long,
    val numFiles: Int,
    /** The cover saved in app storage, if the folder or file has one. */
    val coverPath: String?,
    val addedAt: Long,
    val scannedAt: Long,
    val hidden: Boolean,
    val serverItemId: String?,
    /** [LocalUploadState] name while adding to the server or after it failed; null otherwise. */
    val uploadState: String?,
    val uploadError: String?,
)

enum class LocalUploadState { UPLOADING, WAITING_FOR_SERVER, FAILED }

/** One audio file of a phone book, in play order. [uri] is its content:// document URI. */
@Entity(tableName = "local_book_files", primaryKeys = ["bookId", "fileIndex"])
data class LocalBookFileEntity(
    val bookId: String,
    val fileIndex: Int,
    val uri: String,
    val relPath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val durationSec: Double,
    val mimeType: String?,
    /** The file's own title tag, used for chapter names. */
    val title: String?,
)

/** Where you are in a phone book. [updatedAt] is on the server's clock, so it compares fairly once uploaded. */
@Entity(tableName = "local_book_progress")
data class LocalBookProgressEntity(
    @PrimaryKey val bookId: String,
    val positionSec: Double,
    val durationSec: Double,
    val finished: Boolean,
    val finishedAt: Long?,
    val updatedAt: Long,
)

data class LocalBookRow(
    @Embedded val book: LocalBookEntity,
    val positionSec: Double?,
    val finished: Boolean,
    val progressUpdatedAt: Long?,
) {
    /** As a server book row, so the player and "Continue listening" can show it like any other book. */
    fun toBookRow(): BookRow = BookRow(
        BookEntity(
            id = book.id, libraryId = "", title = book.title, subtitle = null, author = book.author, narrator = null, series = null,
            description = null, publishedYear = null, durationSec = book.durationSec, sizeBytes = book.sizeBytes,
            numTracks = book.numFiles, numChapters = if (book.numFiles > 1) book.numFiles else 0, tags = "",
            addedAt = book.addedAt, updatedAt = book.scannedAt, detailsFetchedAt = book.scannedAt,
        ),
        progressSec = positionSec, finished = finished, progressUpdatedAt = progressUpdatedAt,
        downloadState = null, downloadedBytes = null,
    )
}

@Dao
interface LocalBookDao {
    /** Books to list: not removed by the user and not yet on the server. */
    @Query(LOCAL_ROW + " WHERE b.hidden = 0 AND b.serverItemId IS NULL ORDER BY b.title COLLATE NOCASE")
    fun visible(): Flow<List<LocalBookRow>>

    @Query(LOCAL_ROW + " WHERE b.id = :id") fun book(id: String): Flow<LocalBookRow?>
    @Query("SELECT * FROM local_books WHERE id = :id") suspend fun bookOnce(id: String): LocalBookEntity?
    @Query("SELECT * FROM local_books WHERE treeUri = :treeUri") suspend fun booksIn(treeUri: String): List<LocalBookEntity>
    @Query("SELECT * FROM local_books") fun all(): Flow<List<LocalBookEntity>>

    @Query("SELECT * FROM local_book_files WHERE bookId = :id ORDER BY fileIndex") suspend fun files(id: String): List<LocalBookFileEntity>
    @Query("SELECT * FROM local_book_files WHERE bookId = :id ORDER BY fileIndex") fun filesFlow(id: String): Flow<List<LocalBookFileEntity>>
    @Query("SELECT f.* FROM local_book_files f JOIN local_books b ON b.id = f.bookId WHERE b.treeUri = :treeUri")
    suspend fun filesIn(treeUri: String): List<LocalBookFileEntity>

    @Query("DELETE FROM local_book_files WHERE bookId IN (SELECT id FROM local_books WHERE treeUri = :treeUri)") suspend fun deleteFilesIn(treeUri: String)
    @Query("DELETE FROM local_books WHERE treeUri = :treeUri") suspend fun deleteBooksIn(treeUri: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBooks(items: List<LocalBookEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFiles(items: List<LocalBookFileEntity>)

    /**
     * Replaces everything found in one folder at once, so the list never shows it half-scanned. What
     * the user did meanwhile (removed a book, added it to the server) is kept from the current rows.
     */
    @Transaction
    suspend fun replaceFolder(treeUri: String, books: List<LocalBookEntity>, files: List<LocalBookFileEntity>) {
        val current = booksIn(treeUri).associateBy { it.id }
        deleteFilesIn(treeUri)
        deleteBooksIn(treeUri)
        insertBooks(books.map { b ->
            current[b.id]?.let { c -> b.copy(hidden = c.hidden, serverItemId = c.serverItemId, uploadState = c.uploadState, uploadError = c.uploadError) } ?: b
        })
        insertFiles(files)
    }

    @Transaction
    suspend fun removeFolder(treeUri: String) {
        deleteFilesIn(treeUri)
        deleteBooksIn(treeUri)
    }

    @Query("UPDATE local_books SET hidden = :hidden WHERE id = :id") suspend fun setHidden(id: String, hidden: Boolean)
    @Query("UPDATE local_books SET uploadState = :state, uploadError = :error WHERE id = :id") suspend fun setUpload(id: String, state: String?, error: String?)
    @Query("UPDATE local_books SET serverItemId = :serverItemId, uploadState = NULL, uploadError = NULL WHERE id = :id") suspend fun link(id: String, serverItemId: String)
    /** Uploads cut short when the app was closed. */
    @Query("UPDATE local_books SET uploadState = 'FAILED', uploadError = :error WHERE uploadState IN ('UPLOADING', 'WAITING_FOR_SERVER') AND id NOT IN (:running)")
    suspend fun failStaleUploads(running: List<String>, error: String)

    @Query("SELECT * FROM local_book_progress WHERE bookId = :id") suspend fun progress(id: String): LocalBookProgressEntity?
    @Query("SELECT * FROM local_book_progress WHERE bookId = :id") fun progressFlow(id: String): Flow<LocalBookProgressEntity?>
    @Upsert suspend fun upsertProgress(item: LocalBookProgressEntity)

    /** Started, unfinished phone books, most recently played first. */
    @Query(LOCAL_ROW + " WHERE b.hidden = 0 AND b.serverItemId IS NULL AND p.positionSec > 0 AND p.finished = 0 ORDER BY p.updatedAt DESC LIMIT :limit")
    fun inProgress(limit: Int): Flow<List<LocalBookRow>>

    companion object {
        const val LOCAL_ROW = """SELECT b.*, p.positionSec AS positionSec, COALESCE(p.finished, 0) AS finished, p.updatedAt AS progressUpdatedAt
            FROM local_books b LEFT JOIN local_book_progress p ON p.bookId = b.id"""
    }
}
