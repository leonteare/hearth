package im.flume.hearth.domain.requests

import im.flume.hearth.core.common.EXTRA_ROUTE
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import im.flume.hearth.core.common.R
import im.flume.hearth.core.network.TidalType
import im.flume.hearth.core.data.AlbumEntity
import im.flume.hearth.core.data.ArtistEntity
import im.flume.hearth.core.data.RequestEntity
import im.flume.hearth.domain.music.RequestMatching
import im.flume.hearth.core.data.RequestStatus
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.core.data.toEntity
import im.flume.hearth.domain.music.download.DownloadRepository
import kotlinx.coroutines.launch

/*
 * What happens when requested music turns up: finding it on the server, adding it to the library,
 * liking or downloading it as asked, following artists followed early, and telling the user with a
 * notification. Extensions on [RequestsRepository], which owns the state they use.
 */

private const val CHANNEL = "requests"

/** What a request matched on the server. */
internal sealed interface FoundOnServer {
    data class Songs(val songs: List<SongEntity>) : FoundOnServer
    data class Album(val album: AlbumEntity, val songs: List<SongEntity>) : FoundOnServer
    data class Artist(val artist: ArtistEntity) : FoundOnServer
}

/** Looks for the request on the server. Null when it isn't there (yet); throws when the server can't be asked. */
internal suspend fun RequestsRepository.findOnServer(r: RequestEntity): FoundOnServer? {
    val artist = RequestMatching.splitArtists(r.artist).first()
    val title = RequestMatching.searchTitle(r.title)
    return when (TidalType.of(r.type)) {
        TidalType.TRACK -> {
            for (q in listOf("$title $artist", title).distinct()) {
                val candidates = api.search(q, songCount = 20, albumCount = 0, artistCount = 0).song
                    .filterNot { it.isDir }.map { it.toEntity() }
                val song = RequestMatching.pickTrack(candidates, r.title, r.artist, r.isrc, r.durationSec)
                if (song != null) return FoundOnServer.Songs(listOf(song))
            }
            null
        }
        TidalType.ALBUM -> {
            for (q in listOf("$title $artist", title).distinct()) {
                val candidates = api.search(q, songCount = 0, albumCount = 20, artistCount = 0).album.map { it.toEntity() }
                val album = RequestMatching.pickAlbum(candidates, r.title, r.artist) ?: continue
                val full = api.album(album.id) ?: continue
                return FoundOnServer.Album(full.toEntity(), full.song.map { it.toEntity() })
            }
            null
        }
        TidalType.ARTIST -> {
            val candidates = api.search(r.title, songCount = 0, albumCount = 0, artistCount = 10).artist.map { it.toEntity() }
            RequestMatching.pickArtist(candidates, r.title)?.let { FoundOnServer.Artist(it) }
        }
    }
}

/**
 * Puts what was found into the library, does what the request asked for on arrival (like or save
 * it, download it to the phone; or nothing more), and marks the request done.
 */
internal suspend fun RequestsRepository.complete(r: RequestEntity, found: FoundOnServer): RequestIntent.Arrival {
    val lib = db.library()
    val matched: List<String>
    var route: String? = null
    val actions = RequestIntent.onArrival(TidalType.of(r.type), r.like, r.toPhone)
    when (found) {
        is FoundOnServer.Songs -> {
            lib.insertMissingSongs(found.songs)
            // Songs on albums this phone hasn't synced yet get their album row too, so "Go to album" works.
            found.songs.mapNotNull { it.albumId }.distinct().filter { lib.albumOnce(it) == null }.forEach { id ->
                runCatching { api.album(id) }.getOrNull()?.let { lib.insertMissingAlbums(listOf(it.toEntity())) }
            }
            if (RequestIntent.Action.LIKE_SONGS in actions) found.songs.forEach { s -> runCatching { library.setStarred(s.id, true) } }
            applyFollowIntents(found.songs.map { it.artist to it.artistId })
            if (RequestIntent.Action.DOWNLOAD_SONGS in actions) downloads.download(library.songsByIds(found.songs.map { it.id }))
            matched = found.songs.map { it.id }
            route = found.songs.firstOrNull()?.albumId?.let { "album/${Uri.encode(it)}" }
        }
        is FoundOnServer.Album -> {
            lib.insertMissingSongs(found.songs)
            lib.insertMissingAlbums(listOf(found.album))
            if (RequestIntent.Action.SAVE_ALBUM in actions) runCatching { library.setAlbumSaved(found.album.id, true) }
            applyFollowIntents(listOf(found.album.artist to found.album.artistId))
            if (RequestIntent.Action.DOWNLOAD_ALBUM in actions) downloads.pinAndDownload(DownloadRepository.KIND_ALBUM, found.album.id, found.songs)
            matched = listOf(found.album.id)
            route = "album/${Uri.encode(found.album.id)}"
        }
        is FoundOnServer.Artist -> {
            lib.insertMissingArtists(listOf(found.artist))
            runCatching { library.setArtistFollowed(found.artist.id, true) }
            setFollowIntent(found.artist.name, false)
            matched = listOf(found.artist.id)
            route = "artist/${Uri.encode(found.artist.id)}"
            // The whole discography arrives with the next full sync; nothing is downloaded to the phone.
            scope.launch { runCatching { sync.syncIfNeeded() } }
        }
    }
    catalogVersion++
    library.invalidateSearchIndex() // so it shows up in Search straight away
    dao.upsert(
        r.copy(
            status = RequestStatus.DONE, errorMessage = null, matchedIds = matched.joinToString(","),
            updatedAt = System.currentTimeMillis(),
        )
    )
    return RequestIntent.Arrival(
        TidalType.of(r.type), r.title, r.artist, r.albumTitle.takeIf { r.type == TidalType.TRACK.api },
        r.like, r.toPhone, route, "${r.type}:${r.tidalId}",
    )
}

/**
 * Tidarr said "finished" but nothing turned up on the server. That's a failure, not a success:
 * Tidarr marks a download that produced no files as finished (e.g. on a free Tidal account).
 * A sync still runs in case it did arrive under a name that couldn't be matched.
 */
internal suspend fun RequestsRepository.giveUp(r: RequestEntity) {
    dao.upsert(RequestTracking.gaveUp(r, System.currentTimeMillis()))
    scope.launch { runCatching { sync.syncIfNeeded() } }
}

/** Follows the server's artist for any arrived music whose artist was followed early. */
private suspend fun RequestsRepository.applyFollowIntents(credits: List<Pair<String, String?>>) {
    if (followIntents.value.isEmpty()) return
    for ((artist, artistId) in credits) {
        if (artistId == null) continue
        val hit = followIntents.value.firstOrNull { RequestMatching.artistMatches(listOf(it), artist) } ?: continue
        runCatching { library.setArtistFollowed(artistId, true) }.onSuccess { setFollowIntent(hit, false) }
    }
}

internal fun RequestsRepository.notifyArrival(note: RequestIntent.Note) {
    val (title, text, route, key) = note
    runCatching {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "New music", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, context.requestsContainer.mainActivity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ROUTE, route ?: "downloads")
        val id = key.hashCode()
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply { text?.let { setContentText(it) } }
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}
