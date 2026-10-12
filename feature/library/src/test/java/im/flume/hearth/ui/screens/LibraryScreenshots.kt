package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import im.flume.hearth.testing.Samples
import im.flume.hearth.testing.ScreenshotTest
import im.flume.hearth.testing.liveRows
import im.flume.hearth.ui.components.SongRow
import org.junit.Test

/** Your Library's controls and a collection page. Moves to :feature:library with those screens. */
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
}
