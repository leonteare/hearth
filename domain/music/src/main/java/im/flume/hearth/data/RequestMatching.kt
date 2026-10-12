package im.flume.hearth.data

import im.flume.hearth.api.TidalAlbum
import im.flume.hearth.api.TidalSearchResults
import im.flume.hearth.api.TidalTrack
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
