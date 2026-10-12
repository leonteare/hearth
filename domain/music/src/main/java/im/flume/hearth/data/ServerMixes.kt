package im.flume.hearth.data

/**
 * The Daily Mix and Discover Weekly the server builds each night (monitoring/hearth-mixes.py on the
 * homelab). They're ordinary Navidrome playlists marked in their comment. Songs fetched for them are
 * deleted when the mix ends unless someone saves them: likes them, adds them to a playlist, or
 * downloads them (Hearth puts those in a hidden "kept" playlist, which the server counts as saved).
 */
object ServerMixes {
    const val MIX_MARKER = "[hearth-mix]"
    const val KEPT_MARKER = "[hearth-kept]"
    const val KEPT_NAME = "Hearth · On my phone"
    const val KEPT_COMMENT = "Songs from your mixes you downloaded, kept on the server. $KEPT_MARKER"

    fun isMix(p: PlaylistEntity) = p.comment?.contains(MIX_MARKER) == true
    fun isKept(p: PlaylistEntity) = p.comment?.contains(KEPT_MARKER) == true

    /** Playlists Hearth manages for you, kept out of the Playlists tab, Home's playlists and pickers. */
    fun isHidden(p: PlaylistEntity) = isMix(p) || isKept(p)

    /** Your own mixes, Daily Mix first. */
    fun mine(all: List<PlaylistItem>): List<PlaylistItem> =
        all.filter { it.access.isOwner && isMix(it.playlist) }.sortedBy { if (it.playlist.name.startsWith("Daily")) 0 else 1 }

    /** What the mix says about itself ("Muse and more. Leaves at midnight unless you save it."). */
    fun description(p: PlaylistEntity): String = p.comment.orEmpty().replace(MIX_MARKER, "").trim()

    /**
     * Downloaded songs that need adding to the kept playlist: in one of your mixes, not liked (liked
     * songs are kept anyway) and not already kept.
     */
    fun toKeep(downloaded: Collection<String>, inMixes: Set<String>, alreadyKept: Set<String>, liked: Set<String>): List<String> =
        downloaded.filter { it in inMixes && it !in alreadyKept && it !in liked }.distinct()
}
