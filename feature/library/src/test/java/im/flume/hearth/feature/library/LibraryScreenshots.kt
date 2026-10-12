package im.flume.hearth.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import im.flume.hearth.domain.music.AlbumItem
import im.flume.hearth.domain.music.Discography
import im.flume.hearth.domain.music.PlaylistAccess
import im.flume.hearth.domain.music.PlaylistItem
import im.flume.hearth.domain.music.ShareRole
import im.flume.hearth.domain.music.SongItem
import im.flume.hearth.core.testing.FakeActions
import im.flume.hearth.core.testing.Samples
import im.flume.hearth.core.testing.ScreenshotTest
import im.flume.hearth.core.testing.liveRows
import im.flume.hearth.core.ui.components.CoverArt
import im.flume.hearth.core.ui.components.PlayShuffleButtons
import im.flume.hearth.core.ui.components.SongRow
import org.junit.Test
import im.flume.hearth.core.ui.screens.EqualWidthChips
import im.flume.hearth.core.ui.screens.FollowButton

/** Your Library's controls and lists, a collection page and an artist page. */
class LibraryScreenshots : ScreenshotTest() {
    /** The top of Your Library as LibraryScreen draws it: Music · Podcasts · Audiobooks, then that section's controls. */
    @Composable
    private fun Controls(place: LibraryPlace, sorts: List<String>) = Column {
        EqualWidthChips(LibrarySection.entries, place.section, { it.label }, MaterialTheme.typography.labelLarge, minHeight = 36.dp) {}
        LibraryControls(
            place, showAddPodcasts = place.section == LibrarySection.PODCASTS, onGoTo = {}, onAddPodcasts = {},
            sortOptions = sorts, sort = 0, onSort = {},
        )
    }

    @Test
    fun `library controls for music, on albums`() = snap("controls_music") {
        Controls(LibraryPlace(LibrarySection.MUSIC, LibraryTab.ALBUMS), listOf("Recently added", "A–Z", "Artist"))
    }

    @Test
    fun `library controls for podcasts`() = snap("controls_podcasts") {
        Controls(LibraryPlace(LibrarySection.PODCASTS), listOf("A–Z", "Newest episode"))
    }

    @Composable
    private fun Buttons() = CollectionButtons(
        onPlay = {}, onShuffle = {}, remote = null, downloaded = false, downloadProgress = null,
        onDownloadToggle = {}, saved = true, onSavedChange = {},
    )

    @Test
    fun `an album page header, buttons and track list`() {
        val songs = Samples.songs(6)
        snapScreen("collection_album", live = liveRows(playingId = songs[2].id)) {
            LazyColumn {
                item {
                    CollectionHeader(
                        title = "Long Winter", subtitleLine = "The Quiet Hours • 2021 • 6 songs, 21 min", cover = null,
                        headerColor = Color(0xFF2F5D62), headerIcon = null, remote = null, wide = false, searchable = true,
                        searchOpen = false, query = "", onQueryChange = {}, onOpenSearch = {}, onCloseSearch = {},
                        searchFocus = FocusRequester(), onHeightChanged = {}, headerActions = {},
                        buttons = { Buttons() },
                    )
                }
                // As CollectionScreen does on a phone: the buttons are their own item under the header.
                item { Buttons() }
                collectionSongs(
                    songs, keys = songs.map { it.id }, tracks = null, filtered = null, query = "", showTrackNumbers = true,
                ) { _, song -> SongRow(song, onClick = {}, showCover = false, leading = song.track.toString()) }
            }
        }
    }

    @Test
    fun `your playlists, with one waiting for an answer to an invite`() {
        val yours = PlaylistAccess(owner = "leon", isOwner = true, role = null, invite = null, sharing = null)
        val invited = PlaylistAccess(owner = "sam", isOwner = false, role = null, invite = ShareRole.ADD, sharing = null)
        val playlists = listOf(
            PlaylistItem(Samples.playlist(), yours),
            PlaylistItem(Samples.playlist(id = "pl2", name = "Road Trip", owner = "sam"), invited),
        )
        snap("list_playlists") {
            LazyColumn {
                playlistsList(playlists, FakeActions())
                libraryFooter("2 playlists")
            }
        }
    }

    @Test
    fun `saved albums before anything is saved`() = snap("list_albums_empty") {
        LazyColumn { albumsList(emptyList(), FakeActions()) }
    }

    @Test
    fun `podcasts before following any show`() = snap("list_podcasts_none") {
        LazyColumn {
            podcastsList(
                signedIn = true, noneFollowed = true, podcasts = emptyList(), newCounts = emptyMap(),
                importOpml = {}, actions = FakeActions(),
            )
        }
    }

    @Test
    fun `an artist page header, popular songs and albums`() {
        val songs = Samples.songs(4)
        val albums = listOf(Samples.album(), Samples.album(id = "al2", name = "Harbour Lights", year = 2018))
        snapScreen("artist_page", live = liveRows(playingId = songs[0].id)) {
            ArtistLayout(
                name = "The Quiet Hours",
                headerColor = Color(0xFF5D3A62),
                art = { size -> CoverArt(null, size, Modifier.clip(CircleShape), fallback = "The Quiet Hours") },
                counts = "2 albums • 4 songs",
                buttons = { PlayShuffleButtons(onPlay = {}, onShuffle = {}, extra = { FollowButton(true) {} }) },
                popular = songs.map { SongItem.Owned(it) },
                onPlayOwned = {},
                discography = Discography(albums.map { AlbumItem.Owned(it) }, emptyList()),
            )
        }
    }
}
