package im.flume.hearth.ui.screens

// Spoken-word player controls: title, current chapter, seek bar with chapter gaps, speed, −15 / +30 and the
// mini player's version, plus the circular skip icon they share.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.playback.PlayerUiState
import im.flume.hearth.core.playback.SpokenNowPlaying
import im.flume.hearth.core.data.spoken.ChapterEntity
import im.flume.hearth.spoken.ChapterMarks
import im.flume.hearth.spoken.SpokenFormat
import im.flume.hearth.core.ui.components.BigPlayButton
import im.flume.hearth.core.ui.components.PlayPauseIcon
import im.flume.hearth.core.ui.components.PlayerSlider
import im.flume.hearth.core.ui.components.formatDuration
import im.flume.hearth.core.ui.components.rememberSeekState
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.ui.screens.rememberSpokenPosition

/** Circular arrow with the number of seconds inside: back 15 / forward 30. */
@Composable
internal fun SkipIcon(back: Boolean, seconds: Int, size: Dp) {
    val label = if (back) "Back $seconds seconds" else "Forward $seconds seconds"
    Box(Modifier.size(size).clearAndSetSemantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(
            HearthTheme.icons.replay, null,
            Modifier.fillMaxSize().graphicsLayer { if (!back) scaleX = -1f },
        )
        Text(
            "$seconds", fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.27f).sp,
            modifier = Modifier.padding(top = size * 0.08f),
        )
    }
}


/** The mini player's controls in spoken-word mode. */
@Composable
internal fun SpokenMiniControls(state: PlayerUiState) {
    val player = LocalContext.current.container.player
    IconButton(onClick = { player.skipBack() }) { SkipIcon(back = true, seconds = 15, size = 24.dp) }
    IconButton(onClick = { player.playPause() }) { PlayPauseIcon(state.isPlaying, Modifier.size(30.dp)) }
    IconButton(onClick = { player.skipForward() }) { SkipIcon(back = false, seconds = 30, size = 24.dp) }
}

@Composable
internal fun SpokenControls(
    state: PlayerUiState,
    spoken: SpokenNowPlaying,
    info: SpokenInfo,
    chapters: List<ChapterEntity>,
    openPage: () -> Unit,
    onSpeed: () -> Unit,
    onChapters: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val player = LocalContext.current.container.player
    val accent = MaterialTheme.colorScheme.primary
    val pos = rememberSpokenPosition(state)
    val seek = rememberSeekState()
    val colors = HearthTheme.colors
    val spacing = HearthTheme.spacing
    val totalMs = spoken.durationMs.coerceAtLeast(1)
    val totalSec = totalMs / 1000.0
    val shownMs = if (seek.dragging) (seek.value * totalMs).toLong() else pos
    val posSec = shownMs / 1000.0
    val chapterIndex = ChapterMarks.current(chapters, posSec)
    val chapter = chapters.getOrNull(chapterIndex)
    val ticks = remember(chapters, totalSec) { ChapterMarks.ticks(chapters, totalSec) }

    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = if (spoken.isBook) "Go to book" else "Go to show", onClick = openPage),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(info.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(info.subtitle, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (chapter != null) {
        val left = ChapterMarks.leftInChapter(chapters, posSec, totalSec)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button, onClickLabel = "Chapters", onClick = onChapters),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                chapter.title.ifBlank { "Chapter ${chapterIndex + 1}" }, color = accent, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            left?.let {
                Text(" · " + SpokenFormat.remaining(it, spoken.speed) + " in chapter", color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }

    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }

    PlayerSlider(
        fraction = pos.toFloat() / totalMs,
        seek = seek,
        onSeek = { player.seekSpoken((it * totalMs).toLong()) },
        ticks = ticks,
    )
    Row {
        Text(formatDuration(shownMs / 1000), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        // Time left at the chosen speed, like other podcast apps.
        val leftSec = ((totalMs - shownMs).coerceAtLeast(0) / 1000.0 / spoken.speed.coerceAtLeast(0.1f)).toLong()
        Text("−" + formatDuration(leftSec), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
    }

    Row(Modifier.fillMaxWidth().padding(vertical = spacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onSpeed, modifier = Modifier.size(width = 64.dp, height = 48.dp), contentPadding = PaddingValues(0.dp)) {
            Text(SpokenFormat.speed(spoken.speed), color = if (spoken.speed != 1f) accent else colors.text, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { player.skipBack() }, Modifier.size(56.dp)) {
            SkipIcon(back = true, seconds = 15, size = 40.dp)
        }
        BigPlayButton(state.isPlaying, onClick = { player.playPause() })
        IconButton(onClick = { player.skipForward() }, Modifier.size(56.dp)) {
            SkipIcon(back = false, seconds = 30, size = 40.dp)
        }
        if (chapters.isNotEmpty()) {
            IconButton(onClick = onChapters, Modifier.size(width = 64.dp, height = 48.dp)) { Icon(HearthTheme.icons.chapters, "Chapters") }
        } else {
            Spacer(Modifier.width(64.dp))
        }
    }
    // The sleep timer lives in the ⋮ menu; when it's on, the header says so.
    if (!spoken.isBook) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onOpenQueue) { Icon(HearthTheme.icons.queue, "Up next") }
        }
    }
}
