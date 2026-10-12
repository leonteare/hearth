// The menus and sheets GetMusic opens: a missing song's or album's options, Get their music?, and a request's options.
package im.flume.hearth.core.ui.components

import androidx.compose.runtime.Composable
import im.flume.hearth.core.network.TidalType
import im.flume.hearth.core.data.RequestEntity
import im.flume.hearth.core.data.RequestStatus
import im.flume.hearth.core.ui.theme.HearthTheme

/** The sheets [GetMusic] opens. Shown once, at the root. */
@Composable
fun GetMusicSheets(get: GetMusic) {
    val actions = LocalActions.current
    val requests = LocalRequests.current
    get.menuFor?.let { t ->
        val request = requests[trackKey(t.id)]
        val close = { get.menuFor = null }
        ActionSheet(true, onDismiss = close, title = t.displayTitle, subtitle = menuSubtitle(request), showCover = false) {
            MenuItem("Preview", HearthTheme.icons.play) { close(); get.preview(t) }
            IntentItems(
                request, album = false, close = close,
                onLike = { get.heartTrack(t, request) },
                onDownload = { get.downloadTrack(t, request) },
                onServer = { get.addTrackToServer(t, request) },
            )
            t.albumId?.let { id -> MenuItem("Go to album", HearthTheme.icons.album) { close(); actions.openAlbum(remoteAlbumId(id)) } }
            t.artistId?.let { id -> MenuItem("Go to artist", HearthTheme.icons.artist) { close(); actions.openArtist(remoteArtistId(id)) } }
        }
    }
    get.albumMenuFor?.let { a ->
        val request = requests[albumKey(a.id)]
        val close = { get.albumMenuFor = null }
        ActionSheet(true, onDismiss = close, title = a.title, subtitle = menuSubtitle(request), showCover = false) {
            IntentItems(
                request, album = true, close = close,
                onLike = { get.heartAlbum(a, request) },
                onDownload = { get.downloadAlbum(a, request) },
                onServer = { get.addAlbumToServer(a, request) },
            )
            MenuItem("Go to album", HearthTheme.icons.album) { close(); actions.openAlbum(remoteAlbumId(a.id)) }
            a.artistId?.let { id -> MenuItem("Go to artist", HearthTheme.icons.artist) { close(); actions.openArtist(remoteArtistId(id)) } }
        }
    }
    get.artistSheet?.let { (artist, page) ->
        ActionSheet(
            true, onDismiss = { get.artistSheet = null },
            title = "Get their music?", subtitle = "Following ${artist.name}. Their music isn't on your server yet.", showCover = false,
        ) {
            MenuItem("Popular songs", HearthTheme.icons.popular) { get.getArtist(artist, page, GetMusic.ArtistChoice.POPULAR) }
            MenuItem("Latest album", HearthTheme.icons.albumOutline) { get.getArtist(artist, page, GetMusic.ArtistChoice.LATEST) }
            MenuItem("Everything", HearthTheme.icons.everything) { get.getArtist(artist, page, GetMusic.ArtistChoice.EVERYTHING) }
            MenuItem("Not now", HearthTheme.icons.close) { get.artistSheet = null }
        }
    }
    get.requestMenu?.let { opened ->
        val r = requests["${opened.type}:${opened.tidalId}"] ?: opened
        val close = { get.requestMenu = null }
        ActionSheet(true, onDismiss = close, title = r.title, subtitle = failureText(r) ?: menuSubtitle(r), showCover = false) {
            // "Adding" can be tried again too: it may have finished without anything arriving.
            if (r.status == RequestStatus.FAILED || r.status == RequestStatus.ADDING) {
                MenuItem("Try again", HearthTheme.icons.refresh) { close(); get.retry(r) }
            }
            if (isComing(r) && r.type != TidalType.ARTIST.api) {
                val album = r.type == TidalType.ALBUM.api
                if (r.like && r.toPhone) MenuItem(if (album) "Don't add to Your Library" else "Don't add to Liked Songs", HearthTheme.icons.like) { close(); get.dropIntent(r, like = true) }
                if (r.toPhone && r.like) MenuItem("Don't download to phone", HearthTheme.icons.download) { close(); get.dropIntent(r, toPhone = true) }
                if (r.like || r.toPhone) MenuItem("Only add to server", HearthTheme.icons.addToServer) { close(); get.serverOnly(r) }
            }
            if (r.type == TidalType.ALBUM.api) MenuItem("Go to album", HearthTheme.icons.album) { close(); actions.openAlbum(remoteAlbumId(r.tidalId)) }
            if (r.type == TidalType.ARTIST.api) MenuItem("Go to artist", HearthTheme.icons.artist) { close(); actions.openArtist(remoteArtistId(r.tidalId)) }
            MenuItem(if (r.status == RequestStatus.FAILED) "Remove" else "Cancel", HearthTheme.icons.close, tint = HearthTheme.colors.destructive, tintText = true) { close(); get.cancel(r) }
        }
    }
}

/**
 * The ways to get a song or album that isn't on the server (like or save it, download it, or just add
 * it to the server), each one taking itself back while it's on its way; then Try again or Stop.
 */
@Composable
private fun IntentItems(
    request: RequestEntity?,
    album: Boolean,
    close: () -> Unit,
    onLike: () -> Unit,
    onDownload: () -> Unit,
    onServer: () -> Unit,
) {
    val get = LocalGetMusic.current
    val coming = isComing(request)
    if (willLike(request)) MenuItem(if (album) "Don't add to Your Library" else "Don't add to Liked Songs", HearthTheme.icons.likeOn) { close(); onLike() }
    else MenuItem(if (album) "Save" else "Like", HearthTheme.icons.like) { close(); onLike() }
    if (willDownload(request)) MenuItem("Don't download to phone", HearthTheme.icons.download) { close(); onDownload() }
    else MenuItem("Download to phone", HearthTheme.icons.download) { close(); onDownload() }
    if (!coming) MenuItem("Add to server", HearthTheme.icons.addToServer) { close(); onServer() }
    else if (request!!.like || request.toPhone) MenuItem("Only add to server", HearthTheme.icons.addToServer) { close(); onServer() }
    when {
        request?.status == RequestStatus.FAILED -> MenuItem("Try again", HearthTheme.icons.refresh) { close(); get.retry(request) }
        coming -> MenuItem("Stop getting it", HearthTheme.icons.close) { close(); get.cancel(request!!) }
    }
}
