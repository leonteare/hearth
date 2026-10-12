@file:OptIn(UnstableApi::class)

package im.flume.hearth.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import im.flume.hearth.core.playback.PlaybackService.Companion.SKIP_BACK_SEC
import im.flume.hearth.core.playback.PlaybackService.Companion.SKIP_FORWARD_SEC

/**
 * The player PlaybackService hands to its MediaSession. In spoken-word mode, next/previous
 * (headphones, Bluetooth, car) skip +30 s / −15 s instead of changing track.
 */
internal class SpokenAwarePlayer(private val service: PlaybackService, p: Player) : ForwardingPlayer(p) {
    override fun seekToNext() = if (service.spokenNow != null) service.skipSpokenBy(SKIP_FORWARD_SEC) else super.seekToNext()
    override fun seekToNextMediaItem() = if (service.spokenNow != null) service.skipSpokenBy(SKIP_FORWARD_SEC) else super.seekToNextMediaItem()
    override fun seekToPrevious() = if (service.spokenNow != null) service.skipSpokenBy(-SKIP_BACK_SEC) else super.seekToPrevious()
    override fun seekToPreviousMediaItem() = if (service.spokenNow != null) service.skipSpokenBy(-SKIP_BACK_SEC) else super.seekToPreviousMediaItem()
    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (service.spokenNow == null) return base
        return base.buildUpon().addAll(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        ).build()
    }
    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
}
