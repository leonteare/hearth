package im.flume.hearth.domain.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import im.flume.hearth.core.data.PlaylistEntity

class ServerMixesTest {
    private fun pl(id: String, name: String, comment: String?, owner: String = "leon") =
        PlaylistEntity(id, name, 0, 0, null, owner, null, comment)

    private val daily = pl("d", "Daily Mix", "Muse and more. Leaves at midnight unless you save it. [hearth-mix]")
    private val weekly = pl("w", "Discover Weekly", "Leaves Sunday night unless you save it. [hearth-mix]")
    private val kept = pl("k", ServerMixes.KEPT_NAME, ServerMixes.KEPT_COMMENT)
    private val road = pl("r", "Road Trip", null)
    private val sadiesDaily = pl("sd", "Daily Mix", "Leaves at midnight. [hearth-mix]", owner = "sadie")

    @Test
    fun `mixes and the kept list stay out of playlists, Home and the picker`() {
        val items = PlaylistRules.items(listOf(weekly, road, daily, kept), "leon", emptyMap())
        assertEquals(listOf("r"), PlaylistRules.visible(items).map { it.playlist.id })
        assertEquals(listOf("r"), PlaylistRules.joined(items).map { it.playlist.id })
        assertEquals(listOf("r"), PlaylistRules.editable(items).map { it.playlist.id })
    }

    @Test
    fun `your mixes are your own, Daily Mix first`() {
        val items = PlaylistRules.items(listOf(weekly, sadiesDaily, daily, road), "leon", emptyMap())
        assertEquals(listOf("d", "w"), ServerMixes.mine(items).map { it.playlist.id })
    }

    @Test
    fun `a mix describes itself without the marker`() {
        assertEquals("Muse and more. Leaves at midnight unless you save it.", ServerMixes.description(daily))
        assertTrue(ServerMixes.isMix(daily))
        assertFalse(ServerMixes.isMix(road))
    }

    @Test
    fun `only downloaded mix songs that aren't liked or kept already are kept`() {
        val keep = ServerMixes.toKeep(listOf("a", "b", "c", "d", "a"), inMixes = setOf("a", "b", "c"), alreadyKept = setOf("b"), liked = setOf("c"))
        assertEquals(listOf("a"), keep)
    }
}
