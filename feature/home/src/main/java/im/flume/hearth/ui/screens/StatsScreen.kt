package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.flume.hearth.container
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.Ranked
import im.flume.hearth.data.Stats
import im.flume.hearth.data.StatsCalculator
import im.flume.hearth.data.StatsPeriod
import im.flume.hearth.ui.components.CoverGradientHeader
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.components.TopBar
import im.flume.hearth.ui.components.LoadingPage
import im.flume.hearth.ui.theme.AccentDeep
import im.flume.hearth.ui.theme.HearthTheme
import java.time.Month
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.TextStyle
import java.util.Locale

@Composable
private fun rememberStats(period: StatsPeriod): Stats? {
    val c = LocalContext.current.container
    var stats by remember(period) { mutableStateOf<Stats?>(null) }
    LaunchedEffect(period) {
        val r = period.range
        val rows = c.db.library().history(r.first, r.last + 1)
        // A year can be thousands of plays; keep the grouping off the main thread.
        stats = withContext(Dispatchers.Default) { StatsCalculator.summarize(rows) }
    }
    return stats
}

private fun hours(minutes: Long): String = if (minutes >= 120) "${minutes / 60} hours" else "$minutes minutes"

/** "Your September" card on Home; hidden when there's nothing to show. */
@Composable
fun StatsCard(period: StatsPeriod) {
    val actions = LocalActions.current
    val stats = rememberStats(period) ?: return
    if (stats.plays == 0) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = HearthTheme.spacing.gutter, vertical = 6.dp)
            .clip(HearthTheme.shapes.card)
            .background(Brush.horizontalGradient(listOf(AccentDeep, HearthTheme.colors.surfaceHigh)))
            .clickable { actions.open("stats/${period.key}") }
            .padding(HearthTheme.spacing.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (period.isYear) "${period.title} in music" else period.title, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(hours(stats.minutes), stats.topArtists.firstOrNull()?.let { "Top artist: ${it.name}" }).joinToString(" · "),
                color = HearthTheme.colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(HearthTheme.icons.chevron, null, tint = HearthTheme.colors.textSecondary)
    }
}

@Composable
fun StatsScreen(key: String) {
    val period = remember(key) { StatsPeriod(key) }
    val actions = LocalActions.current
    val stats = rememberStats(period) ?: return LoadingPage()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = HearthTheme.spacing.xxl)) {
        item {
            CoverGradientHeader(AccentDeep) {
                Column {
                    TopBar(null)
                    Text(period.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = HearthTheme.spacing.l))
                    Text("in music", color = HearthTheme.colors.textSecondary, modifier = Modifier.padding(horizontal = HearthTheme.spacing.l))
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.padding(horizontal = HearthTheme.spacing.l), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            if (stats.minutes >= 120) "${stats.minutes / 60}" else "${stats.minutes}",
                            if (stats.minutes >= 120) "hours listened" else "minutes listened",
                            Modifier.weight(1f),
                        )
                        StatTile("${stats.songCount}", "songs", Modifier.weight(1f))
                        StatTile("${stats.artistCount}", "artists", Modifier.weight(1f))
                    }
                }
            }
        }
        if (stats.plays == 0) {
            item { Text("Nothing played yet in this period.", color = HearthTheme.colors.textSecondary, modifier = Modifier.padding(HearthTheme.spacing.l)) }
            return@LazyColumn
        }
        item {
            Button(
                onClick = { actions.play(PlaySource(PlaySource.Kind.SONGS, label = "${period.title}: top songs", songIds = stats.topSongIds)) },
                modifier = Modifier.padding(HearthTheme.spacing.l),
            ) {
                Icon(HearthTheme.icons.play, null)
                Spacer(Modifier.width(6.dp))
                Text("Play your top songs")
            }
        }
        item { RankedSection("Top artists", stats.topArtists, round = true) { actions.openArtist(it) } }
        item { RankedSection("Top songs", stats.topSongs) { id -> actions.play(PlaySource(PlaySource.Kind.SONGS, label = period.title, songIds = stats.topSongIds), startSongId = id) } }
        item { RankedSection("Top albums", stats.topAlbums) { actions.openAlbum(it) } }
        if (period.isYear) item { MonthChart(stats.minutesByMonth) }
        stats.busiestDay?.let { day ->
            item {
                SectionHeader("Favourite day to listen")
                Text(day.getDisplayName(TextStyle.FULL, Locale.getDefault()), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = HearthTheme.spacing.l))
            }
        }
        item {
            Text(
                "Counts songs you listened to for at least half their length (or 4 minutes). Skips don't count, and a song on repeat counts at most ${StatsCalculator.MAX_IN_A_ROW} times in a row.",
                color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(HearthTheme.spacing.l),
            )
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(HearthTheme.shapes.card).background(HearthTheme.colors.shade.copy(alpha = 0.3f)).padding(HearthTheme.spacing.m)) {
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RankedSection(title: String, items: List<Ranked>, round: Boolean = false, onClick: (String) -> Unit) {
    if (items.isEmpty()) return
    SectionHeader(title)
    items.forEachIndexed { i, r ->
        MediaRow(
            r.name,
            if (r.detail.endsWith("plays")) r.detail else "${r.detail} · ${r.plays} plays",
            onClick = { onClick(r.id) },
            coverArt = r.coverArt,
            round = round,
            leading = { Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = HearthTheme.colors.textSecondary, modifier = Modifier.width(28.dp)) },
        )
    }
}

@Composable
private fun MonthChart(minutes: List<Long>) {
    val max = (minutes.maxOrNull() ?: 0L).coerceAtLeast(1)
    SectionHeader("Month by month")
    Row(Modifier.fillMaxWidth().height(140.dp).padding(horizontal = HearthTheme.spacing.l), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        minutes.forEachIndexed { i, m ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f * m / max + 0.01f).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.height(HearthTheme.spacing.xs))
                Text(Month.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelMedium, color = HearthTheme.colors.textSecondary)
            }
        }
    }
}
