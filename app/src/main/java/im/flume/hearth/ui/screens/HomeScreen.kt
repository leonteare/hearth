package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.data.AlbumEntity
import im.flume.hearth.data.Mix
import im.flume.hearth.data.StatsPeriod
import im.flume.hearth.data.PlaySource
import im.flume.hearth.sync.SyncState
import im.flume.hearth.ui.components.AlbumRow
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.MediaCard
import im.flume.hearth.ui.components.PageHeader
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.DownloadsColor
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.LikedColor
import im.flume.hearth.ui.theme.SurfaceHigh
import im.flume.hearth.ui.theme.TextSecondary
import im.flume.hearth.data.PlaylistRules
import java.util.Calendar

@Composable
fun HomeScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val dao = c.db.library()

    val online by c.network.isOnline.collectAsStateWithLifecycle()
    val sync by c.sync.state.collectAsStateWithLifecycle()
    val songCount by remember { dao.songCount() }.collectAsStateWithLifecycle(-1)
    val localRecent by remember { dao.recentlyPlayedAlbums(12) }.collectAsStateWithLifecycle(emptyList())
    val playlistItems by remember { c.library.playlistItems }.collectAsStateWithLifecycle(emptyList())
    val downloadCounts by remember { c.downloads.counts }.collectAsStateWithLifecycle(im.flume.hearth.data.DownloadCounts())
    // Yours and ones you've joined; everyone else's stay out of sight.
    val playlists = remember(playlistItems) { PlaylistRules.joined(playlistItems) }

    var serverRecent by remember { mutableStateOf<List<AlbumEntity>>(emptyList()) }
    LaunchedEffect(online, songCount > 0) {
        if (online) serverRecent = runCatching { c.library.serverRecentAlbums(12) }.getOrDefault(emptyList())
    }
    val recent = serverRecent.ifEmpty { localRecent }
    var mixes by remember { mutableStateOf<List<Mix>>(emptyList()) }
    LaunchedEffect(songCount > 0) { if (songCount > 0) mixes = runCatching { c.mixes.today(online) }.getOrDefault(emptyList()) }

    val accent = MaterialTheme.colorScheme.primary
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            PageHeader(greeting()) {
                if (!online) Icon(Icons.Default.CloudOff, "Offline", tint = TextSecondary)
                IconButton(onClick = { actions.open("settings") }) { Icon(Icons.Default.Settings, "Settings") }
            }
        }

        item { UpdateBanner() }
        item { CrashBanner() }

        val running = sync as? SyncState.Running
        if (running != null && songCount <= 0) {
            item {
                Column(Modifier.padding(Dimens.Gutter)) {
                    Text("Loading your library… ${running.songsSoFar} songs", color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        (sync as? SyncState.Failed)?.let { f ->
            item { Text("Library sync failed: ${f.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }

        item {
            QuickGrid(
                tiles = buildList {
                    add(QuickTile("Shuffle my library", Icons.Default.Shuffle, accent) { actions.play(PlaySource.MyLibrary, shuffle = true) })
                    add(QuickTile("Liked Songs", Icons.Default.Favorite, LikedColor) { actions.open("liked") })
                    // Only while something is downloading or failed; finished downloads live in their albums and playlists.
                    if (downloadCounts.active) {
                        add(QuickTile(downloadCounts.summary, Icons.Default.DownloadDone, DownloadsColor) { actions.open("downloads") })
                    }
                    recent.take(if (downloadCounts.active) 3 else 4).forEach { a -> add(QuickTile(a.name, cover = a.coverArt) { actions.openAlbum(a.id) }) }
                }
            )
        }

        items(StatsPeriod.current(), key = { "stats:${it.key}" }) { StatsCard(it) }

        if (mixes.isNotEmpty()) {
            item {
                SectionHeader("Your mixes")
                LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(Dimens.CarouselSpacing)) {
                    items(mixes.size) { i ->
                        val mix = mixes[i]
                        MediaCard(mix.title, mix.subtitle, onClick = { actions.open("mix/$i") }, subtitleLines = 2, cover = { MixCover(mix, Dimens.CardWidth) })
                    }
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item {
                SectionHeader("Your playlists")
                LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(Dimens.CarouselSpacing)) {
                    items(playlists, key = { it.playlist.id }) { item ->
                        val p = item.playlist
                        val by = if (item.access.isOwner) "" else " • by ${PlaylistRules.displayName(item.access.owner)}"
                        MediaCard(p.name, plural(p.songCount, "song") + by, onClick = { actions.openPlaylist(p.id) }, coverArt = p.coverArt)
                    }
                }
            }
        }
    }
}

@Composable
fun MixCover(mix: Mix, size: androidx.compose.ui.unit.Dp) {
    val covers = (mix.covers + List(4) { null }).take(4)
    Column(Modifier.size(size).clip(HearthShapes.Cover)) {
        for (row in 0..1) Row(Modifier.weight(1f)) {
            for (col in 0..1) CoverArt(covers[row * 2 + col], null, Modifier.weight(1f).fillMaxSize(), corner = 0.dp, requestSize = 200)
        }
    }
}

private data class QuickTile(
    val title: String,
    val icon: ImageVector? = null,
    val color: Color = SurfaceHigh,
    val cover: String? = null,
    val onClick: () -> Unit,
)

@Composable
private fun QuickGrid(tiles: List<QuickTile>) {
    val columns = gridColumns()
    Column(Modifier.padding(horizontal = Dimens.Gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { t -> QuickTileView(t, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QuickTileView(t: QuickTile, modifier: Modifier) {
    Row(
        modifier.heightIn(min = 56.dp).clip(HearthShapes.Card).background(SurfaceHigh).clickable(onClick = t.onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t.icon != null) {
            Box(Modifier.size(56.dp).background(t.color), contentAlignment = Alignment.Center) {
                Icon(t.icon, null, tint = if (t.color == MaterialTheme.colorScheme.primary) MaterialTheme.colorScheme.onPrimary else Color.White)
            }
        } else {
            CoverArt(t.cover, 56.dp, corner = 0.dp, requestSize = 150, fallback = t.title)
        }
        Text(
            t.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
