package im.flume.hearth.data

import im.flume.hearth.api.SubsonicException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSharingTest {
    @Test
    fun `round trip keeps human text and sharing`() {
        val sharing = Sharing(owner = "leon", members = mapOf("wife" to ShareRole.ADD), invites = mapOf("guest" to ShareRole.VIEW))
        val comment = PlaylistSharing.serialize("Road trip songs\nsecond line", sharing)
        assertTrue(comment.startsWith("Road trip songs\nsecond line\nhearth:{"))
        val parsed = PlaylistSharing.parse(comment)
        assertEquals("Road trip songs\nsecond line", parsed.text)
        assertEquals(sharing, parsed.sharing)
    }

    @Test
    fun `plain comment has no sharing`() {
        val parsed = PlaylistSharing.parse("Just a note")
        assertEquals("Just a note", parsed.text)
        assertNull(parsed.sharing)
        assertNull(PlaylistSharing.parse(null).sharing)
        assertEquals("", PlaylistSharing.parse("").text)
    }

    @Test
    fun `sharing line alone`() {
        val parsed = PlaylistSharing.parse("""hearth:{"v":1,"owner":"Leon","members":{"Wife":"add"}}""")
        assertEquals("", parsed.text)
        assertEquals("leon", parsed.sharing?.owner)
        assertEquals(mapOf("wife" to ShareRole.ADD), parsed.sharing?.members)
        assertEquals(emptyMap<String, ShareRole>(), parsed.sharing?.invites)
    }

    @Test
    fun `malformed sharing line is ignored and dropped from the text`() {
        val parsed = PlaylistSharing.parse("Note\nhearth:{not json")
        assertEquals("Note", parsed.text)
        assertNull(parsed.sharing)
        assertNull(PlaylistSharing.parse("hearth:").sharing)
        assertNull(PlaylistSharing.parse("hearth:[1,2]").sharing)
    }

    @Test
    fun `unknown roles and extra fields are skipped`() {
        val parsed = PlaylistSharing.parse("""hearth:{"v":2,"future":true,"members":{"a":"admin","b":"view"},"invites":{"c":"ADD"}}""")
        assertEquals(mapOf("b" to ShareRole.VIEW), parsed.sharing?.members)
        assertEquals(mapOf("c" to ShareRole.ADD), parsed.sharing?.invites)
        assertNull(parsed.sharing?.owner)
    }

    @Test
    fun `the last sharing line wins and old ones are cleaned up`() {
        val comment = "hi\nhearth:{\"members\":{\"a\":\"add\"}}\nhearth:{\"members\":{\"b\":\"view\"}}"
        val parsed = PlaylistSharing.parse(comment)
        assertEquals("hi", parsed.text)
        assertEquals(mapOf("b" to ShareRole.VIEW), parsed.sharing?.members)
        assertEquals(1, PlaylistSharing.serialize(parsed.text, parsed.sharing).lines().count { it.startsWith("hearth:") })
    }

    @Test
    fun `nobody shared means no sharing line`() {
        assertEquals("Note", PlaylistSharing.serialize("Note\n", Sharing(owner = "leon")))
        assertEquals("", PlaylistSharing.serialize("", null))
    }

    @Test
    fun `invite accept and remove`() {
        var s = Sharing(owner = "leon").withRole("Wife", ShareRole.VIEW)
        assertEquals(mapOf("wife" to ShareRole.VIEW), s.invites)
        s = s.withRole("wife", ShareRole.ADD)
        assertEquals(mapOf("wife" to ShareRole.ADD), s.invites)
        s = s.accept("WIFE")
        assertEquals(mapOf("wife" to ShareRole.ADD), s.members)
        assertTrue(s.invites.isEmpty())
        // Changing a member's role keeps them a member rather than re-inviting.
        s = s.withRole("wife", ShareRole.VIEW)
        assertEquals(mapOf("wife" to ShareRole.VIEW), s.members)
        assertTrue(s.invites.isEmpty())
        assertTrue(s.remove("wife").isEmpty)
        // Accepting without an invite changes nothing.
        assertEquals(s, s.accept("someone"))
    }
}

class PlaylistRulesTest {
    private fun pl(id: String, owner: String?, sharing: Sharing? = null) =
        PlaylistEntity(id, "P$id", 3, 600, null, owner, null, sharing?.let { PlaylistSharing.serialize("", it) })

    private val mine = pl("1", "leon")
    private val hers = pl("2", "wife")
    private val sharedAdd = pl("3", "wife", Sharing(owner = "wife", members = mapOf("leon" to ShareRole.ADD)))
    private val sharedView = pl("4", "wife", Sharing(members = mapOf("leon" to ShareRole.VIEW)))
    private val invited = pl("5", "wife", Sharing(owner = "wife", invites = mapOf("leon" to ShareRole.ADD)))
    private val legacy = pl("6", null)
    private val all = listOf(mine, hers, sharedAdd, sharedView, invited, legacy)

    private fun ids(list: List<PlaylistItem>) = list.map { it.playlist.id }

    @Test
    fun `others' playlists are hidden`() {
        val items = PlaylistRules.items(all, "Leon", emptyMap())
        assertEquals(listOf("1", "3", "4", "5", "6"), ids(PlaylistRules.visible(items)))
        assertEquals(listOf("1", "3", "4", "6"), ids(PlaylistRules.joined(items)))
        assertEquals(listOf("5"), ids(PlaylistRules.pendingInvites(items)))
    }

    @Test
    fun `only owners and can-add members can edit`() {
        val items = PlaylistRules.items(all, "leon", emptyMap())
        assertEquals(listOf("1", "3", "6"), ids(PlaylistRules.editable(items)))
        val view = PlaylistRules.access(sharedView, "leon")
        assertFalse(view.canEdit)
        assertTrue(view.isMember)
        assertEquals("wife", view.owner) // falls back to the Navidrome owner
        val add = PlaylistRules.access(sharedAdd, "leon")
        assertTrue(add.canEdit)
        assertFalse(add.isOwner)
    }

    @Test
    fun `the owner sees their shared playlist as owner`() {
        val a = PlaylistRules.access(sharedAdd, "wife")
        assertTrue(a.isOwner)
        assertTrue(a.isShared)
        assertNull(a.invite)
    }

    @Test
    fun `local answers apply before the server has them`() {
        val accepted = PlaylistRules.access(invited, "leon", mapOf("5" to true))
        assertEquals(ShareRole.ADD, accepted.role)
        assertNull(accepted.invite)
        assertTrue(accepted.canEdit)
        val declined = PlaylistRules.access(invited, "leon", mapOf("5" to false))
        assertFalse(declined.visible)
        val left = PlaylistRules.access(sharedAdd, "leon", mapOf("3" to false))
        assertFalse(left.visible)
        // An accepted invite that the owner since withdrew doesn't come back.
        assertFalse(PlaylistRules.access(hers, "leon", mapOf("2" to true)).visible)
    }

    @Test
    fun `admin error names the owner`() {
        val msg = PlaylistRules.editError(SubsonicException(50, "nope"), "leon", "fallback")
        assertTrue(msg.contains("ask Leon"))
        assertEquals("fallback", PlaylistRules.editError(java.io.IOException(), "leon", "fallback"))
        assertEquals("fallback", PlaylistRules.editError(SubsonicException(70, "missing"), "leon", "fallback"))
    }
}
