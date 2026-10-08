package im.flume.hearth.data

import android.content.Context
import androidx.core.content.edit
import im.flume.hearth.api.SubsonicClient
import im.flume.hearth.playback.QueuePlanner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.random.Random

@Serializable
data class Mix(val title: String, val subtitle: String, val songIds: List<String>, val covers: List<String?>)

/**
 * Daily mixes for whoever is signed in on this phone, built from their own play counts (Navidrome
 * keeps these per user) plus Hearth's play history. Each mix groups a few favourite artists that
 * sound alike, mixes their most-played songs with ones rarely played, and adds similar artists.
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

    suspend fun today(online: Boolean): List<Mix> {
        val key = "${session.credentials.value?.username}:${LocalDate.now()}"
        if (prefs.getString("key", null) == key) {
            runCatching { json.decodeFromString<List<Mix>>(prefs.getString("mixes", "[]")!!) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val mixes = build(Random(key.hashCode()), online)
        if (mixes.isNotEmpty()) {
            prefs.edit { putString("key", key); putString("mixes", json.encodeToString(mixes)) }
        }
        return mixes
    }

    private suspend fun build(random: Random, online: Boolean): List<Mix> {
        val dao = db.library()
        val all = dao.allSongsOnce()
        if (all.isEmpty()) return emptyList()
        val recent = dao.recentHistorySongIds(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000).groupingBy { it }.eachCount()
        val byArtist = all.filter { it.artistId != null }.groupBy { it.artistId!! }

        // Favourite artists: lifetime plays, with recent listening in Hearth counting extra.
        val score = byArtist.mapValues { (_, songs) -> songs.sumOf { it.playCount + 3L * (recent[it.id] ?: 0) } }
        val top = score.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(MAX_ARTISTS).map { it.key }
        if (top.size < 2) return emptyList()

        val similar: Map<String, Set<String>> = if (online) top.associateWith { id ->
            runCatching { api.artistInfo(id)?.similarArtist.orEmpty().map { it.id }.toSet() }.getOrDefault(emptySet())
        } else emptyMap()
        val groups = groupArtists(top, similar)

        return groups.mapIndexedNotNull { i, group ->
            val ids = buildList {
                for (artistId in group) {
                    val songs = byArtist[artistId].orEmpty()
                    val favourites = songs.sortedByDescending { it.playCount + 3L * (recent[it.id] ?: 0) }.take(6)
                    val rare = (songs - favourites.toSet()).sortedBy { it.playCount }.take(12).shuffled(random).take(4)
                    addAll(favourites + rare)
                }
                // A few songs from similar artists that you have but haven't been playing much.
                val extra = similar.filterKeys { it in group }.values.flatten().toSet() - top.toSet()
                addAll(extra.flatMap { byArtist[it].orEmpty() }.shuffled(random).take(12))
            }.distinctBy { it.id }
            if (ids.size < 10) return@mapIndexedNotNull null
            val artistOf = ids.associate { it.id to it.artistId }
            val order = QueuePlanner.spreadArtists(ids.map { it.id }.shuffled(random), artistOf::get).take(MIX_SIZE)
            val names = group.mapNotNull { a -> byArtist[a]?.firstOrNull()?.artist }
            Mix(
                title = "Mix ${i + 1}",
                subtitle = when (names.size) {
                    1 -> names[0]
                    2 -> "${names[0]} and ${names[1]}"
                    else -> "${names[0]}, ${names[1]} and more"
                },
                songIds = order,
                covers = group.take(4).map { a -> byArtist[a]?.maxByOrNull { it.playCount }?.coverArt },
            )
        }
    }

    companion object {
        private const val MAX_ARTISTS = 12
        private const val MIX_SIZE = 50
        private const val MIXES = 3

        /**
         * Splits favourite artists (best first) into up to three groups. Each group starts from the
         * highest-ranked artist not yet used and pulls in artists Last.fm calls similar; anything
         * left over is dealt out in rank order so every mix gets a share.
         */
        fun groupArtists(top: List<String>, similar: Map<String, Set<String>>): List<List<String>> {
            val per = (top.size + MIXES - 1) / MIXES
            val remaining = top.toMutableList()
            val groups = mutableListOf<MutableList<String>>()
            while (remaining.isNotEmpty() && groups.size < MIXES) {
                val seed = remaining.removeAt(0)
                val group = mutableListOf(seed)
                val near = remaining.filter { it in similar[seed].orEmpty() || seed in similar[it].orEmpty() }
                for (a in near) if (group.size < per) { group += a; remaining -= a }
                groups += group
            }
            var i = 0
            for (a in remaining) { groups[i % groups.size] += a; i++ }
            return groups
        }
    }
}
