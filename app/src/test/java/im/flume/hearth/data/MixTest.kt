package im.flume.hearth.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MixTest {
    @Test
    fun `seeds skip artists similar to ones already chosen`() {
        val top = listOf("beatles", "stones", "muse", "radiohead", "mcr")
        val similar = mapOf("beatles" to setOf("stones"), "muse" to setOf("radiohead"))
        assertEquals(listOf("beatles", "muse", "mcr"), MixRepository.pickSeeds(top, similar))
    }

    @Test
    fun `without similarity data the top three are used`() {
        assertEquals(listOf("a", "b", "c"), MixRepository.pickSeeds(listOf("a", "b", "c", "d"), emptyMap()))
    }
}
