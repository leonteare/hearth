package im.flume.hearth.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTabsTest {
    private val start = LibraryPlace()

    @Test
    fun `the library starts on music playlists`() {
        assertEquals(LibraryTab.PLAYLISTS, start.tab)
        assertEquals(LibrarySection.MUSIC, start.section)
    }

    @Test
    fun `opening podcasts or audiobooks switches the top level`() {
        assertEquals(LibraryTab.PODCASTS, LibraryTabs.open(start, "PODCASTS").tab)
        assertEquals(LibraryTab.AUDIOBOOKS, LibraryTabs.open(start, "AUDIOBOOKS").tab)
    }

    @Test
    fun `opening a music list goes to music with that list chosen`() {
        val place = LibraryTabs.open(LibraryPlace(LibrarySection.PODCASTS), "ALBUMS")
        assertEquals(LibrarySection.MUSIC, place.section)
        assertEquals(LibraryTab.ALBUMS, place.tab)
    }

    @Test
    fun `music keeps its list while you visit podcasts`() {
        val onArtists = LibraryTabs.goTo(start, LibraryTab.ARTISTS)
        val away = LibraryTabs.goTo(onArtists, LibraryTab.PODCASTS)
        assertEquals(LibraryTab.PODCASTS, away.tab)
        assertEquals(LibraryTab.ARTISTS, away.copy(section = LibrarySection.MUSIC).tab)
        assertEquals(LibraryTab.ARTISTS, LibraryTabs.open(away, "music").tab)
    }

    @Test
    fun `an unknown request leaves you where you are`() {
        val place = LibraryPlace(LibrarySection.AUDIOBOOKS, LibraryTab.ALBUMS)
        assertEquals(place, LibraryTabs.open(place, "SONGS"))
        assertEquals(place, LibraryTabs.open(place, ""))
    }

    @Test
    fun `requests ignore case and spaces`() {
        assertEquals(LibraryTab.ARTISTS, LibraryTabs.open(start, " artists ").tab)
    }

    @Test
    fun `a saved place comes back the same`() {
        val place = LibraryPlace(LibrarySection.PODCASTS, LibraryTab.ALBUMS)
        assertEquals(place, LibraryTabs.restore(LibraryTabs.save(place)))
    }

    @Test
    fun `a missing or broken saved place falls back to music playlists`() {
        assertEquals(start, LibraryTabs.restore(null))
        assertEquals(start, LibraryTabs.restore("nonsense"))
        assertEquals(LibraryPlace(LibrarySection.AUDIOBOOKS), LibraryTabs.restore("AUDIOBOOKS/PODCASTS"))
    }

    @Test
    fun `music's second row is playlists artists albums`() {
        assertEquals(listOf(LibraryTab.PLAYLISTS, LibraryTab.ARTISTS, LibraryTab.ALBUMS), LibraryTabs.musicTabs)
    }
}
