package im.flume.hearth.core.playback

/**
 * How many upcoming songs to save into the streaming cache ahead of time. On Wi-Fi it's a few, so a
 * dead spot doesn't stop the music; on mobile data each one is a whole song of the user's allowance
 * that may never be played (skips, a changed queue), so it's one, or none at original quality where a
 * single song can be 30–50 MB.
 */
internal object PrefetchPolicy {
    const val UNMETERED = 3
    const val METERED = 1

    /** [bitrateKbps] is the quality streams play at on this connection; 0 means the original file. */
    fun count(metered: Boolean, bitrateKbps: Int): Int = when {
        !metered -> UNMETERED
        bitrateKbps <= 0 -> 0
        else -> METERED
    }

    /** Queue positions to save ahead: the [count] songs after [current], as far as the queue of [size] goes. */
    fun window(current: Int, size: Int, count: Int): IntRange =
        current + 1 until minOf(size, current + 1 + count)

    /** Songs whose pinned bitrate is kept: the current one and up to [ahead] after it (all that can be prefetched). */
    fun pinnedWindow(current: Int, size: Int, ahead: Int): IntRange =
        current..minOf(size - 1, current + ahead)
}

/**
 * Whether the player needs to hold a Wi-Fi lock as well as the CPU wake lock. A Wi-Fi lock keeps the
 * radio fully awake, which costs battery for nothing when the music comes from files on the phone.
 */
internal object WakeModePolicy {
    /** The song playing plus this many after it must all be on the phone to drop the Wi-Fi lock. */
    const val LOOKAHEAD = 2

    /**
     * [onPhone] answers for the current item (first) and those after it, in queue order. True means
     * local playback is enough (no Wi-Fi lock); an empty queue keeps the network mode.
     */
    fun localOnly(onPhone: List<Boolean>): Boolean =
        onPhone.isNotEmpty() && onPhone.take(LOOKAHEAD + 1).all { it }

    /** Queue positions [localOnly] looks at: the current song and [LOOKAHEAD] after it. Null leaves the mode as it is. */
    fun window(current: Int, size: Int): IntRange? =
        if (size == 0 || current !in 0 until size) null
        else current until minOf(size, current + 1 + LOOKAHEAD)
}

/** When the service goes away with the app. */
internal object ServiceLifetime {
    /** Swiping the app away stops the service unless music is playing (or about to). */
    fun stopOnTaskRemoved(playWhenReady: Boolean, itemCount: Int): Boolean = !playWhenReady || itemCount == 0
}
