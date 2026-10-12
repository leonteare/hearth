// The list under each Your Library tab (playlists, artists, albums, podcasts, audiobooks) and its rows.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import im.flume.hearth.data.AlbumEntity
import im.flume.hearth.data.ArtistEntity
import im.flume.hearth.data.PlaylistItem
import im.flume.hearth.data.PlaylistRules
import im.flume.hearth.data.SearchFilter
import im.flume.hearth.spoken.BookRow
import im.flume.hearth.spoken.PodcastEntity
import im.flume.hearth.ui.components.Actions
import im.flume.hearth.ui.components.IconTile
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.theme.HearthTheme

internal fun LazyListScope.playlistsList(playlists: List<PlaylistItem>, actions: Actions) {
    item {
        MediaRow(
            "New playlist", null,
            onClick = { actions.newPlaylist() },
            cover = { IconTile(HearthTheme.icons.add, HearthTheme.colors.surfaceHigh) },
        )
    }
    item {
        MediaRow("Liked Songs", null, onClick = { actions.open("liked") }, cover = { IconTile(HearthTheme.icons.likeOn, HearthTheme.colors.liked) })
    }
    // Downloads aren't here: the downloads icon at the top of every page opens them.
    items(playlists, key = { it.playlist.id }) { item -> PlaylistListRow(item) }
    item(key = "all-songs") { BrowseAllRow("All songs") { actions.open("browse/songs") } }
}

internal fun LazyListScope.artistsList(artists: List<ArtistEntity>, actions: Actions) {
    if (artists.isEmpty()) item {
        LibraryEmpty(
            "No artists yet",
            "Tap Follow on an artist's page, or save one of their albums, and they'll show up here.",
            "Browse all artists",
        ) { actions.open("browse/artists") }
    }
    items(artists, key = { it.id }) { ArtistRow(it) }
    if (artists.isNotEmpty()) item(key = "browse-artists") { BrowseAllRow("Browse all artists") { actions.open("browse/artists") } }
}

internal fun LazyListScope.albumsList(albums: List<AlbumEntity>, actions: Actions) {
    if (albums.isEmpty()) item {
        LibraryEmpty(
            "No saved albums yet",
            "Tap the heart on an album to save it here. Albums you download are saved too.",
            "Browse all albums",
        ) { actions.open("browse/albums") }
    }
    items(albums, key = { it.id }) { AlbumListRow(it) }
    if (albums.isNotEmpty()) item(key = "browse-albums") { BrowseAllRow("Browse all albums") { actions.open("browse/albums") } }
}

internal fun LazyListScope.podcastsList(
    signedIn: Boolean,
    noneFollowed: Boolean,
    podcasts: List<PodcastEntity>,
    newCounts: Map<String, Int>,
    importOpml: () -> Unit,
    actions: Actions,
) {
    if (!signedIn) {
        item { SpokenSignInPrompt("podcasts") }
        return
    }
    // Nothing followed yet: say what the tab is for first, then how to start.
    // Once you follow something these move to the "+" above the list.
    if (noneFollowed) {
        item(key = "no-podcasts") {
            LibraryEmpty(
                "No podcasts yet",
                "Search for a show to follow, or grab a single episode. New episodes from shows you follow " +
                    "download on Wi-Fi and leave your phone once you've finished them.",
            )
        }
        // The Search tab itself (not a page on top of this one), so Your Library still comes back here.
        item(key = "add-podcast") {
            MediaRow(
                "Find a podcast", null,
                onClick = { actions.openSearchTab(SearchFilter.PODCASTS) },
                cover = { IconTile(HearthTheme.icons.search, HearthTheme.colors.surfaceHigh) },
            )
        }
        item(key = "import-opml") {
            MediaRow(
                "Import podcasts (OPML)", "Bring the shows you follow from another app",
                onClick = importOpml,
                cover = { IconTile(HearthTheme.icons.importFile, HearthTheme.colors.surfaceHigh) },
            )
        }
    }
    items(podcasts, key = { "pod:${it.id}" }) { PodcastListRow(it, newCounts[it.id] ?: 0) }
}

internal fun LazyListScope.audiobooksList(
    sort: Int,
    signedIn: Boolean,
    books: List<BookRow>,
    noPhoneBooks: Boolean,
    syncing: Boolean,
    actions: Actions,
) {
    item(key = "phone-books") { PhoneAudiobooks(sort) }
    when {
        !signedIn -> item { SpokenSignInPrompt("audiobooks") }
        books.isEmpty() && noPhoneBooks -> item {
            LibraryEmpty(
                "No audiobooks yet",
                if (syncing) "Getting the list from the server…" else "Audiobooks on your server show up here. You can also add a folder of audiobooks on this phone in Settings → Storage.",
                "Find an audiobook",
            ) { actions.openSearchTab(SearchFilter.BOOKS) }
        }
    }
    if (signedIn) items(books, key = { "book:${it.book.id}" }) { BookListRow(it) }
}

/** The quiet count at the bottom of every Your Library list. */
internal fun LazyListScope.libraryFooter(text: String) {
    item {
        Text(
            text,
            color = HearthTheme.colors.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(HearthTheme.spacing.l),
        )
    }
}

@Composable
private fun PlaylistListRow(item: PlaylistItem) {
    val actions = LocalActions.current
    val p = item.playlist
    val a = item.access
    val owner = PlaylistRules.displayName(a.owner)
    val subtitle = when {
        a.invite != null -> "$owner invited you"
        a.isOwner -> "Playlist • ${plural(p.songCount, "song")}"
        else -> "Playlist • by $owner"
    }
    MediaRow(
        p.name, subtitle, onClick = { actions.openPlaylist(p.id) }, coverArt = p.coverArt,
        trailing = if (a.invite != null) {
            { Box(Modifier.padding(end = HearthTheme.spacing.m).size(10.dp).clip(CircleShape).background(HearthTheme.colors.noticeDot).semantics { contentDescription = "New invite" }) }
        } else null,
    )
}

@Composable
private fun LibraryEmpty(title: String, body: String, button: String? = null, onBrowse: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.xxl, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(HearthTheme.spacing.s))
        Text(body, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (button != null) {
            Spacer(Modifier.height(HearthTheme.spacing.m))
            TextButton(onClick = onBrowse) { Text(button) }
        }
    }
}

/**
 * A quiet link at the end of a Your Library list to everything on the server, so the full lists
 * aren't only reachable from an empty library.
 */
@Composable
private fun BrowseAllRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(HearthTheme.icons.chevron, null, tint = HearthTheme.colors.textSecondary)
    }
}
