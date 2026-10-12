// GetMusic: getting music onto the server from the hearts, Download, Add to server and Follow. One per app, provided at the root.
package im.flume.hearth.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import im.flume.hearth.core.playback.AppContainer
import im.flume.hearth.core.network.TidalAlbum
import im.flume.hearth.core.network.TidalArtist
import im.flume.hearth.core.network.TidalArtistPage
import im.flume.hearth.core.network.TidalTrack
import im.flume.hearth.core.network.TidalType
import im.flume.hearth.core.data.RequestEntity
import im.flume.hearth.core.data.RequestStatus
import im.flume.hearth.requests.RequestsRepository
import im.flume.hearth.core.common.couldNot
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private fun RequestEntity.asDraft() = copy(
    status = RequestStatus.REQUESTED, errorMessage = null, matchedIds = null, seenInQueue = false, finishedAt = null, lookupAttempts = 0,
)

/**
 * Getting music onto the server: the hearts, Follow on an artist the server doesn't have, the ⋯ menu
 * of a song that isn't there, and the sheets they open. One per app, provided at the root.
 */
@Stable
class GetMusic(private val c: AppContainer, private val actions: Actions) {
    /** The ⋯ menu of a song that isn't on the server. */
    var menuFor by mutableStateOf<TidalTrack?>(null)
    /** "Get their music?" for an artist just followed. */
    var artistSheet by mutableStateOf<Pair<TidalArtist, TidalArtistPage?>?>(null)
    /** The ⋯ menu of something on its way (Downloads page). */
    var requestMenu by mutableStateOf<RequestEntity?>(null)

    /** Why [what] failed in plain words; with no network at all that's the connection, whatever [e] says. */
    private fun whyFailed(what: String, e: Throwable?) = couldNot(what, e, offline = c.network.cannotStream)

    fun preview(t: TidalTrack) {
        if (!c.network.isOnline.value) { actions.showMessage("Preview unavailable"); return }
        c.preview.toggle(t)
    }

    /** The ⋯ menu of an album that isn't on the server. */
    var albumMenuFor by mutableStateOf<TidalAlbum?>(null)

    /** ♡ on a song: get it and like it when it arrives; on one that will be liked, take that back. */
    fun heartTrack(t: TidalTrack, r: RequestEntity?) =
        if (willLike(r)) dropIntent(r!!, like = true) else send(t.displayTitle, c.requests.track(t), like = true, toPhone = false)

    /** ♡ on an album: get the whole album and save it when it arrives; on one that will be saved, take that back. */
    fun heartAlbum(a: TidalAlbum, r: RequestEntity?) =
        if (willLike(r)) dropIntent(r!!, like = true) else send(a.title, c.requests.album(a), like = true, toPhone = false)

    /** Download on a song: get it and download it to this phone; on one that will be, take that back. */
    fun downloadTrack(t: TidalTrack, r: RequestEntity?) =
        if (willDownload(r)) dropIntent(r!!, toPhone = true) else send(t.displayTitle, c.requests.track(t), like = false, toPhone = true)

    /** Download on an album: get it and download it to this phone as a downloaded album. */
    fun downloadAlbum(a: TidalAlbum, r: RequestEntity?) =
        if (willDownload(r)) dropIntent(r!!, toPhone = true) else send(a.title, c.requests.album(a), like = false, toPhone = true)

    /** "Add to server": just get it onto the server; one on its way stops being liked or downloaded. */
    fun addTrackToServer(t: TidalTrack, r: RequestEntity?) = addToServer(r, t.displayTitle) { c.requests.track(t) }

    fun addAlbumToServer(a: TidalAlbum, r: RequestEntity?) = addToServer(r, a.title) { c.requests.album(a) }

    private fun addToServer(r: RequestEntity?, title: String, draft: () -> RequestEntity) {
        if (r == null || !isComing(r)) send(title, draft(), like = false, toPhone = false) else serverOnly(r)
    }

    /** Keeps [r] coming to the server, but it won't be liked or downloaded when it arrives (with Undo). */
    fun serverOnly(r: RequestEntity) {
        c.appScope.launch {
            runCatching { c.requests.serverOnly(r) }
                .onSuccess {
                    actions.showUndo("\"${r.title}\" will only be added to your server") { c.requests.request(r.asDraft(), r.like, r.toPhone) }
                }
                .onFailure { actions.showMessage("Couldn't change it right now") }
        }
    }

    /** Takes back liking or downloading something on its way; with nothing else left for it to do, it stops (with Undo). */
    fun dropIntent(r: RequestEntity, like: Boolean = false, toPhone: Boolean = false) {
        c.appScope.launch {
            runCatching { c.requests.dropIntent(r, like = like, toPhone = toPhone) }
                .onSuccess { stopped ->
                    val album = r.type == TidalType.ALBUM.api
                    val message = when {
                        stopped -> "Stopped getting \"${r.title}\""
                        like -> "\"${r.title}\" won't be ${if (album) "saved" else "liked"} when it arrives"
                        else -> "\"${r.title}\" won't be downloaded to your phone"
                    }
                    actions.showUndo(message) { c.requests.request(r.asDraft(), r.like, r.toPhone) }
                }
                .onFailure { actions.showMessage(whyFailed("Couldn't stop it", it)) }
        }
    }

