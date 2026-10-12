package im.flume.hearth.spoken

import im.flume.hearth.api.AbsFeedResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowFeedTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    /** A real POST /api/podcasts/feed answer (Up First, trimmed), plus a repeated episode and one without audio. */
    private fun fixture() = json.decodeFromString(AbsFeedResponse.serializer(), javaClass.getResource("/abs/feed-up-first.json")!!.readText()).podcast

    private fun feedEp(title: String, at: Long, url: String = "https://cdn.example/$title.mp3", guid: String? = "guid-$title", season: String? = null) =
        FeedEpisode(title = title, publishedAt = at, durationSec = 600.0, enclosureUrl = url, guid = guid, season = season)

    private fun serverRow(
        id: String, title: String, at: Long, url: String? = null, guid: String? = null,
        progress: Double? = null, finished: Boolean = false, download: SpokenDownloadState? = null, season: String? = null,
    ) = EpisodeRow(
        EpisodeEntity(id, "pod", title, null, null, at, 600.0, 0, "ino-$id", "audio/mpeg", season, null, null, at, url, guid),
        "Show", null, progress, finished, null, download, null,
    )

    // ------------------------------------------------------------------ parsing

    @Test
    fun `parses a captured feed, skipping repeats and episodes without audio`() {
        val feed = fixture()
        assertEquals("Up First from NPR", feed.metadata.title)
        val parsed = FeedParse.episodes(feed.episodes)
        assertEquals(4, parsed.size)
        val first = parsed[0].first
        assertEquals("The women who fought back when their jailers assaulted them", first.title)
        assertEquals(1791702000000L, first.publishedAt)
        assertEquals(1696.0, first.durationSec, 0.0)
        assertEquals("99895a21-c51a-4eab-8a41-cb462d41842d", first.guid)
        assertEquals("audio/mpeg", first.enclosureType)
        assertEquals("27145282", first.enclosureLength)
        assertTrue(first.enclosureUrl.startsWith("https://"))
        assertEquals("g:99895a21-c51a-4eab-8a41-cb462d41842d", first.key)
        // The original object goes back to the server untouched when asking for the episode.
        assertEquals(first.title, parsed[0].second["title"]!!.jsonPrimitive.content)
        assertEquals(parsed.size, parsed.map { it.first.key }.toSet().size)
    }

    @Test
    fun `durations in any iTunes form`() {
        assertEquals(1696.0, FeedParse.durationSeconds("1696"), 0.0)
        assertEquals(28 * 60 + 16.0, FeedParse.durationSeconds("28:16"), 0.0)
        assertEquals(3723.0, FeedParse.durationSeconds("1:02:03"), 0.0)
        assertEquals(0.0, FeedParse.durationSeconds(""), 0.0)
        assertEquals(0.0, FeedParse.durationSeconds("about an hour"), 0.0)
    }

    @Test
    fun `the request body for a light episode has the feed's shape`() {
        val body = feedEp("A", 1000).copy(enclosureType = "audio/mpeg", enclosureLength = "123").toRequestJson()
        assertEquals("A", body["title"]!!.jsonPrimitive.content)
        assertEquals("https://cdn.example/A.mp3", body["enclosure"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("123", body["enclosure"]!!.jsonObject["length"]!!.jsonPrimitive.content)
        assertEquals("guid-A", body["guid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `episodes without a guid are keyed by their audio URL`() {
        assertEquals("u:https://cdn.example/A.mp3", feedEp("A", 1, guid = null).key)
    }

    // ------------------------------------------------------------------ matching

    @Test
    fun `matches by audio URL first, then guid, then title and date`() {
        val day = 86_400_000L
        val feed = listOf(
            feedEp("By URL", 10 * day, url = "https://cdn/1.mp3", guid = "g1"),
            feedEp("By guid", 9 * day, url = "https://cdn/2-new.mp3", guid = "g2"),
            feedEp("By Title: Part 2!", 8 * day, url = "https://cdn/3.mp3", guid = "g3"),
            feedEp("Same title, other day", 2 * day, url = "https://cdn/4.mp3", guid = "g4"),
            feedEp("Not there", 1 * day, url = "https://cdn/5.mp3", guid = "g5"),
        )
        val server = listOf(
            ServerEpisodeRef("s1", "https://cdn/1.mp3", "different", "Renamed on server", 10 * day),
            ServerEpisodeRef("s2", "https://cdn/2-old.mp3", "g2", "By guid", 9 * day),
            // Stored before the server kept URL / guid: matched on title (ignoring case and punctuation) and date.
            ServerEpisodeRef("s3", null, null, "by title part 2", 8 * day + 3 * 3_600_000),
            ServerEpisodeRef("s4", null, null, "Same title, other day", 6 * day),
        )
        val m = FeedMatch.match(feed, server)
        assertEquals("s1", m[feed[0].key])
        assertEquals("s2", m[feed[1].key])
        assertEquals("s3", m[feed[2].key])
        assertNull(m[feed[3].key])
        assertNull(m[feed[4].key])
    }

    @Test
    fun `each server episode is matched once`() {
        val feed = listOf(feedEp("Daily", 1000, url = "u1", guid = null), feedEp("Daily", 1000, url = "u2", guid = null))
        val m = FeedMatch.match(feed, listOf(ServerEpisodeRef("s", null, null, "Daily", 1000)))
        assertEquals(1, m.size)
    }

    @Test
    fun `merge keeps server episodes the feed dropped, and a deleted one goes back to the feed row`() {
        val feed = listOf(feedEp("A", 3000, url = "ua"), feedEp("B", 2000, url = "ub"))
        val withServer = ShowEpisodes.merge(feed, listOf(serverRow("sa", "A", 3000, url = "ua"), serverRow("old", "Gone from feed", 100)))
        assertEquals(listOf("g:guid-A", "g:guid-B", "s:old"), withServer.map { it.key })
        assertEquals("sa", withServer[0].server?.episode?.id)
        assertNull(withServer[1].server)
        // The server cleaned "A" up: same key, now just the feed's.
        val after = ShowEpisodes.merge(feed, emptyList())
        assertEquals("g:guid-A", after[0].key)
        assertEquals(ShowEpisodeState.NOT_ON_SERVER, ShowEpisodes.state(after[0]))
    }

    // ------------------------------------------------------------------ states

    @Test
    fun `row states`() {
        val f = feedEp("A", 1)
        fun e(row: EpisodeRow?) = ShowEpisode(f.key, f, row)
        assertEquals(ShowEpisodeState.NOT_ON_SERVER, ShowEpisodes.state(e(null)))
        assertEquals(ShowEpisodeState.ON_SERVER, ShowEpisodes.state(e(serverRow("1", "A", 1))))
        assertEquals(ShowEpisodeState.ON_PHONE, ShowEpisodes.state(e(serverRow("1", "A", 1, download = SpokenDownloadState.DONE))))
        assertEquals(ShowEpisodeState.IN_PROGRESS, ShowEpisodes.state(e(serverRow("1", "A", 1, progress = 30.0, download = SpokenDownloadState.DONE))))
        assertEquals(ShowEpisodeState.PLAYED, ShowEpisodes.state(e(serverRow("1", "A", 1, progress = 600.0, finished = true))))
        val fetch = ServerFetch(f.key, "A", FetchPhase.QUEUED, FetchThen.PLAY, 0, queuePosition = 2)
        assertEquals(ShowEpisodeState.GETTING, ShowEpisodes.state(e(null), fetch))
        assertEquals(ShowEpisodeState.FAILED, ShowEpisodes.state(e(null), fetch.copy(phase = FetchPhase.FAILED)))
        // Once it's on the server, the server's state wins.
        assertEquals(ShowEpisodeState.ON_SERVER, ShowEpisodes.state(e(serverRow("1", "A", 1)), fetch))
    }

    @Test
    fun `fetch progress`() {
        assertEquals(FetchPhase.READY, FetchProgress.phase(onServer = true, queueIndex = 3, elapsedMs = 0))
        assertEquals(FetchPhase.QUEUED, FetchProgress.phase(false, 0, FetchProgress.GIVE_UP_MS + 1))
        assertEquals(FetchPhase.DOWNLOADING, FetchProgress.phase(false, null, 5_000))
        assertEquals(FetchPhase.FAILED, FetchProgress.phase(false, null, FetchProgress.GIVE_UP_MS + 1))
        assertTrue(FetchProgress.pollDelayMs(0) < FetchProgress.pollDelayMs(10 * 60_000))
        val f = ServerFetch("k", "A", FetchPhase.QUEUED, FetchThen.PLAY, 0, queuePosition = 3)
        assertEquals("Getting it… (2 ahead)", FetchProgress.label(f))
        assertEquals("Getting it…", FetchProgress.label(f.copy(queuePosition = 1)))
    }

    // ------------------------------------------------------------------ sort, filter, seasons

    @Test
    fun `sort and filter`() {
        val list = ShowEpisodes.merge(
            listOf(feedEp("Café politics", 3), feedEp("Weather today", 1), feedEp("Politics of weather", 2)), emptyList(),
        )
        assertEquals(listOf("Café politics", "Politics of weather", "Weather today"), ShowEpisodes.arrange(list, "", newestFirst = true).map { it.title })
        assertEquals(listOf("Weather today", "Politics of weather", "Café politics"), ShowEpisodes.arrange(list, "", newestFirst = false).map { it.title })
        assertEquals(listOf("Café politics", "Politics of weather"), ShowEpisodes.arrange(list, "POLITICS", true).map { it.title })
        // Accents, punctuation and word order don't matter; every word must be there.
        assertEquals(listOf("Café politics"), ShowEpisodes.arrange(list, "politics cafe", true).map { it.title })
        assertEquals(listOf("Politics of weather"), ShowEpisodes.arrange(list, "weather, politics", true).map { it.title })
        assertTrue(ShowEpisodes.arrange(list, "sport", true).isEmpty())
    }

    @Test
    fun `season headings only when there are several seasons`() {
        val one = ShowEpisodes.merge(listOf(feedEp("A", 2, season = "1"), feedEp("B", 1, season = "1")), emptyList())
        assertFalse(ShowEpisodes.withSeasons(one).any { it is ShowListEntry.Season })
        val two = ShowEpisodes.sort(
            ShowEpisodes.merge(listOf(feedEp("S1E1", 1, season = "1"), feedEp("S1E2", 2, season = "1"), feedEp("S2E1", 3, season = "2"), feedEp("Bonus", 4)), emptyList()),
            newestFirst = true,
        )
        assertEquals(
            listOf("Bonus", "season:2", "S2E1", "season:1", "S1E2", "S1E1"),
            ShowEpisodes.withSeasons(two).map { if (it is ShowListEntry.Season) it.key else (it as ShowListEntry.Episode).episode.title },
        )
    }
}
