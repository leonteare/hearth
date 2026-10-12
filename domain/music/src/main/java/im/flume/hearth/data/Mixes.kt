package im.flume.hearth.data

import android.content.Context
import androidx.core.content.edit
import im.flume.hearth.api.SubsonicClient
import im.flume.hearth.playback.QueuePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.random.Random

@Serializable
data class Mix(val title: String, val subtitle: String, val songIds: List<String>, val covers: List<String?>)

/**
 * Daily mixes for whoever is signed in on this phone, built from their own play counts (Navidrome
 * keeps these per user) plus Hearth's play history. Each mix is built around one favourite artist:
 * their most-played songs, a few rarely played ones, and songs by similar artists in your library.
 * Rebuilt once a day; the same day always gives the same mixes.
 */
class MixRepository(
    context: Context,
    private val db: AppDatabase,
    private val api: SubsonicClient,
    private val session: SessionStore,
) {
    private val prefs = context.getSharedPreferences("mixes", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Safe to call from the main thread: storage and the CPU-heavy building run on background threads. */
    suspend fun today(online: Boolean): List<Mix> {
        val key = "v3:${session.credentials.value?.username}:${LocalDate.now()}"
        val cached = withContext(Dispatchers.IO) {
            if (prefs.getString("key", null) != key) return@withContext null
            runCatching { json.decodeFromString<List<Mix>>(prefs.getString("mixes", "[]")!!) }.getOrNull()
        }
        cached?.takeIf { it.isNotEmpty() }?.let { return it }
        // Groups and sorts the whole library; database and server calls inside switch threads themselves.
        val mixes = withContext(Dispatchers.Default) { build(Random(key.hashCode()), online) }
        if (mixes.isNotEmpty()) {
            withContext(Dispatchers.IO) { prefs.edit { putString("key", key); putString("mixes", json.encodeToString(mixes)) } }
        }
        return mixes
    }

    private suspend fun build(random: Random, online: Boolean): List<Mix> {
        val dao = db.library()
        val all = dao.allSongsOnce()
        if (all.isEmpty()) return emptyList()
        val recent = dao.recentHistorySongIds(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000).groupingBy { it }.eachCount()
        val byArtist = all.filter { it.artistId != null }.groupBy { it.artistId!! }
        val byId = all.associateBy { it.id }
        fun weight(song: SongEntity) = song.playCount + 3L * (recent[song.id] ?: 0)

        // Favourite artists: lifetime plays, with recent listening in Hearth counting extra.
        val top = byArtist.mapValues { (_, songs) -> songs.sumOf(::weight) }
            .entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(MAX_ARTISTS).map { it.key }
        if (top.isEmpty()) return emptyList()

        val similar: Map<String, Set<String>> = if (online) top.associateWith { id ->
            runCatching { api.artistInfo(id)?.similarArtist.orEmpty().map { it.id }.filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
        } else emptyMap()

        return pickSeeds(top, similar).mapNotNull { seed ->
            val seedSongs = byArtist[seed].orEmpty()
            val favourites = seedSongs.sortedByDescending(::weight).take(12)
            val rare = (seedSongs - favourites.toSet()).shuffled(random).take(4)
            // Navidrome's own pick of songs by similar artists (via Last.fm), limited to your library.
            val alike = if (online) runCatching { api.similarSongsForArtist(seed, 60) }.getOrDefault(emptyList())
                .mapNotNull { byId[it.id] }
                // Only artists Last.fm actually lists as similar; without that data Navidrome can return unrelated songs.
                .filter { it.artistId != seed && it.artistId in similar[seed].orEmpty() } else emptyList()
            // Plus favourites from your other top artists that Last.fm says sound similar.
            val related = top.filter { it != seed && (it in similar[seed].orEmpty() || seed in similar[it].orEmpty()) }
                .flatMap { a -> byArtist[a].orEmpty().sortedByDescending(::weight).take(5) }
            val songs = (favourites + rare + related + alike.shuffled(random).take(30)).distinctBy { it.id }
            if (songs.size < 10) return@mapNotNull null

            val artistOf = songs.associate { it.id to it.artistId }
            val order = QueuePlanner.spreadArtists(songs.map { it.id }.shuffled(random), artistOf::get).take(MIX_SIZE)
            val seedName = seedSongs.firstOrNull()?.artist ?: return@mapNotNull null
            val others = order.mapNotNull { byId[it] }.filter { it.artistId != seed }
                .groupingBy { it.artist }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(2)
            val coverArtists = listOf(seed) + others.mapNotNull { name -> byArtist.entries.firstOrNull { it.value.first().artist == name }?.key }
            Mix(
                title = "$seedName Mix",
                subtitle = if (others.isEmpty()) seedName else "$seedName, ${others.joinToString(", ")} and more",
                songIds = order,
                covers = (coverArtists.map { a -> byArtist[a]?.maxByOrNull(::weight)?.coverArt } +
                    favourites.drop(1).map { it.coverArt }).distinct().take(4),
            )
        }
    }

    companion object {
        private const val MAX_ARTISTS = 15
        private const val MIX_SIZE = 50
        private const val MIXES = 3

        /**
         * Chooses up to three favourite artists to build mixes around, best first, skipping any that
         * Last.fm considers similar to one already chosen, so each mix has its own sound.
         */
        fun pickSeeds(top: List<String>, similar: Map<String, Set<String>>): List<String> {
            val seeds = mutableListOf<String>()
            for (a in top) {
                if (seeds.size == MIXES) break
                val clash = seeds.any { s -> a in similar[s].orEmpty() || s in similar[a].orEmpty() }
                if (!clash) seeds += a
            }
            return seeds
        }
    }
}
