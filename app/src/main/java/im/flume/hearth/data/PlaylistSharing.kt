package im.flume.hearth.data

import im.flume.hearth.api.SubsonicException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ShareRole(val wire: String) {
    /** Can add, remove and reorder songs, rename and change the photo. */
    ADD("add"),
    /** Can listen only. */
    VIEW("view");

    companion object {
        fun fromWire(s: String?): ShareRole? = entries.firstOrNull { it.wire.equals(s, ignoreCase = true) }
    }
}

/** Who a playlist is shared with in Hearth. Usernames are kept lower-case; Navidrome ignores case. */
data class Sharing(
    val owner: String? = null,
    val members: Map<String, ShareRole> = emptyMap(),
    val invites: Map<String, ShareRole> = emptyMap(),
) {
    val isEmpty: Boolean get() = members.isEmpty() && invites.isEmpty()

    /** Shares with [user] as [role], or stops sharing when null. Existing members keep their place; others get an invite. */
    fun withRole(user: String, role: ShareRole?): Sharing {
        val u = user.lowercase()
        return when {
            role == null -> copy(members = members - u, invites = invites - u)
            u in members -> copy(members = members + (u to role))
            else -> copy(invites = invites + (u to role))
        }
    }

    fun accept(user: String): Sharing {
        val u = user.lowercase()
        val role = invites[u] ?: return this
        return copy(members = members + (u to role), invites = invites - u)
    }

    /** Declining an invite and leaving a playlist are the same: you're no longer on it. */
    fun remove(user: String): Sharing = withRole(user, null)
}

/** A playlist comment split into the human part and Hearth's sharing line. */
data class PlaylistComment(val text: String, val sharing: Sharing?)

/**
 * Sharing details live in the playlist's Navidrome comment so both phones see them: any human text,
 * then a line `hearth:{"v":1,"owner":"leon","members":{"wife":"add"},"invites":{}}`.
 */
object PlaylistSharing {
    const val MARKER = "hearth:"

    @Serializable
    private data class Wire(
        val v: Int = 1,
        val owner: String? = null,
        val members: Map<String, String> = emptyMap(),
        val invites: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; encodeDefaults = true }

    fun parse(comment: String?): PlaylistComment {
        if (comment.isNullOrEmpty()) return PlaylistComment("", null)
        val lines = comment.lines()
        val marker = lines.lastOrNull { it.trimStart().startsWith(MARKER) }
        // Every marker line is dropped from the human text, so a broken one doesn't pile up on rewrite.
        val text = lines.filterNot { it.trimStart().startsWith(MARKER) }.joinToString("\n").trimEnd()
        val sharing = marker?.let { line ->
            runCatching {
                val w = json.decodeFromString(Wire.serializer(), line.trimStart().removePrefix(MARKER).trim())
                Sharing(
                    owner = w.owner?.lowercase()?.takeIf { it.isNotBlank() },
                    members = roles(w.members),
                    invites = roles(w.invites),
                )
            }.getOrNull()
        }
        return PlaylistComment(text, sharing)
    }

    private fun roles(raw: Map<String, String>): Map<String, ShareRole> =
        raw.mapNotNull { (user, role) -> ShareRole.fromWire(role)?.let { user.lowercase() to it } }
            .filter { it.first.isNotBlank() }.toMap()

    /**
     * The comment to write back: the human [text] plus the sharing line. Never empty: Navidrome treats
     * `comment=` as "not given" and would keep the old line (and its invites), so when nobody is shared
     * with an owner-only line stays, or a single space when there's nothing else to write.
     */
    fun serialize(text: String, sharing: Sharing?): String {
        val human = text.trimEnd()
        if (sharing == null || (sharing.isEmpty && sharing.owner == null)) return human.ifEmpty { " " }
        val wire = Wire(
            owner = sharing.owner,
            members = sharing.members.mapValues { it.value.wire },
            invites = sharing.invites.mapValues { it.value.wire },
        )
        val line = MARKER + json.encodeToString(Wire.serializer(), wire)
        return if (human.isEmpty()) line else "$human\n$line"
    }
}

