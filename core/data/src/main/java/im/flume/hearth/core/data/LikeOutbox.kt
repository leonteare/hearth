// Likes and unlikes waiting to reach the server: the table, what can be liked, and its queries.
package im.flume.hearth.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** What was liked: a song (Liked Songs), an album (Your Library) or an artist (who you follow). */
enum class LikeKind { SONG, ALBUM, ARTIST }

/**
 * A like or unlike made on this phone that the server hasn't been told about yet, because it
 * couldn't be reached. One row per thing: liking something again replaces the row, so however many
 * times it is tapped, the last choice is the one the server is told about.
 */
@Entity(tableName = "like_outbox", primaryKeys = ["kind", "id"])
data class LikeOutboxEntity(
    val kind: LikeKind,
    val id: String,
    /** How it ended up: liked, or not liked. */
    val liked: Boolean,
    /** When it was tapped; the queue is pushed oldest first. */
    val changedAt: Long,
)

@Dao
interface LikeOutboxDao {
    /** Queues the change, replacing any earlier one for the same thing (the last tap wins). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: LikeOutboxEntity)

    @Query("SELECT * FROM like_outbox ORDER BY changedAt, id")
    suspend fun all(): List<LikeOutboxEntity>

    /** Forgets a pushed change, unless the same thing was tapped again while it was being pushed. */
    @Query("DELETE FROM like_outbox WHERE kind = :kind AND id = :id AND changedAt = :changedAt")
    suspend fun delete(kind: LikeKind, id: String, changedAt: Long)
}
