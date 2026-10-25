package im.flume.hearth.feature.search

import im.flume.hearth.core.domain.SearchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SearchFiltersTest {
    @Test
    fun `kids mode hides the podcasts filter and keeps the rest in order`() {
        val kids = searchFilters(podcasts = false)
        assertFalse(SearchFilter.PODCASTS in kids)
        assertEquals(SearchFilter.entries.filter { it != SearchFilter.PODCASTS }, kids)
        assertEquals(SearchFilter.entries.toList(), searchFilters(podcasts = true))
    }
}
