// The artist page, for artists on the server and ones that aren't ("tidal:" ids).
package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.api.TidalArtistPage
import im.flume.hearth.api.tidalImageUrl
import im.flume.hearth.container
import im.flume.hearth.data.AlbumEntity
import im.flume.hearth.data.Availability
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SearchIndex
import im.flume.hearth.data.SongEntity
import im.flume.hearth.data.SongItem
import im.flume.hearth.ui.components.AvailabilityIcon
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.LoadingPage
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.LocalGetMusic
import im.flume.hearth.ui.components.LocalRequests
import im.flume.hearth.ui.components.PlayShuffleButtons
import im.flume.hearth.ui.components.REMOTE_PREFIX
import im.flume.hearth.ui.components.RemoteCover
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.SongRow
import im.flume.hearth.ui.components.UnavailablePage
import im.flume.hearth.ui.components.artistKey
import im.flume.hearth.ui.components.comingLabel
import im.flume.hearth.ui.components.isComing
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.components.rememberCoverColor
import im.flume.hearth.ui.theme.CoverSize
import im.flume.hearth.ui.theme.HearthTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ArtistExtras(val page: TidalArtistPage, val owned: Map<String, SongEntity>, val ownedAlbums: Map<String, AlbumEntity> = emptyMap())

@Composable
fun ArtistScreen(id: String) {
    if (id.startsWith(REMOTE_PREFIX)) return RemoteArtistScreen(id.removePrefix(REMOTE_PREFIX))
    val c = LocalContext.current.container
    val dao = c.db.library()
    val artist by remember(id) { dao.artist(id) }.collectAsStateWithLifecycle(null)
    val songs by remember(id) { dao.artistSongs(id) }.collectAsStateWithLifecycle(emptyList())
    val albums by remember(id) { dao.artistAlbums(id) }.collectAsStateWithLifecycle(emptyList())
    val actions = LocalActions.current
    val name = artist?.name ?: songs.firstOrNull()?.artist.orEmpty()
    val source = PlaySource(PlaySource.Kind.ARTIST, id, name)

    // Popular songs and the releases the server is missing, looked up once the page is showing.
    val lookups = lookupsOn()
    var extras by remember(id) { mutableStateOf<ArtistExtras?>(null) }
    LaunchedEffect(id, name, lookups) {
        if (!lookups || name.isBlank()) return@LaunchedEffect
        extras = try {
            withContext(Dispatchers.IO) {
                c.requests.artistByName(name)?.let { page -> ArtistExtras(page, c.requests.ownedTracks(page.topTracks)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val x = extras
    val popular = remember(songs, x) {
        x?.page?.topTracks?.takeIf { it.isNotEmpty() }?.let { ownedItems(it, x.owned) } ?: songs.take(5).map { SongItem.Owned(it) }
    }
    val discography = remember(albums, x) { Availability.discography(albums, x?.page?.albums.orEmpty(), x?.page?.singles.orEmpty()) }
    val shownIds = remember(popular) { popular.mapNotNull { (it as? SongItem.Owned)?.song?.id }.toSet() }
    val rest = if (x != null) songs.filterNot { it.id in shownIds } else songs.drop(5)
    val artistCover = artist?.coverArt ?: albums.firstOrNull()?.coverArt

    ArtistLayout(
        name = name,
        headerColor = rememberCoverColor(artistCover),
        art = { size -> CoverArt(artistCover, size, Modifier.clip(CircleShape), requestSize = CoverSize.LARGE, fallback = name) },
        counts = "${plural(albums.size, "album")} • ${plural(songs.size, "song")}",
        buttons = {
            PlayShuffleButtons(
                onPlay = { actions.play(source) },
                onShuffle = { actions.play(source, shuffle = true) },
                extra = {
                    artist?.let { ar -> FollowButton(ar.starred) { actions.setArtistFollowed(id, !ar.starred) } }
                    Spacer(Modifier.width(HearthTheme.spacing.xs))
                    TextButton(onClick = { actions.startArtistRadio(id, name) }) {
                        Icon(HearthTheme.icons.radio, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Radio")
                    }
                },
            )
        },
        popular = popular,
        onPlayOwned = { song ->
            val ids = popular.mapNotNull { (it as? SongItem.Owned)?.song?.id }
            actions.play(PlaySource(PlaySource.Kind.SONGS, label = name, songIds = ids), startSongId = song.id)
        },
        discography = discography,
        more = {
            if (rest.isNotEmpty()) {
                item(key = "all-head") { SectionHeader("All songs") }
                items(rest, key = { "all:${it.id}" }) { s ->
                    Column(Modifier.animateItem()) { SongRow(s, onClick = { actions.play(source, startSongId = s.id) }) }
                }
            }
            item(key = "about") { ArtistAbout(id) }
        },
    )
}

private data class RemoteArtist(val extras: ArtistExtras, val localArtistId: String?)

@Composable
private fun RemoteArtistScreen(id: String) {
    val c = LocalContext.current.container
    val state = rememberLoad("artist:$id") {
        val page = c.requests.artistPage(id)
        val owned = c.requests.ownedTracks(page.topTracks)
        val ownedAlbums = c.requests.ownedAlbums(page.albums + page.singles)
        RemoteArtist(ArtistExtras(page, owned, ownedAlbums), c.requests.localArtist(page.artist.name)?.id)
    }
    when (state) {
        Load.Loading -> LoadingPage()
        Load.Failed -> UnavailablePage(HearthTheme.icons.offline, "Couldn't load this artist. Check your connection and try again.")
        is Load.Ready -> {
            val local = state.value.localArtistId
            if (local != null) ArtistScreen(local) else RemoteArtistContent(state.value.extras)
        }
    }
}

@Composable
private fun RemoteArtistContent(x: ArtistExtras) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val get = LocalGetMusic.current
    val page = x.page
    val artist = page.artist
    val request = LocalRequests.current[artistKey(artist.id)]
    val intents by c.requests.followIntents.collectAsStateWithLifecycle()
    val following = SearchIndex.norm(artist.name) in intents || isComing(request)
    val popular = remember(x) { ownedItems(page.topTracks, x.owned) }
    val discography = remember(x) { Availability.discography(x.ownedAlbums.values.distinctBy { it.id }, page.albums, page.singles) }
    val firstMissing = popular.firstNotNullOfOrNull { (it as? SongItem.Missing)?.track }
    val counts = listOfNotNull(
        page.albums.size.takeIf { it > 0 }?.let { plural(it, "album") },
        page.singles.size.takeIf { it > 0 }?.let { plural(it, "single or EP", "singles and EPs") },
    ).joinToString(" • ")

    ArtistLayout(
        name = artist.name,
        headerColor = rememberCoverColor(null, imageUrl = tidalImageUrl(artist.picture, 160)),
        art = { size -> Box(Modifier.size(size).clip(CircleShape)) { RemoteCover(artist.picture, artist.name, 750) } },
        counts = counts,
        dimmed = true,
        status = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvailabilityIcon(request)
                Spacer(Modifier.width(HearthTheme.spacing.xs))
                Text(comingLabel(request) ?: "Not on your server yet", color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        },
        buttons = {
            PlayShuffleButtons(
                onPlay = { if (firstMissing != null) get.preview(firstMissing) else actions.showMessage("Nothing to preview") },
                onShuffle = null,
                playLabel = "Play preview",
                extra = {
                    FollowButton(following) { get.follow(artist, page) }
                    if (following && request == null) {
                        Spacer(Modifier.width(HearthTheme.spacing.xs))
                        TextButton(onClick = { get.offerArtist(artist, page) }) {
                            Icon(HearthTheme.icons.fetchToServer, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Get music")
                        }
                    }
                },
            )
        },
        popular = popular,
        onPlayOwned = { song ->
            val ids = popular.mapNotNull { (it as? SongItem.Owned)?.song?.id }
            actions.play(PlaySource(PlaySource.Kind.SONGS, label = artist.name, songIds = ids), startSongId = song.id)
        },
        discography = discography,
    )
}
