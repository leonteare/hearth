package im.flume.hearth.ui.theme

/**
 * The only sizes (in pixels) the UI asks the server for covers at. Each size is its own URL and
 * so its own download and cache entry: sticking to three means a cover fetched for one screen is
 * already there for the next, instead of the same picture being fetched at 120, 150, 200, 600...
 */
object CoverSize {
    /** List rows, the mini player, action sheet headers and colour picking. */
    const val SMALL = 150
    /** Cards, tiles and the spoken-word mini player. */
    const val MEDIUM = 300
    /** Page headers, Now Playing and the visualiser. */
    const val LARGE = 900
}
