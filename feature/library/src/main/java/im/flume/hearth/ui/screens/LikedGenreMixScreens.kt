// Song-list pages built on the shared collection screen: an Artist Mix, a genre, and Liked Songs.
package im.flume.hearth.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.data.Mix
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SongEntity
import im.flume.hearth.data.SongOrder
import im.flume.hearth.download.DownloadRepository
import im.flume.hearth.ui.components.EmptyState
import im.flume.hearth.ui.components.LoadingPage
import im.flume.hearth.ui.components.MixCover
import im.flume.hearth.ui.components.UnavailablePage
import im.flume.hearth.ui.theme.HearthTheme

/**
 * A mix built on this phone, found by its title ("Artist Mix", one per artist) rather than its place
 * in the list: the list can be rebuilt differently (e.g. offline), and the page must stay the mix you tapped.
 */
@Composable
fun MixScreen(key: String) {
    val c = LocalContext.current.container
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    var mix by remember { mutableStateOf<Mix?>(null) }
    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        mix = runCatching { c.mixes.today(online).firstOrNull { it.title == key } }.getOrNull()
        songs = mix?.let { c.library.songsByIds(it.songIds) }.orEmpty()
        loaded = true
    }
    if (!loaded) return LoadingPage()
    // A stale link (e.g. today's mixes were rebuilt with fewer entries).
    val m = mix ?: return UnavailablePage(HearthTheme.icons.radio, "This mix isn't available any more")
    CollectionScreen(
        title = m.title,
        subtitle = m.subtitle,
        cover = m.covers.firstOrNull(),
        songs = songs,
        source = PlaySource(PlaySource.Kind.SONGS, label = "${m.title} · ${m.subtitle}", songIds = m.songIds),
        headerIcon = { size -> MixCover(m, size) },
    )
}

@Composable
fun GenreScreen(name: String) {
    val dao = LocalContext.current.container.db.library()
    val loaded by remember(name) { dao.genreSongs(name) }.collectAsStateWithLifecycle(null)
    val songs = loaded.orEmpty()
    CollectionScreen(
        title = name,
        subtitle = "Genre",
        cover = songs.firstOrNull()?.coverArt,
        songs = songs,
        source = PlaySource(PlaySource.Kind.GENRE, name, name),
        emptyState = if (loaded?.isEmpty() == true) {
            { EmptyState(HearthTheme.icons.song, "No songs in this genre any more") }
        } else null,
    )
}

@Composable
fun LikedScreen() {
    val dao = LocalContext.current.container.db.library()
    val loaded by remember { dao.starredSongs() }.collectAsStateWithLifecycle(null)
    // Title A–Z unless you pick another order; the choice is remembered like Your Library's sorts.
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("library", Context.MODE_PRIVATE) }
    var sort by remember { mutableStateOf(prefs.getInt("sort-LIKED", 0)) }
    val order = SongOrder.liked(sort)
    val sorted = remember(loaded, order) { loaded?.let { order.sort(it) }.orEmpty() }
    CollectionScreen(
        searchable = true,
        title = "Liked Songs",
        subtitle = "",
        cover = null,
        songs = sorted,
        source = PlaySource.Liked.copy(order = order),
        pin = PinTarget(DownloadRepository.KIND_LIKED, "liked"),
        headerIcon = { size -> BigIcon(HearthTheme.icons.likeOn, HearthTheme.colors.liked, size) },
        headerActions = {
            SortMenu(SongOrder.LIKED.map { it.second }, sort) {
                sort = it
                prefs.edit().putInt("sort-LIKED", it).apply()
            }
        },
        emptyState = if (loaded?.isEmpty() == true) {
            { EmptyState(HearthTheme.icons.like, "Songs you like show up here. Tap the heart on any song to add it.") }
        } else null,
    )
}

@Composable
private fun BigIcon(icon: ImageVector, color: Color, size: Dp = 220.dp) {
    Box(Modifier.size(size * 0.82f).clip(HearthTheme.shapes.cover).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(72.dp), tint = HearthTheme.colors.onCover)
    }
}
