// The player the media session publishes, so the session always speaks for whatever is making the sound:
// this phone's own player here, and a Sonos speaker's state, position, song and transport while a speaker
// has the sound - including its volume, which Android then gives the volume keys and its own slider.
@file:OptIn(UnstableApi::class)

package im.flume.hearth.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The player PlaybackService's MediaSession publishes, wrapped round [SpokenAwarePlayer].
 *
 * While a Sonos speaker has the sound this phone's own player is stopped, and a session that reported that
 * is one Android believes is idle: it sends the volume keys nowhere, and the notification, the lock screen,
 * headphones and a car all press buttons on a player that makes no sound. So the session reports what the
 * speaker is doing - playing, where it is, which song, how loud - and every command it is given goes to
 * [SonosOutput]. On this phone every call forwards to the real player exactly as it always did.
 */
internal class OutputAwarePlayer(service: PlaybackService, p: Player) : ForwardingPlayer(p) {
    private val sonos = service.c.sonos
    private val connection = service.c.player

    /** The session's listeners, because Android only changes what it shows once something says it moved. */
    private val listeners = CopyOnWriteArrayList<Player.Listener>()

    private var onSpeaker = RemoteVolume.onSpeaker(sonos.output.value, sonos.volumeNow())
    private var shown: Int = RemoteVolume.shown(sonos.volumeNow())
    private var muted = false

    /** Where an unmute goes back to: a speaker's own mute isn't used, silence is volume 0. */
    private var beforeMute = shown

    // What the session was last told about the speaker, so it is only told again when something changed.
    private var toldRemote = RemoteSession.onSpeaker(sonos.output.value)
    private var toldPlaying = false
    private var toldIndex = -1
    private var toldPositionMs = 0L

    init {
        // Two things to follow: where the sound is going, and what the speaker last said it was doing (its
        // state, position, song and volume all arrive together, see RemoteOutput). Either changes what the
        // session has to tell Android.
        service.scope.launch {
            combine(sonos.output, connection.outputNow) { out, _ -> out }.collect { changed(it) }
        }
    }

    /**
     * Whether a speaker has the sound, asked afresh every time rather than remembered. [SonosOutput] hands
     * the sound back and then tells this phone to carry on, and those commands arrive here a moment later:
     * a remembered answer would send them to the speaker it had just let go of.
     */
    private val remote: Boolean get() = RemoteSession.onSpeaker(sonos.output.value)

    /** What the speaker last said it was doing, or null when it hasn't said anything yet. */
    private fun speaker(): RemoteOutput? = connection.outputNow.value

    // ------------------------------------------------------------------ what the session reports

    override fun getPlaybackState(): Int =
        RemoteSession.state(remote, super.getMediaItemCount() > 0, super.getPlaybackState())

    override fun getPlayWhenReady(): Boolean =
        RemoteSession.playing(remote, speaker()?.isPlaying == true, super.getPlayWhenReady())

    override fun isPlaying(): Boolean =
        RemoteSession.playing(remote, speaker()?.isPlaying == true, super.isPlaying())

    /** Nothing here is holding the sound back while it is coming out of a speaker. */
    override fun getPlaybackSuppressionReason(): Int =
        if (remote) Player.PLAYBACK_SUPPRESSION_REASON_NONE else super.getPlaybackSuppressionReason()

    /** A speaker's troubles are shown in the app in words; a stopped player's old error isn't the session's. */
    override fun getPlayerError(): PlaybackException? = if (remote) null else super.getPlayerError()

    override fun isLoading(): Boolean = if (remote) false else super.isLoading()

    /** A speaker plays at normal speed, so a controller counting the position on gets it right. */
    override fun getPlaybackParameters(): PlaybackParameters =
        if (remote) PlaybackParameters.DEFAULT else super.getPlaybackParameters()

    override fun getCurrentPosition(): Long =
        if (remote) RemoteSession.position(connection.positionMs) else super.getCurrentPosition()

    override fun getContentPosition(): Long = if (remote) currentPosition else super.getContentPosition()

    /** A speaker fetches the music itself, so there is nothing buffered here beyond where it has got to. */
    override fun getBufferedPosition(): Long = if (remote) currentPosition else super.getBufferedPosition()

    override fun getTotalBufferedDuration(): Long = if (remote) 0 else super.getTotalBufferedDuration()

    override fun getDuration(): Long =
        if (remote) RemoteSession.duration(speaker()?.durationMs ?: 0, songLength()) else super.getDuration()

    override fun getContentDuration(): Long = if (remote) duration else super.getContentDuration()

