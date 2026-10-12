package im.flume.hearth.feature.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.focus.FocusRequester
import im.flume.hearth.domain.music.AlbumItem
import im.flume.hearth.domain.music.ArtistItem
import im.flume.hearth.core.data.GenreCount
import im.flume.hearth.core.domain.SearchFilter
import im.flume.hearth.core.domain.SearchHit
import im.flume.hearth.core.data.SearchIndex
import im.flume.hearth.domain.music.SongItem
import im.flume.hearth.core.testing.Samples
import im.flume.hearth.core.testing.ScreenshotTest
import org.junit.Test
import im.flume.hearth.core.ui.screens.gridColumns

/** Search's field, filters, landing page and results. */
class SearchScreenshots : ScreenshotTest() {
    @Test
    fun `the empty search field with its hint and the filter chips`() = snap("field_empty") {
        Column {
            SearchField("", onQueryChange = {}, hint = "What do you want to listen to?", focus = FocusRequester(), onSearch = {})
            SearchFilterChips(SearchFilter.ALL) {}
        }
    }

    @Test
    fun `a typed search with songs chosen`() = snap("field_typed") {
        Column {
            SearchField("northern", onQueryChange = {}, hint = "What do you want to listen to?", focus = FocusRequester(), onSearch = {})
            SearchFilterChips(SearchFilter.SONGS) {}
        }
    }

    @Test
    fun `search results mixing an artist, an album, songs and a show`() {
        val hits = listOf(
            SearchHit.Artist(ArtistItem.Owned(Samples.artist()), 100),
            SearchHit.Album(AlbumItem.Owned(Samples.album()), 90),
            SearchHit.Song(SongItem.Owned(Samples.song()), 80),
            SearchHit.Song(SongItem.Owned(Samples.song(id = "s2", title = "Northern Rain", starred = true)), 70),
            SearchHit.Podcast(Samples.podcast(), 60),
        )
        snapScreen("results") {
            LazyColumn {
                searchResults(
                    query = "northern", filter = SearchFilter.ALL, indexing = false,
                    library = SearchIndex.Results(emptyList(), emptyList(), emptyList(), fuzzy = false),
                    hits = hits, shown = hits, directoryLoading = false, podcasts = listOf(Samples.podcast()),
                    username = null, playSong = {},
                )
            }
        }
    }

    @Test
    fun `the landing page with recent searches and genres to browse`() = snap("landing") {
        LazyColumn {
            searchLanding(
                recent = listOf("northern", "quiet hours"),
                onClearRecent = {},
                onPickRecent = {},
                genres = listOf("Ambient", "Folk", "Jazz", "Electronic", "Classical").map { GenreCount(it, 10) },
                gridColumns = 2,
                onOpenGenre = {},
            )
        }
    }

    @Test
    fun `no results says so`()= snap("no_results") {
        LazyColumn {
            searchResults(
                query = "zzz", filter = SearchFilter.ALL, indexing = false,
                library = SearchIndex.Results(emptyList(), emptyList(), emptyList(), fuzzy = false),
                hits = emptyList(), shown = emptyList(), directoryLoading = false, podcasts = emptyList(),
                username = null, playSong = {},
            )
        }
    }
}
