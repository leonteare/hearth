package im.flume.hearth.feature.home

import androidx.compose.material3.TextButton
import im.flume.hearth.core.playback.AppContainer
import im.flume.hearth.core.data.DownloadCounts
import im.flume.hearth.domain.music.ServerMixes
import im.flume.hearth.core.data.spoken.SpokenDownloadState
import im.flume.hearth.domain.spoken.SpokenLists
import im.flume.hearth.core.ui.components.BannerCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import im.flume.hearth.core.playback.container
import im.flume.hearth.domain.music.Mix
import im.flume.hearth.domain.music.StatsPeriod
import im.flume.hearth.domain.music.PlaySource
import im.flume.hearth.domain.music.sync.SyncState
import im.flume.hearth.core.ui.components.MixCover
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.SectionHeader
import im.flume.hearth.core.ui.components.MediaCard
import im.flume.hearth.core.ui.components.PageHeader
import im.flume.hearth.core.ui.components.plural
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.domain.music.PlaylistRules
import java.util.Calendar
import im.flume.hearth.core.ui.screens.CrashBanner
import im.flume.hearth.core.ui.screens.UpdateBanner
import im.flume.hearth.core.ui.screens.coverArt

@Composable
fun HomeScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val dao = c.db.library()

    val online by c.network.isOnline.collectAsStateWithLifecycle()
    val sync by c.sync.state.collectAsStateWithLifecycle()
    val songCount by remember { dao.songCount() }.collectAsStateWithLifecycle(-1)
    val playlistItems by remember { c.library.playlistItems }.collectAsStateWithLifecycle(emptyList())
    // Yours and ones you've joined; everyone else's stay out of sight.
    val playlists = remember(playlistItems) { PlaylistRules.joined(playlistItems) }
    val serverMixes = remember(playlistItems) { ServerMixes.mine(playlistItems) }
    // Podcasts and audiobooks, once signed in: what you're part-way through, and new episodes of shows you follow.
    val spokenAccount by c.spoken.account.collectAsStateWithLifecycle()
    val continueListening by remember { c.spoken.continueListening(12) }.collectAsStateWithLifecycle(emptyList())
    val latestEpisodes by remember { c.spoken.latestEpisodes(50) }.collectAsStateWithLifecycle(emptyList())
    val newEpisodes = remember(latestEpisodes) { SpokenLists.newEpisodes(latestEpisodes, 5) }

    var mixes by remember { mutableStateOf<List<Mix>>(emptyList()) }
    LaunchedEffect(songCount > 0) { if (songCount > 0) mixes = runCatching { c.mixes.today(online) }.getOrDefault(emptyList()) }

    val accent = MaterialTheme.colorScheme.primary
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = HearthTheme.spacing.xl)) {
        item {
            PageHeader(greeting()) {
                if (!online) Icon(HearthTheme.icons.offline, "Offline", tint = HearthTheme.colors.textSecondary)
                IconButton(onClick = { actions.open("settings") }) { Icon(HearthTheme.icons.settings, "Settings") }
            }
        }

        item { UpdateBanner() }
        item { CrashBanner() }

        val running = sync as? SyncState.Running
        if (running != null && songCount <= 0) {
            item {
                Column(Modifier.padding(HearthTheme.spacing.gutter)) {
                    Text("Loading your library… ${running.songsSoFar} songs", color = HearthTheme.colors.textSecondary)
                    Spacer(Modifier.height(HearthTheme.spacing.s))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        (sync as? SyncState.Failed)?.let { f ->
            item {
                BannerCard(
                    title = "Library sync failed",
                    body = f.message,
                    icon = HearthTheme.icons.offline,
                    actions = {
                        TextButton(onClick = {
                            c.appScope.launch { c.sync.fullSync(runCatching { c.api.scanStatus()?.lastScan }.getOrNull()) }
                        }) { Text("Try again") }
                    },
                )
            }
        }

        // Always the same four, so they're where your thumb expects. Downloads lives in Your Library.
        item {
            QuickGrid(
                listOf(
                    QuickTile("Shuffle my library", HearthTheme.icons.shuffle, accent) { actions.play(PlaySource.MyLibrary, shuffle = true) },
                    QuickTile("Liked Songs", HearthTheme.icons.likeOn, HearthTheme.colors.liked) { actions.open("liked") },
                    QuickTile("Podcasts", HearthTheme.icons.podcast, HearthTheme.colors.podcasts) { actions.openLibraryTab("PODCASTS") },
                    QuickTile("Audiobooks", HearthTheme.icons.audiobook, HearthTheme.colors.audiobooks) { actions.openLibraryTab("AUDIOBOOKS") },
                )
            )
        }

        if (spokenAccount != null) {
            continueListeningSection(continueListening, c, actions)
            newEpisodesSection(newEpisodes)
        }

        items(StatsPeriod.current(), key = { "stats:${it.key}" }) { StatsCard(it) }

        if (serverMixes.isNotEmpty()) {
            // The Daily Mix and Discover Weekly the server builds overnight, with music you don't have yet.
            item {
                SectionHeader("Your mixes")
                LazyRow(contentPadding = PaddingValues(horizontal = HearthTheme.spacing.gutter), horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.carouselSpacing)) {
                    items(serverMixes, key = { it.playlist.id }) { item ->
                        val p = item.playlist
                        MediaCard(p.name, ServerMixes.description(p), onClick = { actions.openPlaylist(p.id) }, subtitleLines = 2, coverArt = p.coverArt)
                    }
                }
            }
        } else if (mixes.isNotEmpty()) {
            // Until the server has made any (or while offline): mixes built on the phone from your own library.
            item {
                SectionHeader("Your mixes")
                LazyRow(contentPadding = PaddingValues(horizontal = HearthTheme.spacing.gutter), horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.carouselSpacing)) {
                    items(mixes.size) { i ->
                        val mix = mixes[i]
                        MediaCard(mix.title, mix.subtitle, onClick = { actions.open("mix/${android.net.Uri.encode(mix.title)}") }, subtitleLines = 2, cover = { MixCover(mix, HearthTheme.spacing.cardWidth) })
                    }
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item {
                SectionHeader("Your playlists")
                LazyRow(contentPadding = PaddingValues(horizontal = HearthTheme.spacing.gutter), horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.carouselSpacing)) {
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

internal data class QuickTile(
    val title: String,
    val icon: ImageVector,
    val color: Color,
    val onClick: () -> Unit,
)

/** A fixed two-by-two grid, the same on every screen size. */
@Composable
internal fun QuickGrid(tiles: List<QuickTile>) {
    val columns = 2
    Column(Modifier.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.m), verticalArrangement = Arrangement.spacedBy(HearthTheme.spacing.s)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.s), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { t -> QuickTileView(t, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QuickTileView(t: QuickTile, modifier: Modifier) {
    Row(
        modifier.heightIn(min = 56.dp).clip(HearthTheme.shapes.card).background(HearthTheme.colors.surfaceHigh).clickable(onClick = t.onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).background(t.color), contentAlignment = Alignment.Center) {
            Icon(t.icon, null, tint = if (t.color == MaterialTheme.colorScheme.primary) MaterialTheme.colorScheme.onPrimary else HearthTheme.colors.onColorFill)
        }
        Text(
            t.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = HearthTheme.spacing.xs),
        )
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/** Songs plus podcast episodes and audiobooks downloading to this phone, for the downloads icon in page headers. */
fun AppContainer.allDownloadCounts(): Flow<DownloadCounts> =
    combine(downloads.counts, spoken.downloads.rows) { songs, rows ->
        DownloadCounts(
            queued = songs.queued + rows.count { it.state == SpokenDownloadState.QUEUED },
            downloading = songs.downloading + rows.count { it.state == SpokenDownloadState.DOWNLOADING },
            failed = songs.failed + rows.count { it.state == SpokenDownloadState.FAILED },
        )
    }
