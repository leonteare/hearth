package im.flume.hearth.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.api.AlbumDto
import im.flume.hearth.api.ArtistInfo
import im.flume.hearth.api.ItemDate
import im.flume.hearth.container
import im.flume.hearth.data.AlbumEntity
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.formatLongDuration
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

private fun cleanHtml(html: String?): String? {
    if (html.isNullOrBlank()) return null
    val text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        .replace(Regex("\\s*Read more on Last\\.fm\\.?\\s*$", RegexOption.IGNORE_CASE), "")
        .trim()
    return text.takeIf { it.isNotBlank() }
}

private fun formatDate(d: ItemDate?): String? {
    val year = d?.year?.takeIf { it > 0 } ?: return null
    val month = d.month?.takeIf { it in 1..12 } ?: return year.toString()
    val monthName = Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault())
    val day = d.day?.takeIf { it in 1..31 } ?: return "$monthName $year"
    return runCatching { LocalDate.of(year, month, day) }.map { "$day $monthName $year" }.getOrDefault("$monthName $year")
}

/** Collapsible block of prose: 4 lines until tapped. */
@Composable
fun AboutText(title: String, text: String, link: String?) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    SectionHeader(title)
    Column(Modifier.padding(horizontal = 16.dp).animateContentSize()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (link != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Read more on Last.fm",
                color = Accent,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
        }
    }
}

/** Small print under an album's track list, Spotify-style, plus album notes if the server has any. */
@Composable
fun AlbumDetails(album: AlbumEntity?, albumId: String, songCount: Int, totalSec: Long) {
    val c = LocalContext.current.container
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    var full by remember(albumId) { mutableStateOf<AlbumDto?>(null) }
    var notes by remember(albumId) { mutableStateOf<String?>(null) }
    var notesLink by remember(albumId) { mutableStateOf<String?>(null) }
    LaunchedEffect(albumId, online) {
        if (!online) return@LaunchedEffect
        full = runCatching { c.api.album(albumId) }.getOrNull()
        runCatching { c.api.albumInfo(albumId) }.getOrNull()?.let { info ->
            notes = cleanHtml(info.notes)
            notesLink = info.lastFmUrl?.takeIf { notes != null }
        }
    }
    val released = formatDate(full?.releaseDate) ?: album?.year?.toString()
    val original = formatDate(full?.originalReleaseDate)?.takeIf { it != released }
    val lines = listOfNotNull(
        released?.let { "Released $it" },
        original?.let { "Originally released $it" },
        "$songCount songs, ${formatLongDuration(totalSec)}",
        full?.recordLabels?.map { it.name }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "© $it" },
        (full?.genre ?: album?.genre)?.takeIf { it.isNotBlank() },
    )
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = TextSecondary) }
    }
    notes?.let { AboutText("About this album", it, notesLink) }
}

/** Biography and similar artists (only ones in your library), shown at the bottom of an artist page. */
@Composable
fun ArtistAbout(artistId: String) {
    val c = LocalContext.current.container
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    var info by remember(artistId) { mutableStateOf<ArtistInfo?>(null) }
    LaunchedEffect(artistId, online) {
        if (!online) return@LaunchedEffect
        info = runCatching { c.api.artistInfo(artistId) }.getOrNull()
    }
    val bio = cleanHtml(info?.biography)
    if (bio != null) AboutText("About", bio, info?.lastFmUrl)

    val fans = info?.similarArtist.orEmpty().filter { it.id.isNotBlank() && it.albumCount > 0 }
    if (fans.isNotEmpty()) {
        SectionHeader("Fans also like")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(fans, key = { it.id }) { a -> ArtistCard(a.id, a.name, a.coverArt) }
        }
    }
}

@Composable
private fun ArtistCard(id: String, name: String, coverArt: String?) {
    val actions = LocalActions.current
    Column(Modifier.width(110.dp).clickable { actions.openArtist(id) }, horizontalAlignment = Alignment.CenterHorizontally) {
        CoverArt(coverArt, 110.dp, corner = 55.dp, requestSize = 300)
        Spacer(Modifier.height(8.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
