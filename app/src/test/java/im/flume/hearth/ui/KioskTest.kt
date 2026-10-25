package im.flume.hearth.ui

import im.flume.hearth.core.network.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskTest {
    private val alice = Credentials("https://music.home", "alice", "s1", "t1")
    private val bob = Credentials("https://music.home", "bob", "s2", "t2")

    @Test
    fun `a profile survives being saved and read back`() {
        assertEquals(alice, Profiles.decode(Profiles.encode(alice)))
    }

    @Test
    fun `a broken saved profile is skipped`() {
        assertNull(Profiles.decode("just-a-url"))
        assertNull(Profiles.decode("a\tb\t\td"))
    }

    @Test
    fun `signing in again replaces that person's token and keeps the order`() {
        val list = Profiles.upsert(Profiles.upsert(emptyList(), alice), bob)
        val again = Profiles.upsert(list, alice.copy(username = "Alice", token = "t9"))
        assertEquals(listOf("Alice", "bob"), again.map { it.username })
        assertEquals("t9", again[0].token)
    }

    @Test
    fun `forgetting a profile removes only that person`() {
        assertEquals(listOf(bob), Profiles.remove(listOf(alice, bob), alice))
    }

    @Test
    fun `names start with a capital`() {
        assertEquals("Alice", Profiles.displayName("alice"))
    }

    @Test
    fun `a pin is four to eight digits`() {
        assertTrue(KioskPin.valid("2580"))
        assertTrue(KioskPin.valid("12345678"))
        assertFalse(KioskPin.valid("123"))
        assertFalse(KioskPin.valid("12a4"))
        assertFalse(KioskPin.valid("123456789"))
    }
}
