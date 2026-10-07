package im.flume.hearth.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import im.flume.hearth.api.LyricLine
import im.flume.hearth.api.SubsonicClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Lyrics(val synced: Boolean, val lines: List<LyricLine>) {
    val isEmpty: Boolean get() = lines.none { it.value.isNotBlank() }
}

/** A row on the lyrics screen: a sung line, or an instrumental break shown as animated dots. */
data class LyricRow(val start: Long?, val end: Long?, val text: String, val isBreak: Boolean)

/**
 * Turns lyrics into display rows. For synced lyrics, an intro of [MIN_INTRO_MS] or more before the
 * first line and every blank line become breaks that run until the next line starts.
 */
fun Lyrics.rows(): List<LyricRow> {
    if (!synced) return lines.map { LyricRow(null, null, it.value, isBreak = false) }
    val out = ArrayList<LyricRow>()
    val firstStart = lines.firstOrNull { it.value.isNotBlank() }?.start
    if (firstStart != null && firstStart >= MIN_INTRO_MS) out += LyricRow(0, firstStart, "", isBreak = true)
    lines.forEachIndexed { i, line ->
        val nextStart = lines.drop(i + 1).firstOrNull { it.start != null }?.start
        if (line.value.isBlank()) {
            if (out.lastOrNull()?.isBreak != true && line.start != null) {
                out += LyricRow(line.start, nextStart ?: (line.start + 5_000), "", isBreak = true)
            }
        } else {
            out += LyricRow(line.start, nextStart, line.value, isBreak = false)
        }
    }
    return out
}

/** Index of the row playing at [positionMs] (the last row that has started), or -1. */
fun List<LyricRow>.currentIndex(positionMs: Long): Int {
    var idx = -1
    for (i in indices) {
        val s = this[i].start ?: return -1
        if (s <= positionMs) idx = i else break
    }
    return idx
}

const val MIN_INTRO_MS = 3_000L

@Entity(tableName = "lyrics")
data class LyricsEntity(@PrimaryKey val songId: String, val json: String, val fetchedAt: Long)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun get(songId: String): LyricsEntity?

    @Upsert
    suspend fun put(item: LyricsEntity)
}

/**
 * Lyrics from Navidrome (embedded tags or .lrc / .txt files next to the music), kept in Room so
 * they also work offline. "No lyrics" is remembered for a week so we don't keep asking.
 */
class LyricsRepository(private val api: SubsonicClient, private val dao: LyricsDao) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Null means unknown (offline and never fetched). */
    suspend fun get(song: SongEntity, online: Boolean): Lyrics? {
        val cached = dao.get(song.id)?.let { e ->
            runCatching { json.decodeFromString(Lyrics.serializer(), e.json) }.getOrNull()?.let { it to e.fetchedAt }
        }
        if (cached != null) {
            val (lyrics, at) = cached
            val age = System.currentTimeMillis() - at
            val stale = age > (if (lyrics.isEmpty) WEEK else MONTH)
            if (!stale || !online) return lyrics
        }
        if (!online) return null
        return runCatching { fetch(song) }.getOrNull()?.also { store(song.id, it) } ?: cached?.first
    }

    suspend fun prefetch(song: SongEntity) {
        if (dao.get(song.id) != null) return
        runCatching { fetch(song) }.getOrNull()?.let { store(song.id, it) }
    }

    private suspend fun fetch(song: SongEntity): Lyrics {
        val structured = runCatching { api.lyricsBySongId(song.id) }.getOrDefault(emptyList())
            .filter { l -> l.line.any { it.value.isNotBlank() } }
        val best = structured.firstOrNull { it.synced } ?: structured.firstOrNull()
        if (best != null) {
            val lines = if (best.synced) best.line.map { it.copy(start = it.start?.plus(best.offset)) } else best.line
            return Lyrics(best.synced, lines)
        }
        val plain = runCatching { api.lyricsByName(song.artist, song.title) }.getOrNull()
        return Lyrics(false, plain?.lines()?.map { LyricLine(null, it) }.orEmpty())
    }

    private suspend fun store(songId: String, lyrics: Lyrics) {
        dao.put(LyricsEntity(songId, json.encodeToString(Lyrics.serializer(), lyrics), System.currentTimeMillis()))
    }

    companion object {
        private const val WEEK = 7 * 24 * 60 * 60 * 1000L
        private const val MONTH = 30 * 24 * 60 * 60 * 1000L
    }
}