/** What the signed-in user may do with one playlist. */
data class PlaylistAccess(
    /** The Hearth owner, for "by Leon" and messages; null on old rows that don't know it. */
    val owner: String?,
    val isOwner: Boolean,
    /** Set when you've joined someone else's playlist. */
    val role: ShareRole?,
    /** Set while someone has invited you and you haven't answered. */
    val invite: ShareRole?,
    val sharing: Sharing?,
) {
    val canEdit: Boolean get() = isOwner || role == ShareRole.ADD
    val isMember: Boolean get() = !isOwner && role != null
    /** Shown in Your Library: yours, joined, or waiting for an answer. Others' playlists stay hidden. */
    val visible: Boolean get() = isOwner || role != null || invite != null
    val isShared: Boolean get() = sharing?.isEmpty == false
}

data class PlaylistItem(val playlist: PlaylistEntity, val access: PlaylistAccess)

/** Visibility and permission rules, free of Android so they can be unit tested. */
object PlaylistRules {
    private fun same(a: String?, b: String?) = a != null && b != null && a.equals(b, ignoreCase = true)

    /** [decisions]: invites answered on this phone (true = accepted), applied on top of the server's comment. */
    fun access(p: PlaylistEntity, me: String?, decisions: Map<String, Boolean> = emptyMap()): PlaylistAccess {
        val sharing = PlaylistSharing.parse(p.comment).sharing
        val owner = sharing?.owner ?: p.owner?.lowercase()
        // Rows synced before owners were known count as yours, as they always did.
        val isOwner = owner == null || same(owner, me) || same(p.owner, me)
        if (isOwner || me == null) return PlaylistAccess(owner, isOwner, null, null, sharing)
        val u = me.lowercase()
        val member = sharing?.members?.get(u)
        val invited = sharing?.invites?.get(u)
        return when (decisions[p.id]) {
            false -> PlaylistAccess(owner, false, null, null, sharing)
            true -> PlaylistAccess(owner, false, member ?: invited, null, sharing)
            null -> PlaylistAccess(owner, false, member, invited.takeIf { member == null }, sharing)
        }
    }

    fun items(all: List<PlaylistEntity>, me: String?, decisions: Map<String, Boolean>): List<PlaylistItem> =
        all.map { PlaylistItem(it, access(it, me, decisions)) }

    /** Your Library's Playlists tab: yours, joined, and pending invites. */
    fun visible(all: List<PlaylistItem>) = all.filter { it.access.visible }

    /** Home's row: yours and joined only; invites wait in Your Library. */
    fun joined(all: List<PlaylistItem>) = all.filter { it.access.isOwner || it.access.role != null }

    fun pendingInvites(all: List<PlaylistItem>) = all.filter { it.access.invite != null }

    /** The "Add to playlist" picker. */
    fun editable(all: List<PlaylistItem>) = all.filter { it.access.canEdit }

    /** Whether the server's [sharing] (read back after writing) shows [me]'s answer to an invite. */
    fun answerSaved(sharing: Sharing?, me: String, accept: Boolean): Boolean {
        val u = me.lowercase()
        return if (accept) sharing?.members?.containsKey(u) == true
        else sharing == null || (u !in sharing.members && u !in sharing.invites)
    }

    /** Capitalised for display: "leon" becomes "Leon". */
    fun displayName(user: String?): String = user?.replaceFirstChar { it.titlecase() } ?: "the owner"

    /**
     * Message for a failed playlist edit. The admin hint only shows when the server said no and the
     * account is known not to be an admin ([isAdmin] false); an admin being refused gets a plain message.
     */
    fun editError(e: Throwable, owner: String?, isAdmin: Boolean?, fallback: String): String = when {
        e !is SubsonicException || !e.notAuthorized -> fallback
        isAdmin == false ->
            "Only admins can edit playlists they don't own — ask ${displayName(owner)} to make your Navidrome account an admin"
        else -> "Couldn't save the playlist"
    }
}
