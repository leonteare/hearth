package im.flume.hearth.data

import im.flume.hearth.api.TidalAlbum
import im.flume.hearth.api.TidalSearchResults
import im.flume.hearth.api.TidalTrack
import im.flume.hearth.api.TidarrQueueItem
import im.flume.hearth.data.SearchIndex.Companion.norm

/**
 * Deciding whether Tidal music is already on the server, and finding it there once Tidarr has
 * fetched it. Pure functions; no Android or network, so all of it is unit tested.
 */
object RequestMatching {
    private val bracketed = Regex("""\s*[(\[][^)\]]*[)\]]""")

    /** Title without bracketed extras or a " - Remastered" style tail: "Song (2001 Remaster)" → "song". */
    fun baseTitle(title: String): String = norm(title.replace(bracketed, "").substringBefore(" - "))

    /** Title to search the server with: bracketed extras dropped, since the files may not carry them. */
    fun searchTitle(title: String): String =
        title.replace(bracketed, "").substringBefore(" - ").trim().ifEmpty { title }

    /** Splits ISRCs stored comma-separated on a song. */
    fun isrcs(field: String?): List<String> =
        field?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }.orEmpty()

    /**
     * True when one of the Tidal artists is the local artist, or one of the artists in a combined
     * local credit ("A feat. B", "A & B"). Whole words only, so "Muse" doesn't match "Museum".
     */
    fun artistMatches(tidalArtists: List<String>, localArtist: String): Boolean {
        val local = creditParts(localArtist)
        if (local.isEmpty()) return false
        return tidalArtists.flatMap(::creditParts).any { it in local }
    }

    private val creditSeparators = Regex("""\s*(?:[,;/&]|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\band\b|\bx\b|\bvs\.?)\s*""", RegexOption.IGNORE_CASE)

    /** The whole credit plus each artist in it, normalised: "Daft Punk feat. Pharrell" → {daft punk feat pharrell, daft punk, pharrell}. */
    private fun creditParts(credit: String): Set<String> =
        (listOf(credit) + credit.split(creditSeparators)).map(::norm).filter { it.isNotBlank() }.toSet()

    /** Same song title: exactly (after normalising), or when both titles reduce to the same base title. */
    fun titleMatches(tidalTitle: String, localTitle: String, loose: Boolean): Boolean =
        norm(tidalTitle) == norm(localTitle) || (loose && baseTitle(tidalTitle).let { it.isNotEmpty() && it == baseTitle(localTitle) })

    /** What's already on the server, for hiding Tidal results you don't need to request. */
    class LocalCatalog(songs: List<SongEntity>, albums: List<AlbumEntity>, artists: List<ArtistEntity> = emptyList()) {
        private val byIsrc: Map<String, SongEntity> = HashMap<String, SongEntity>().apply {
            songs.forEach { s -> isrcs(s.isrc).forEach { putIfAbsent(it, s) } }
        }
        private val songsByTitle: Map<String, List<SongEntity>> = songs.groupBy { norm(it.title) }
        private val albumsByName: Map<String, List<AlbumEntity>> = albums.groupBy { norm(it.name) }
        private val artistsByName: Map<String, ArtistEntity> = artists.associateBy { norm(it.name) }

        /** The server's copy of [t]: by ISRC when both sides have one, otherwise exact normalised title plus a matching artist. */
        fun localTrack(t: TidalTrack): SongEntity? {
            t.isrc?.uppercase()?.let { byIsrc[it] }?.let { return it }
            // A "feat. X" version is a credit, not a different recording: the plain title counts too.
            val featuring = t.version?.trim()?.lowercase()?.let { it.startsWith("feat") || it.startsWith("with ") } == true
            val titles = listOfNotNull(norm(t.displayTitle), norm(t.title).takeIf { featuring })
            return titles.firstNotNullOfOrNull { title -> songsByTitle[title].orEmpty().firstOrNull { artistMatches(t.artists, it.artist) } }
        }

        fun hasTrack(t: TidalTrack): Boolean = localTrack(t) != null

        fun localAlbum(a: TidalAlbum): AlbumEntity? =
            albumsByName[norm(a.title)].orEmpty().firstOrNull { artistMatches(a.artists, it.artist) }

        fun hasAlbum(a: TidalAlbum): Boolean = localAlbum(a) != null

        /** The server's artist with the same (normalised) name. */
        fun localArtist(name: String): ArtistEntity? = artistsByName[norm(name)]

        /** Drops songs and albums already on the server; artists are kept (a discography may be incomplete). */
        fun filter(r: TidalSearchResults): TidalSearchResults =
            r.copy(tracks = r.tracks.filterNot(::hasTrack), albums = r.albums.filterNot(::hasAlbum))
    }

    /**
     * The server song a requested track became: an ISRC match wins; otherwise the title and artist must
     * match and, when both lengths are known, the length must be within 3 seconds.
     */
    fun pickTrack(candidates: List<SongEntity>, title: String, artist: String, isrc: String?, durationSec: Int?): SongEntity? {
        isrc?.uppercase()?.let { code -> candidates.firstOrNull { code in isrcs(it.isrc) }?.let { return it } }
        val artists = splitArtists(artist)
        fun lengthOk(s: SongEntity) = durationSec == null || durationSec <= 0 || s.durationSec <= 0 || kotlin.math.abs(s.durationSec - durationSec) <= 3
        val candidatesByArtist = candidates.filter { artistMatches(artists, it.artist) && lengthOk(it) }
        return candidatesByArtist.firstOrNull { titleMatches(title, it.title, loose = false) }
            ?: candidatesByArtist.firstOrNull { titleMatches(title, it.title, loose = true) }
    }

    /** The server album a requested album became: same normalised name (or base name) and artist. */
    fun pickAlbum(candidates: List<AlbumEntity>, title: String, artist: String): AlbumEntity? {
        val artists = splitArtists(artist)
        val byArtist = candidates.filter { artistMatches(artists, it.artist) }
        return byArtist.firstOrNull { norm(it.name) == norm(title) }
            ?: byArtist.firstOrNull { baseTitle(it.name).let { b -> b.isNotEmpty() && b == baseTitle(title) } }
    }

    fun pickArtist(candidates: List<ArtistEntity>, name: String): ArtistEntity? =
        candidates.firstOrNull { norm(it.name) == norm(name) }

    /** Requests store Tidal's artists joined with ", ". */
    fun splitArtists(artist: String): List<String> = artist.split(", ").filter { it.isNotBlank() }.ifEmpty { listOf(artist) }
}

