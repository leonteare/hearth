package im.flume.hearth.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** One counted play, joined with the song's details. */
data class HistoryRow(
    val songId: String,
    val playedAt: Long,
    val title: String,
    val artist: String,
    val artistId: String?,
    val album: String,
    val albumId: String?,
    val durationSec: Int,
    val coverArt: String?,
)

data class Ranked(val id: String, val name: String, val detail: String, val coverArt: String?, val plays: Int)

data class Stats(
    val minutes: Long,
    val plays: Int,
    val songCount: Int,
    val artistCount: Int,
    val topArtists: List<Ranked>,
    val topSongs: List<Ranked>,
    val topAlbums: List<Ranked>,
    /** Minutes listened per month, January first (only meaningful for a year). */
    val minutesByMonth: List<Long>,
    val busiestDay: DayOfWeek?,
    /** Most-played songs, best first, for "Play your top songs". */
    val topSongIds: List<String>,
)

/** A month ("2026-09") or a whole year ("2026"). */
data class StatsPeriod(val key: String) {
    val isYear: Boolean get() = !key.contains('-')
    private val zone get() = ZoneId.systemDefault()

    val range: LongRange
        get() {
            val (start, end) = if (isYear) {
                LocalDate.of(key.toInt(), 1, 1) to LocalDate.of(key.toInt() + 1, 1, 1)
            } else {
                val ym = YearMonth.parse(key)
                ym.atDay(1) to ym.plusMonths(1).atDay(1)
            }
            return start.atStartOfDay(zone).toInstant().toEpochMilli() until end.atStartOfDay(zone).toInstant().toEpochMilli()
        }

    val title: String
        get() = if (isYear) "Your $key" else "Your ${YearMonth.parse(key).month.getDisplayName(TextStyle.FULL, Locale.getDefault())}"

    companion object {
        /** Last month's stats all month; from 1 December (and through January) the year too. */
        fun current(today: LocalDate = LocalDate.now()): List<StatsPeriod> = buildList {
            when (today.monthValue) {
                12 -> add(StatsPeriod(today.year.toString()))
                1 -> add(StatsPeriod((today.year - 1).toString()))
            }
            add(StatsPeriod(YearMonth.from(today).minusMonths(1).toString()))
        }
    }
}

object StatsCalculator {
    /** A song played more than this many times in a row (e.g. left on repeat) only counts this many. */
    const val MAX_IN_A_ROW = 3

    fun summarize(rows: List<HistoryRow>, zone: ZoneId = ZoneId.systemDefault()): Stats {
        val counted = ArrayList<HistoryRow>(rows.size)
        var run = 0
        var previous: String? = null
        for (r in rows.sortedBy { it.playedAt }) {
            run = if (r.songId == previous) run + 1 else 1
            previous = r.songId
            if (run <= MAX_IN_A_ROW) counted += r
        }

        fun <K> rank(key: (HistoryRow) -> K?, make: (K, List<HistoryRow>) -> Ranked) =
            counted.filter { key(it) != null }.groupBy { key(it)!! }.entries
                .sortedByDescending { it.value.size }.take(5).map { (k, v) -> make(k, v) }

        val months = LongArray(12)
        val days = IntArray(7)
        counted.forEach {
            val date = Instant.ofEpochMilli(it.playedAt).atZone(zone)
            months[date.monthValue - 1] += it.durationSec / 60L
            days[date.dayOfWeek.value - 1]++
        }
        val songsByPlays = counted.groupBy { it.songId }.entries.sortedByDescending { it.value.size }

        return Stats(
            minutes = counted.sumOf { it.durationSec.toLong() } / 60,
            plays = counted.size,
            songCount = counted.map { it.songId }.distinct().size,
            artistCount = counted.map { it.artistId ?: it.artist }.distinct().size,
            topArtists = rank({ it.artistId }) { id, v -> Ranked(id, v[0].artist, "${v.size} plays", v.maxByOrNull { it.playedAt }?.coverArt, v.size) },
            topSongs = songsByPlays.take(5).map { (id, v) -> Ranked(id, v[0].title, v[0].artist, v[0].coverArt, v.size) },
            topAlbums = rank({ it.albumId }) { id, v -> Ranked(id, v[0].album, v[0].artist, v[0].coverArt, v.size) },
            minutesByMonth = months.toList(),
            busiestDay = days.indices.maxByOrNull { days[it] }?.takeIf { days[it] > 0 }?.let { DayOfWeek.of(it + 1) },
            topSongIds = songsByPlays.take(50).map { it.key },
        )
    }
}
