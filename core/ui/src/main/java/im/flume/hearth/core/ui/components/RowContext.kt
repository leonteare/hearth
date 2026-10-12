// What rows need to know to highlight the playing song and dim ones that can't play.
package im.flume.hearth.core.ui.components

import androidx.compose.runtime.compositionLocalOf
import im.flume.hearth.core.data.DownloadState

/** Current playing song id and offline state, used to highlight / dim rows. */
data class RowContext(val playingId: String?, val online: Boolean, val downloads: Map<String, DownloadState>)

val LocalRowContext = compositionLocalOf { RowContext(null, true, emptyMap()) }