/** How a request moves along as Tidarr's queue changes. Pure, for testing. */
object RequestTracking {
    /** Tidarr's queue status → ours. Unknown words leave the request where it is (null). */
    fun mapStatus(tidarrStatus: String?, error: Boolean = false): RequestStatus? = when {
        error -> RequestStatus.FAILED
        else -> when (tidarrStatus?.lowercase()) {
            "queue_download" -> RequestStatus.REQUESTED
            "download", "queue_processing", "processing" -> RequestStatus.DOWNLOADING
            "finished" -> RequestStatus.ADDING
            "error" -> RequestStatus.FAILED
            else -> null
        }
    }

    /** A request that just went in is given this long to show up in the queue before its absence means "done". */
    const val QUEUE_GRACE_MS = 60_000L

    const val FAILED_MESSAGE = "Tidarr couldn't download this — is the Tidal subscription active?"

    /** What requests that never turned up on the server used to be marked with (as done). */
    const val NOT_MATCHED = "Added to the server — couldn't match it automatically"

    /** Tidarr says "finished" even when tiddl fetched nothing (e.g. a free Tidal account), so this is a failure. */
    const val NOTHING_ARRIVED = "Tidarr finished, but nothing arrived on the server. Retry, or check Tidarr's page."

    /** Waiting this long after Tidarr finished, with nothing found, means nothing is coming. */
    const val ADDING_TIMEOUT_MS = 30 * 60_000L

