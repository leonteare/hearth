package im.flume.hearth.ui.screens

// Bottom sheets opened from the spoken-word player: playback speed, sleep timer and the chapter list.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import im.flume.hearth.container
import im.flume.hearth.data.Usage
import im.flume.hearth.playback.PlaybackService
import im.flume.hearth.playback.PlayerUiState
import im.flume.hearth.playback.SpokenNowPlaying
import im.flume.hearth.spoken.ChapterEntity
import im.flume.hearth.spoken.ChapterMarks
import im.flume.hearth.spoken.SpokenFormat
import im.flume.hearth.spoken.SpokenSpeed
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.theme.HearthTheme
import kotlin.math.abs

/** Any speed from 0.5× to 3×: drag, nudge by 0.05 or tap a common one. Changes are heard straight away. */
@Composable
internal fun SpeedSheet(speed: Float, onDismiss: () -> Unit, onSpeed: (Float) -> Unit) {
    var value by remember { mutableStateOf(SpokenSpeed.snap(speed)) }
    fun set(v: Float) {
        val snapped = SpokenSpeed.snap(v)
        if (snapped != value) { value = snapped; onSpeed(snapped) }
    }
    val colors = HearthTheme.colors
    val icons = HearthTheme.icons
    val spacing = HearthTheme.spacing
    ActionSheet(true, onDismiss, title = "Playback speed", showCover = false) {
        Text(
            SpokenFormat.speed(value), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s), textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = spacing.s), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { set(SpokenSpeed.step(value, up = false)) }, enabled = value > SpokenSpeed.MIN) {
                Icon(icons.slower, "Slower")
            }
            Slider(
                value = value, onValueChange = { set(it) },
                valueRange = SpokenSpeed.MIN..SpokenSpeed.MAX,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
            )
            IconButton(onClick = { set(SpokenSpeed.step(value, up = true)) }, enabled = value < SpokenSpeed.MAX) {
                Icon(icons.faster, "Faster")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = spacing.gutter, vertical = spacing.m),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SpokenSpeed.PRESETS.forEach { p ->
                val on = abs(p - value) < 0.001f
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(HearthTheme.shapes.card)
                        .background(if (on) MaterialTheme.colorScheme.primary else colors.surfaceHigh)
                        .clickable(role = Role.Button) { set(p) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(SpokenFormat.speed(p), color = if (on) colors.onAccent else colors.text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun SpokenSleepSheet(sleepAt: Long, hasChapters: Boolean, isBook: Boolean, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val pick = { ms: Long ->
        if (ms != 0L) c.usage.track(Usage.SLEEP)
        c.player.setSleepTimer(ms)
        onDismiss()
    }
    val icons = HearthTheme.icons
    ActionSheet(true, onDismiss, title = "Sleep timer", showCover = false) {
        if (hasChapters) MenuItem("End of this chapter", icons.timer, checked = sleepAt == PlaybackService.SLEEP_END_OF_CHAPTER) { pick(PlaybackService.SLEEP_END_OF_CHAPTER) }
        listOf(15, 30, 45, 60).forEach { m -> MenuItem("$m minutes", icons.timer) { pick(m * 60_000L) } }
        // A book is several files, so "end of this file" would stop at an odd place; episodes are one file.
        if (!isBook) MenuItem("End of this episode", icons.timer, checked = sleepAt == PlaybackService.SLEEP_END_OF_SONG) { pick(PlaybackService.SLEEP_END_OF_SONG) }
        if (sleepAt != 0L) MenuItem("Turn off timer", icons.timerOff, tint = MaterialTheme.colorScheme.primary, tintText = true) { pick(0) }
    }
}

@Composable
internal fun ChaptersSheet(chapters: List<ChapterEntity>, spoken: SpokenNowPlaying, state: PlayerUiState, onDismiss: () -> Unit) {
    val player = LocalContext.current.container.player
    val pos = rememberSpokenPosition(state)
    val current = ChapterMarks.current(chapters, pos / 1000.0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    ActionSheet(true, onDismiss, title = "Chapters", subtitle = plural(chapters.size, "chapter"), showCover = false) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), state = listState) {
            itemsIndexed(chapters, key = { _, ch -> ch.chapterIndex }) { i, ch ->
                ChapterRow(i, ch.title, ch.startSec, ch.endSec - ch.startSec, current = i == current, done = i < current) {
                    player.seekSpoken((ch.startSec * 1000).toLong())
                    if (!state.isPlaying) player.resume()
                    onDismiss()
                }
            }
        }
    }
}