    /** How long the song is as far as this phone knows, for a speaker that hasn't said yet. */
    private fun songLength(): Long =
        super.getDuration().takeIf { it > 0 } ?: (currentMediaItem?.mediaMetadata?.durationMs ?: C.TIME_UNSET)

    // ------------------------------------------------------------------ which song, out of the phone's queue

    override fun getCurrentMediaItemIndex(): Int = if (!remote) super.getCurrentMediaItemIndex() else
        RemoteSession.index(speaker()?.queueIndex ?: -1, super.getMediaItemCount(), super.getCurrentMediaItemIndex())

    override fun getCurrentPeriodIndex(): Int = if (remote) currentMediaItemIndex else super.getCurrentPeriodIndex()

    override fun getCurrentMediaItem(): MediaItem? {
        if (!remote) return super.getCurrentMediaItem()
        val at = currentMediaItemIndex
        return if (at in 0 until super.getMediaItemCount()) super.getMediaItemAt(at) else super.getCurrentMediaItem()
    }

    override fun getMediaMetadata(): MediaMetadata =
        if (remote) currentMediaItem?.mediaMetadata ?: super.getMediaMetadata() else super.getMediaMetadata()

    // ------------------------------------------------------------------ the volume, and what it comes out of

    override fun getDeviceInfo(): DeviceInfo = RemoteVolume.deviceInfo(onSpeaker) ?: super.getDeviceInfo()

    override fun getDeviceVolume(): Int = if (onSpeaker) shown else super.getDeviceVolume()

    override fun isDeviceMuted(): Boolean = if (onSpeaker) muted else super.isDeviceMuted()

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        val extra = RemoteVolume.commands(onSpeaker) + RemoteSession.commands(remote)
        if (extra.isEmpty()) return base
        return base.buildUpon().addAll(*extra.toIntArray()).build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    // ------------------------------------------------------------------ a transport command from Android

    override fun play() = if (remote) sonos.resume() else super.play()

