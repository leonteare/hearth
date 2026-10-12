package im.flume.hearth.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** Saved lyrics, one row per song (see LyricsRepository). */
@Entity(tableName = "lyrics")
data class LyricsEntity(@PrimaryKey val songId: String, val json: String, val fetchedAt: Long)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun get(songId: String): LyricsEntity?

    @Upsert
    suspend fun put(item: LyricsEntity)
}
