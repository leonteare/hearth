package im.flume.hearth.feature.player

// Lyrics in place of the cover on Now Playing: loading, synced lines that follow the song, and instrumental-break dots.

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.domain.music.Lyrics
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.domain.music.currentIndex
import im.flume.hearth.domain.music.rows
import im.flume.hearth.core.playback.PlayerUiState
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.ui.screens.LYRIC_GAPS
import im.flume.hearth.core.ui.screens.LYRIC_SIZES
import im.flume.hearth.core.ui.screens.rememberPosition

@Composable
internal fun LyricsView(song: SongEntity?, state: PlayerUiState, modifier: Modifier) {
    val c = LocalContext.current.container
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    var loading by remember(song?.id) { mutableStateOf(true) }
    var lyrics by remember(song?.id) { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(song?.id) {
        if (song == null) return@LaunchedEffect
        lyrics = c.lyrics.get(song, online)
        loading = false
    }

    val l = lyrics
    when {
        loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        l == null -> LyricsMessage("Lyrics aren't available offline for this song", modifier)
        l.isEmpty -> LyricsMessage("No lyrics for this song", modifier)
        else -> SyncedOrPlainLyrics(l, state, modifier)
    }
}

@Composable
private fun LyricsMessage(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(text, color = HearthTheme.colors.textSecondary) }
}

@Composable
private fun SyncedOrPlainLyrics(lyrics: Lyrics, state: PlayerUiState, modifier: Modifier) {
    val c = LocalContext.current.container
    val player = c.player
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val fontSize = LYRIC_SIZES.getOrElse(settings.lyricsSize) { 22 }.sp
    val gap = LYRIC_GAPS.getOrElse(settings.lyricsSpacing) { 8 }.dp
    val pos = rememberPosition(state)
    val text = HearthTheme.colors.text
    val rows = remember(lyrics) { lyrics.rows() }
    val current = rows.currentIndex(pos)
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(vertical = HearthTheme.spacing.xl)) {
        itemsIndexed(rows) { i, row ->
            if (row.isBreak) {
                val active = i == current
                val start = row.start
                val end = row.end
                val progress = when {
                    active && start != null && end != null && end > start ->
                        ((pos - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                    i < current -> 1f
                    else -> 0f
                }
                BreakDots(active, progress, Modifier.padding(vertical = gap + 20.dp))
            } else {
                val active = !lyrics.synced || i == current
                val past = lyrics.synced && i < current
                Text(
                    row.text,
                    fontSize = if (lyrics.synced) fontSize else fontSize * 0.8f,
                    lineHeight = (if (lyrics.synced) fontSize else fontSize * 0.8f) * 1.25f,
                    fontWeight = if (lyrics.synced) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        active -> text
                        past -> text.copy(alpha = 0.55f)
                        else -> text.copy(alpha = 0.35f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = lyrics.synced && row.start != null,
                            interactionSource = null,
                            indication = null,
                        ) { player.seekTo(row.start ?: 0) }
                        .padding(vertical = if (lyrics.synced) gap else gap / 3),
                )
            }
        }
    }
}

/** Instrumental break: three small dots that slowly fill with white, one after another, until the next line. */
@Composable
private fun BreakDots(active: Boolean, progress: Float, modifier: Modifier = Modifier) {
    val smooth by animateFloatAsState(progress, tween(300, easing = LinearEasing), label = "dots")
    val dot = HearthTheme.colors.text
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.s), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val lit = (smooth * 3 - i).coerceIn(0f, 1f)
            val alpha = when {
                active -> 0.3f + 0.7f * lit
                progress >= 1f -> 0.55f
                else -> 0.3f
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot.copy(alpha = alpha)))
        }
    }
}