    /** How a retry gets Tidarr to really download again. */
    enum class RetryAction {
        /** Not in Tidarr's queue: add it. */
        SAVE,
        /** Failed there: Tidarr retries its failed items. */
        RETRY_FAILED,
        /** Listed with any other status (usually "finished" with nothing downloaded): take it out and add it again. */
        REMOVE_AND_SAVE,
    }

    fun retryAction(item: TidarrQueueItem?): RetryAction = when {
        item == null -> RetryAction.SAVE
        item.error || item.status.equals("error", ignoreCase = true) -> RetryAction.RETRY_FAILED
        else -> RetryAction.REMOVE_AND_SAVE
    }

    /** The request once it has been sent again: back to the start of its journey. */
    fun afterRetry(r: RequestEntity, action: RetryAction, now: Long): RequestEntity = r.copy(
        status = RequestStatus.REQUESTED, errorMessage = null, matchedIds = null, updatedAt = now,
        // A retried failed item is still listed; a re-added one has to show up first.
        seenInQueue = action == RetryAction.RETRY_FAILED, finishedAt = null, lookupAttempts = 0,
    )

    /** Tidarr finished but nothing turned up on the server. */
    fun gaveUp(r: RequestEntity, now: Long): RequestEntity =
        r.copy(status = RequestStatus.FAILED, errorMessage = NOTHING_ARRIVED, updatedAt = now)

    /** Rows from before this was treated as a failure (done, "couldn't match"), as they should be; null if fine. */
    fun reclassified(r: RequestEntity): RequestEntity? =
        if (r.status == RequestStatus.DONE && r.errorMessage == NOT_MATCHED) r.copy(status = RequestStatus.FAILED, errorMessage = NOTHING_ARRIVED) else null

    /** True when Tidarr finished over [ADDING_TIMEOUT_MS] ago and the request is still waiting for the server. */
    fun addingTimedOut(r: RequestEntity, now: Long): Boolean =
        r.status == RequestStatus.ADDING && now - (r.finishedAt ?: r.updatedAt) > ADDING_TIMEOUT_MS

    /**
     * The request after looking at Tidarr's queue ([item] is its row there, or null if it isn't
     * listed). Only REQUESTED/DOWNLOADING requests change here; ADDING is handled by the lookup.
     * An item that was seen and then vanished finished (or was cleared), so it's looked for on the server.
     */
    fun afterQueue(r: RequestEntity, item: TidarrQueueItem?, now: Long): RequestEntity {
        if (r.status != RequestStatus.REQUESTED && r.status != RequestStatus.DOWNLOADING) return r
        val next = if (item != null) {
            mapStatus(item.status, item.error) ?: r.status
        } else if (r.seenInQueue || now - r.updatedAt > QUEUE_GRACE_MS) {
            RequestStatus.ADDING
        } else r.status
        val seen = r.seenInQueue || item != null
        if (next == r.status && seen == r.seenInQueue) return r
        return r.copy(
            status = next,
            seenInQueue = seen,
            updatedAt = now,
            finishedAt = if (next == RequestStatus.ADDING) now else r.finishedAt,
            lookupAttempts = if (next == RequestStatus.ADDING) 0 else r.lookupAttempts,
            errorMessage = if (next == RequestStatus.FAILED) FAILED_MESSAGE + (item?.errorStage?.let { " (failed while $it)" } ?: "") else null,
        )
    }

    /** Minutes after Tidarr finished at which the server is searched again; the last one gives up. */
    private val LOOKUP_SCHEDULE_MS = listOf(0L, 1, 3, 6, 10, 15).map { it * 60_000L }

    /** True when it's time for another look on the server. */
    fun lookupDue(r: RequestEntity, now: Long): Boolean {
        val finished = r.finishedAt ?: return true
        val wait = LOOKUP_SCHEDULE_MS.getOrElse(r.lookupAttempts) { return false }
        return now - finished >= wait
    }

    /** True when the last scheduled look has been made without finding it. */
    fun lookupExhausted(r: RequestEntity): Boolean = r.lookupAttempts >= LOOKUP_SCHEDULE_MS.size
}