    override fun pause() = if (remote) sonos.pause() else super.pause()

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!remote) return super.setPlayWhenReady(playWhenReady)
        if (playWhenReady) sonos.resume() else sonos.pause()
    }

    /** There is nothing to get ready here: the speaker has the music and fetches it itself. */
    override fun prepare() {
        if (!remote) super.prepare()
    }

    /**
     * Stopping means silence, so the speaker is paused - the queue stays on it and one press of play picks
     * it up again. The sound is not pulled back to this phone: a notification swiped away, or signing out,
     * should not start the music up out of a pocket. Giving the speaker up is [SonosOutput.backToPhone].
     */
    override fun stop() {
        if (remote) sonos.pause()
        super.stop()
    }

    override fun seekTo(positionMs: Long) = if (remote) sonos.seekTo(positionMs) else super.seekTo(positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (!remote) return super.seekTo(mediaItemIndex, positionMs)
        // A song the speaker was never sent stays where it is rather than quietly starting on the phone.
        if (sonos.skipTo(mediaItemIndex) && positionMs > 0) sonos.seekTo(positionMs)
    }

    override fun seekToDefaultPosition() = if (remote) sonos.seekTo(0) else super.seekToDefaultPosition()

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (!remote) return super.seekToDefaultPosition(mediaItemIndex)
        sonos.skipTo(mediaItemIndex)
    }

    override fun seekToNext() = if (remote) sonos.next() else super.seekToNext()

    override fun seekToNextMediaItem() = if (remote) sonos.next() else super.seekToNextMediaItem()

    override fun seekToPrevious() = if (remote) sonos.previous() else super.seekToPrevious()

    override fun seekToPreviousMediaItem() = if (remote) sonos.previous() else super.seekToPreviousMediaItem()

    override fun seekBack() =
        if (remote) sonos.seekTo(currentPosition - seekBackIncrement) else super.seekBack()

    override fun seekForward() =
        if (remote) sonos.seekTo(currentPosition + seekForwardIncrement) else super.seekForward()

    // ------------------------------------------------------------------ a volume change from Android

    override fun setDeviceVolume(volume: Int, flags: Int) {
        if (!onSpeaker) return super.setDeviceVolume(volume, flags)
        toSpeaker(RemoteVolume.shown(volume))
    }

    override fun increaseDeviceVolume(flags: Int) {
        if (!onSpeaker) return super.increaseDeviceVolume(flags)
        toSpeaker(RemoteVolume.stepped(shown, RemoteVolume.STEP))
    }

    override fun decreaseDeviceVolume(flags: Int) {
        if (!onSpeaker) return super.decreaseDeviceVolume(flags)
        toSpeaker(RemoteVolume.stepped(shown, -RemoteVolume.STEP))
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        if (!onSpeaker) return super.setDeviceMuted(muted, flags)
        if (muted == this.muted) return
        this.muted = muted
        if (muted) {
            beforeMute = shown
            toSpeaker(0)
        } else {
            toSpeaker(beforeMute.coerceAtLeast(RemoteVolume.STEP))
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun setDeviceVolume(volume: Int) = setDeviceVolume(volume, NO_FLAGS)

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun increaseDeviceVolume() = increaseDeviceVolume(NO_FLAGS)

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun decreaseDeviceVolume() = decreaseDeviceVolume(NO_FLAGS)

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun setDeviceMuted(muted: Boolean) = setDeviceMuted(muted, NO_FLAGS)

    // ------------------------------------------------------------------ telling the session

    override fun addListener(listener: Player.Listener) {
        listeners.addIfAbsent(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    /**
     * Moves the speaker and shows the new volume straight away, so the slider follows the key press instead
     * of waiting for the speaker's next answer. A speaker that won't take it is [SonosOutput]'s business: it
     * shows the refusal, or gives the sound back, as it does for every other command.
     */
    private fun toSpeaker(level: Int) {
        sonos.setVolume(level)
        shown = level
        listeners.forEach { it.onDeviceVolumeChanged(shown, muted) }
    }

    /** The sound has moved, or the speaker has said what it is doing. Both change what Android is told. */
    private fun changed(output: AudioOutput) {
        volumeChanged(output)
        transportChanged(output)
    }

    /**
     * A session whose volume changed hands has to say so: until it does, Android keeps the slider it
     * already had - the phone's while a speaker has the sound, or the speaker's after it has come back.
     */
    private fun volumeChanged(output: AudioOutput) {
        val was = onSpeaker
        val volume = sonos.volumeNow()
        onSpeaker = RemoteVolume.onSpeaker(output, volume)
        val flipped = onSpeaker != was
        if (flipped) muted = false
        val level = RemoteVolume.shown(volume)
        val moved = level != shown
        shown = level
        if (flipped) {
            listeners.forEach { it.onDeviceInfoChanged(deviceInfo) }
            listeners.forEach { it.onAvailableCommandsChanged(availableCommands) }
        }
        if (flipped || moved) listeners.forEach { it.onDeviceVolumeChanged(shown, muted) }
    }

    /**
     * What the session publishes, told song by song: the sound moving to or from a speaker, the speaker
     * starting or stopping, moving to the next song, or being seeked. Without this the notification and the
     * lock screen keep whatever they were shown when this phone last played.
     */
    private fun transportChanged(output: AudioOutput) {
        val was = toldRemote
        toldRemote = RemoteSession.onSpeaker(output)
        val flipped = toldRemote != was
        val index = currentMediaItemIndex
        val position = currentPosition
        val playing = isPlaying
        // On this phone the player tells the session about itself, as it always did; nothing to add here.
        if (!toldRemote && !flipped) {
            toldPlaying = playing
            toldIndex = index
            toldPositionMs = position
            return
        }
        if (flipped) {
            listeners.forEach { it.onAvailableCommandsChanged(availableCommands) }
            listeners.forEach { it.onPlaybackStateChanged(playbackState) }
        }
        if (index != toldIndex) {
            if (!flipped) discontinuity(index, position, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
            val reason =
                if (flipped) Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                else Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            listeners.forEach { it.onMediaItemTransition(currentMediaItem, reason) }
        } else if (toldRemote && RemoteSession.seeked(toldPositionMs, position)) {
            discontinuity(index, position, Player.DISCONTINUITY_REASON_SEEK)
        }
        if (flipped || playing != toldPlaying) {
            listeners.forEach { it.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE) }
            listeners.forEach { it.onIsPlayingChanged(playing) }
        }
        toldPlaying = playing
        toldIndex = index
        toldPositionMs = position
    }

    private fun discontinuity(index: Int, positionMs: Long, reason: Int) {
        val from = positionInfo(toldIndex, toldPositionMs)
        val to = positionInfo(index, positionMs)
        listeners.forEach { it.onPositionDiscontinuity(from, to, reason) }
    }

    /** Where the session says it was and where it is now; a speaker has no ads and no periods of its own. */
    private fun positionInfo(index: Int, positionMs: Long): Player.PositionInfo {
        val at = index.coerceAtLeast(0)
        return Player.PositionInfo(
            null,
            at,
            if (at < super.getMediaItemCount()) super.getMediaItemAt(at) else null,
            null,
            at,
            positionMs,
            positionMs,
            C.INDEX_UNSET,
            C.INDEX_UNSET,
        )
    }

    private companion object {
        /** Media3's volume flags: nothing to ask for, Android shows its own slider either way. */
        const val NO_FLAGS = 0
    }
}