    private val batch = Mutex()

    /**
     * Gets a few songs onto the server at once, e.g. an album's missing songs; they aren't liked or
     * downloaded themselves (a downloaded album picks them up when they arrive). Ones already on their
     * way are left as they are. [message] is shown when any were asked for.
     */
    fun getTracks(tracks: List<TidalTrack>, message: String) {
        if (tracks.isEmpty()) return
        c.appScope.launch {
            batch.withLock {
                var failed = 0
                var asked = 0
                var lastError: Throwable? = null
                tracks.forEach { t ->
                    runCatching { c.requests.request(c.requests.track(t), like = false, toPhone = false) }
                        .onSuccess { if (it == RequestsRepository.Outcome.REQUESTED || it == RequestsRepository.Outcome.ALREADY_ON_SERVER) asked++ }
                        .onFailure { failed++; lastError = it }
                }
                when {
                    failed == tracks.size -> actions.showMessage(whyFailed("Couldn't get them", lastError))
                    asked > 0 -> actions.showMessage(message)
                }
            }
        }
    }

    private fun send(title: String, draft: RequestEntity, like: Boolean, toPhone: Boolean) {
        c.appScope.launch {
            runCatching { c.requests.request(draft, like, toPhone) }
                .onSuccess { outcome ->
                    val album = draft.type == TidalType.ALBUM.api
                    val arrival = when {
                        toPhone -> "on your phone"
                        like && album -> "in your library"
                        like -> "in Liked Songs"
                        else -> "on your server"
                    }
                    actions.showMessage(
                        when (outcome) {
                            RequestsRepository.Outcome.ALREADY_REQUESTED -> "\"$title\" is already on its way"
                            RequestsRepository.Outcome.UPDATED -> "\"$title\" is on its way. It'll be $arrival too."
                            RequestsRepository.Outcome.ALREADY_ON_SERVER ->
                                if (!like && !toPhone) "\"$title\" is already on your server" else "\"$title\" was already on your server: it's $arrival now"
                            RequestsRepository.Outcome.REQUESTED -> when (draft.type) {
                                TidalType.ARTIST.api -> "Getting everything by $title"
                                else -> "Getting \"$title\". It'll be $arrival when it arrives."
                            }
                        }
                    )
                }
                .onFailure { actions.showMessage(whyFailed("Couldn't get \"$title\"", it)) }
        }
    }

    fun retry(r: RequestEntity) {
        c.appScope.launch {
            runCatching { c.requests.retry(r) }
                .onSuccess { actions.showMessage("Trying \"${r.title}\" again") }
                .onFailure { actions.showMessage(whyFailed("Couldn't try again", it)) }
        }
    }

    /** Stops getting [r], with Undo. */
    fun cancel(r: RequestEntity) {
        c.appScope.launch {
            runCatching { c.requests.cancel(r) }
                .onSuccess { actions.showUndo("Stopped getting \"${r.title}\"") { c.requests.request(r.asDraft(), r.like, r.toPhone) } }
                .onFailure { actions.showMessage(whyFailed("Couldn't stop it", it)) }
        }
    }

    /** Follow on an artist the server doesn't have: remembered, and offers to get their music. */
    fun follow(artist: TidalArtist, page: TidalArtistPage?) {
        if (c.requests.isFollowIntent(artist.name)) {
            c.requests.setFollowIntent(artist.name, false)
            actions.showUndo("Unfollowed") { c.requests.setFollowIntent(artist.name, true) }
            return
        }
        c.requests.setFollowIntent(artist.name, true)
        artistSheet = artist to page
    }

    /** Opens "Get their music?" without following. */
    fun offerArtist(artist: TidalArtist, page: TidalArtistPage?) { artistSheet = artist to page }

    enum class ArtistChoice { POPULAR, LATEST, EVERYTHING }

    fun getArtist(artist: TidalArtist, page: TidalArtistPage?, choice: ArtistChoice) {
        artistSheet = null
        c.appScope.launch {
            val p = page ?: runCatching { c.requests.artistPage(artist.id) }.getOrNull()
            when (choice) {
                ArtistChoice.EVERYTHING -> send(artist.name, c.requests.artist(artist), like = false, toPhone = false)
                ArtistChoice.POPULAR -> {
                    val top = p?.topTracks.orEmpty()
                    val owned = runCatching { c.requests.ownedTracks(top) }.getOrDefault(emptyMap())
                    val missing = top.filter { it.id !in owned }
                    if (p == null) actions.showMessage(whyFailed("Couldn't get their music", null))
                    else if (missing.isEmpty()) actions.showMessage("Their popular songs are already on your server")
                    else getTracks(missing, "Getting ${missing.size} popular songs by ${artist.name} onto your server")
                }
                ArtistChoice.LATEST -> {
                    val latest = p?.albums?.maxByOrNull { it.releaseDate.orEmpty() } ?: p?.singles?.maxByOrNull { it.releaseDate.orEmpty() }
                    if (latest == null) actions.showMessage("Couldn't find their latest album")
                    else send(latest.title, c.requests.album(latest), like = false, toPhone = false)
                }
            }
        }
    }
}

@Composable
fun rememberGetMusic(c: AppContainer, actions: Actions): GetMusic = remember(c, actions) { GetMusic(c, actions) }
