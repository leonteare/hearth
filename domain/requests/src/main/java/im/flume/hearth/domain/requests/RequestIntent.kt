package im.flume.hearth.domain.requests

import im.flume.hearth.core.network.TidalType
import im.flume.hearth.core.data.RequestEntity
import im.flume.hearth.core.data.RequestStatus

/**
 * What the user wants done with requested music once it's on the server: like it (save an album),
 * download it to this phone, or neither (just have it on the server). Pure, for testing.
 */
object RequestIntent {
    /** The request with [like] and [toPhone] added to what it already wanted. */
    fun merge(r: RequestEntity, like: Boolean, toPhone: Boolean): RequestEntity =
        r.copy(like = r.like || like, toPhone = r.toPhone || toPhone)

    /**
     * The request once [dropLike] / [dropToPhone] are taken away, or null when nothing is left for
     * it to do and it should be cancelled. A request that only wanted one of them stops altogether.
     */
    fun without(r: RequestEntity, dropLike: Boolean = false, dropToPhone: Boolean = false): RequestEntity? {
        val like = r.like && !dropLike
        val toPhone = r.toPhone && !dropToPhone
        return if (!like && !toPhone) null else r.copy(like = like, toPhone = toPhone)
    }

    /** What arriving music sets off, beyond adding it to the library. */
    enum class Action { LIKE_SONGS, SAVE_ALBUM, DOWNLOAD_SONGS, DOWNLOAD_ALBUM, FOLLOW_ARTIST }

    fun onArrival(type: TidalType, like: Boolean, toPhone: Boolean): Set<Action> = when (type) {
        TidalType.TRACK -> buildSet {
            if (like) add(Action.LIKE_SONGS)
            if (toPhone) add(Action.DOWNLOAD_SONGS)
        }
        TidalType.ALBUM -> buildSet {
            if (like) add(Action.SAVE_ALBUM)
            if (toPhone) add(Action.DOWNLOAD_ALBUM)
        }
        TidalType.ARTIST -> setOf(Action.FOLLOW_ARTIST)
    }

    /** Something that just arrived, for its notification. */
    data class Arrival(
        val type: TidalType, val title: String, val artist: String, val albumTitle: String?,
        val like: Boolean, val toPhone: Boolean, val route: String?, val key: String,
    )

    data class Note(val title: String, val text: String?, val route: String?, val key: String)

    /**
     * One notification per arrival, except songs that only went onto the server: several from the
     * same album (e.g. the rest of an album) share one, "N songs added to <album>".
     */
    fun notifications(arrivals: List<Arrival>): List<Note> {
        val serverOnlySongs = arrivals.filter { it.type == TidalType.TRACK && !it.like && !it.toPhone && it.albumTitle != null }
        val grouped = serverOnlySongs.groupBy { it.albumTitle!! }.filterValues { it.size > 1 }
        val inGroups = grouped.values.flatten().toSet()
        val single = arrivals.filterNot { it in inGroups }.map { a ->
            val title = when {
                a.type == TidalType.ARTIST -> "Added to the server: ${a.title}"
                a.toPhone -> "Added to your phone: ${a.title}"
                a.like && a.type == TidalType.ALBUM -> "Saved to Your Library: ${a.title}"
                a.like -> "Added to Liked Songs: ${a.title}"
                else -> "Now on your server: ${a.title}"
            }
            Note(title, a.artist.takeIf { a.type != TidalType.ARTIST }, a.route, a.key)
        }
        val groups = grouped.map { (album, items) ->
            Note("${items.size} songs added to $album", items.first().artist, items.first().route, "album-songs:$album")
        }
        return single + groups
    }

    /** What's happening to something on its way, by what it'll do when it arrives; null when nothing is. */
    fun comingLabel(r: RequestEntity?): String? = when {
        r == null -> null
        r.status == RequestStatus.FAILED -> "Couldn't get it"
        !r.status.active -> null
        r.type == TidalType.ARTIST.api -> when (r.status) {
            RequestStatus.DOWNLOADING -> "Downloading…"
            RequestStatus.ADDING -> "Adding…"
            else -> "Coming to your server…"
        }
        r.toPhone && r.like -> "Coming to your phone · ${likedWord(r)}"
        r.toPhone -> "Coming to your phone"
        r.like -> "Coming · ${likedWord(r)}"
        else -> "Coming to your server"
    }

    /** "Coming to your server · will be liked · will download", for menus. */
    fun summary(r: RequestEntity): String = listOfNotNull(
        "Coming to your server",
        likedWord(r).takeIf { r.like && r.type != TidalType.ARTIST.api },
        "will download".takeIf { r.toPhone && r.type != TidalType.ARTIST.api },
    ).joinToString(" · ")

    private fun likedWord(r: RequestEntity) = if (r.type == TidalType.ALBUM.api) "will be saved" else "will be liked"

    /** Where it is on the way to the server (the Downloads page). */
    fun stageLabel(r: RequestEntity): String? = when (r.status) {
        RequestStatus.REQUESTED -> "Waiting"
        RequestStatus.DOWNLOADING -> "Downloading to your server…"
        RequestStatus.ADDING -> "Adding…"
        RequestStatus.FAILED -> "Couldn't get it"
        else -> null
    }
}
