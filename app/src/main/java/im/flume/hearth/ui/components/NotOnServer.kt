// Music not on the server: routes, request keys, the root providers, and how a request's status reads.
package im.flume.hearth.ui.components

import androidx.compose.runtime.compositionLocalOf
import im.flume.hearth.api.TidalAlbum
import im.flume.hearth.api.TidalType
import im.flume.hearth.data.RequestEntity
import im.flume.hearth.data.RequestIntent
import im.flume.hearth.data.RequestStatus
import im.flume.hearth.data.RequestTracking
import im.flume.hearth.playback.PreviewPlayer

/*
 * Music that isn't on the server yet is a state, not a place: it shows in the same rows, cards and
 * pages as everything else, dimmed, with a small cloud. Tapping a song previews 30 seconds; the
 * heart gets it onto the server and likes it, Download gets it onto the server and this phone, and
 * "Add to server" just gets it onto the server.
 */

/** Routes for pages of music that isn't on the server: the same album and artist pages. */
fun remoteAlbumId(id: String) = "$REMOTE_PREFIX$id"
fun remoteArtistId(id: String) = "$REMOTE_PREFIX$id"
const val REMOTE_PREFIX = "tidal:"

/** This user's requests by "type:id"; provided once at the root. */
val LocalRequests = compositionLocalOf<Map<String, RequestEntity>> { emptyMap() }

/** The preview playing or loading, provided once at the root. */
val LocalPreview = compositionLocalOf<PreviewPlayer.Preview?> { null }

val LocalGetMusic = compositionLocalOf<GetMusic> { error("GetMusic not provided") }

fun trackKey(id: String) = "${TidalType.TRACK.api}:$id"
fun albumKey(id: String) = "${TidalType.ALBUM.api}:$id"
fun artistKey(id: String) = "${TidalType.ARTIST.api}:$id"

/** What's happening to something that's on its way (and what it'll do when it arrives), or null when nothing is. */
fun comingLabel(r: RequestEntity?): String? = RequestIntent.comingLabel(r)

/** Why getting it failed, in the app's words (the stored message names the services behind it). */
fun failureText(r: RequestEntity): String? = when {
    r.status != RequestStatus.FAILED -> null
    r.errorMessage == RequestTracking.NOTHING_ARRIVED -> "It finished, but nothing arrived on your server. Try again later."
    else -> "Couldn't download it. Try again later."
}

/** True while it's been asked for and hasn't arrived or failed. */
fun isComing(r: RequestEntity?) = r != null && r.status.active

/** On its way and will be liked (a song) or saved (an album) when it arrives: the heart shows filled. */
fun willLike(r: RequestEntity?) = isComing(r) && r!!.like

/** On its way and will be downloaded to this phone when it arrives. */
fun willDownload(r: RequestEntity?) = isComing(r) && r!!.toPhone

fun albumKind(a: TidalAlbum) = when (a.type) { "EP" -> "EP"; "SINGLE" -> "Single"; else -> "Album" }

/** A ⋯ menu's subtitle: what it'll do when it arrives, why it failed, or that it isn't on the server. */
fun menuSubtitle(r: RequestEntity?): String = when {
    r == null -> "Not on your server yet"
    r.status == RequestStatus.FAILED -> failureText(r) ?: "Couldn't get it"
    isComing(r) -> RequestIntent.summary(r)
    else -> "Not on your server yet"
}
