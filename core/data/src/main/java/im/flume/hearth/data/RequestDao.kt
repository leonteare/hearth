// Room queries for music requested from Tidarr.
package im.flume.hearth.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RequestDao {
    @Query("SELECT * FROM requests ORDER BY requestedAt DESC") fun all(): Flow<List<RequestEntity>>
    @Query("SELECT * FROM requests ORDER BY requestedAt DESC") suspend fun allOnce(): List<RequestEntity>
    @Query("SELECT * FROM requests WHERE status IN ('REQUESTED', 'DOWNLOADING', 'ADDING') ORDER BY requestedAt")
    suspend fun active(): List<RequestEntity>
    @Query("SELECT * FROM requests WHERE type = :type AND tidalId = :tidalId") suspend fun get(type: String, tidalId: String): RequestEntity?
    @Upsert suspend fun upsert(item: RequestEntity)
    @Query("DELETE FROM requests WHERE type = :type AND tidalId = :tidalId") suspend fun delete(type: String, tidalId: String)
    @Query("DELETE FROM requests WHERE status = 'DONE'") suspend fun clearDone()
}
