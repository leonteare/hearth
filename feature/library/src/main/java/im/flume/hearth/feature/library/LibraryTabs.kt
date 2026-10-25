package im.flume.hearth.feature.library

/** The top row of Your Library: three choices, so all of them fit on a small phone. */
enum class LibrarySection(val label: String) { MUSIC("Music"), PODCASTS("Podcasts"), AUDIOBOOKS("Audiobooks") }

/**
 * What Your Library lists. Its [name] is also the key for that list's saved sort ("sort-<name>"),
 * so these names must not change, and [im.flume.hearth.core.ui.components.Actions.openLibraryTab] uses them too.
 */
enum class LibraryTab(val label: String, val section: LibrarySection) {
    PLAYLISTS("Playlists", LibrarySection.MUSIC),
    ARTISTS("Artists", LibrarySection.MUSIC),
    ALBUMS("Albums", LibrarySection.MUSIC),
    PODCASTS("Podcasts", LibrarySection.PODCASTS),
    AUDIOBOOKS("Audiobooks", LibrarySection.AUDIOBOOKS),
}

/**
 * Where you are in Your Library. Music keeps its own sub-tab while you visit Podcasts or
 * Audiobooks, so coming back to Music lands on the list you left.
 */
data class LibraryPlace(val section: LibrarySection = LibrarySection.MUSIC, val musicTab: LibraryTab = LibraryTab.PLAYLISTS) {
    val tab: LibraryTab
        get() = when (section) {
            LibrarySection.MUSIC -> musicTab
            LibrarySection.PODCASTS -> LibraryTab.PODCASTS
            LibrarySection.AUDIOBOOKS -> LibraryTab.AUDIOBOOKS
        }
}

object LibraryTabs {
    /** Music's second row, in the order it shows. */
    val musicTabs = LibraryTab.entries.filter { it.section == LibrarySection.MUSIC }

    /** The top row. Kids mode (a kiosk phone) has no Podcasts. */
    fun sections(podcasts: Boolean): List<LibrarySection> =
        if (podcasts) LibrarySection.entries else LibrarySection.entries.filter { it != LibrarySection.PODCASTS }

    /**
     * Where a request from elsewhere in the app (e.g. Home's Podcasts tile) should land: a tab name
     * ("PODCASTS", "ARTISTS") or a section name ("MUSIC"). Anything unknown, or Podcasts when they are
     * hidden, leaves you where you are.
     */
    fun open(from: LibraryPlace, request: String, podcasts: Boolean = true): LibraryPlace {
        val name = request.trim().uppercase()
        val target = LibraryTab.entries.firstOrNull { it.name == name }?.let { goTo(from, it) }
            ?: LibrarySection.entries.firstOrNull { it.name == name }?.let { from.copy(section = it) }
            ?: return from
        return if (!podcasts && target.section == LibrarySection.PODCASTS) from else target
    }

    /** Shows [tab], remembering it as Music's sub-tab when it's one of Music's lists. */
    fun goTo(from: LibraryPlace, tab: LibraryTab): LibraryPlace =
        if (tab.section == LibrarySection.MUSIC) LibraryPlace(LibrarySection.MUSIC, tab) else from.copy(section = tab.section)

    /** Saved form of [place], read back by [restore]. */
    fun save(place: LibraryPlace): String = "${place.section.name}/${place.musicTab.name}"

    /** The place [save] wrote, or the default (Music, Playlists) for anything missing or unreadable. */
    fun restore(saved: String?): LibraryPlace {
        val parts = saved?.split('/') ?: return LibraryPlace()
        val section = LibrarySection.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: LibrarySection.MUSIC
        val music = musicTabs.firstOrNull { it.name == parts.getOrNull(1) } ?: LibraryTab.PLAYLISTS
        return LibraryPlace(section, music)
    }
}
