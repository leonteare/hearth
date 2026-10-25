// The volume while a Sonos speaker has the sound: the media session reports its volume as remote, so Android
// sends the volume keys to it with the phone locked and draws its own slider, and every change moves the speaker.
@file:OptIn(UnstableApi::class)

package im.flume.hearth.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Where a speaker's volume meets Android's. Plain sums with no Android and no speaker in them, so what the
 * session says about its volume can be tested on the JVM.
 */
object RemoteVolume {
    /** A Sonos speaker counts 0..100, and Android's slider is given the same range. */
    const val MAX = 100

    /** One press of a volume key: twenty-five presses from silent to full. */
    const val STEP = 4

    /**
     * The volume commands a session has to offer before Android hands it the volume keys and shows a
     * slider. Both the plain and the "with flags" forms, because a controller may look for either.
     */
    @Suppress("DEPRECATION")
    val SPEAKER_COMMANDS: List<Int> = listOf(
        Player.COMMAND_GET_DEVICE_VOLUME,
        Player.COMMAND_SET_DEVICE_VOLUME,
        Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
        Player.COMMAND_ADJUST_DEVICE_VOLUME,
        Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
    )

    /**
     * Whether the volume belongs to a speaker rather than to this phone. It takes more than a speaker being
     * chosen: until it has said how loud it is ([volume] of -1, e.g. straight after a restart, when nothing
     * is polling it), a slider starting at silent would turn the music down on the first press. The volume
     * stays the phone's until the speaker answers, which it does as soon as a player screen is on show.
     */
    fun onSpeaker(output: AudioOutput, volume: Int): Boolean = output is AudioOutput.Sonos && volume >= 0

    /** What the session is playing through: a speaker is somewhere else, this phone is its own volume. */
    fun playbackType(onSpeaker: Boolean): Int =
        if (onSpeaker) DeviceInfo.PLAYBACK_TYPE_REMOTE else DeviceInfo.PLAYBACK_TYPE_LOCAL

    /**
     * What the session publishes as the thing making the sound, or null on this phone, where the player's
     * own answer (Android's music stream) is already right.
     */
    fun deviceInfo(onSpeaker: Boolean): DeviceInfo? =
        if (onSpeaker) DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(MAX).build() else null

    /** The commands to add; none on this phone, where the player's own already fit. */
    fun commands(onSpeaker: Boolean): List<Int> = if (onSpeaker) SPEAKER_COMMANDS else emptyList()

    /** The volume to show, for a speaker that hasn't said how loud it is (-1) as well as one that has. */
    fun shown(volume: Int): Int = volume.coerceIn(0, MAX)

    /** Where a volume key press lands: [by] out of 100, never outside the speaker's own range. */
    fun stepped(volume: Int, by: Int): Int = (shown(volume) + by).coerceIn(0, MAX)
}

/**
 * The player PlaybackService's MediaSession publishes, wrapped round [SpokenAwarePlayer].
 *
 * While a Sonos speaker has the sound this phone has no volume of its own to turn up, so the session says
 * its volume is remote - the same thing a Chromecast says. Android then sends the volume keys here even
 * with the phone locked, shows its own volume slider for the speaker, and each change goes to [SonosOutput].
 * On this phone none of it applies: the volume is the phone's and the keys behave as they always did.
 */
internal class RemoteVolumePlayer(service: PlaybackService, p: Player) : ForwardingPlayer(p) {
    private val sonos = service.c.sonos

    /** The session's listeners, because Android only moves its slider once something says the volume moved. */
    private val listeners = CopyOnWriteArrayList<Player.Listener>()

    private var onSpeaker = RemoteVolume.onSpeaker(sonos.output.value, sonos.volumeNow())
    private var shown: Int = RemoteVolume.shown(sonos.volumeNow())
    private var muted = false

    /** Where an unmute goes back to: a speaker's own mute isn't used, silence is volume 0. */
    private var beforeMute = shown

    init {
        // Two things to follow: where the sound is going, and how loud the speaker last said it was (which
        // arrives with everything else it says, see RemoteOutput). Either changes what the session has to
        // tell Android about its volume.
        service.scope.launch {
            combine(sonos.output, service.c.player.outputNow) { out, _ -> out }.collect { changed(it) }
        }
    }

    // ------------------------------------------------------------------ what the session says about its volume

    override fun getDeviceInfo(): DeviceInfo = RemoteVolume.deviceInfo(onSpeaker) ?: super.getDeviceInfo()

    override fun getDeviceVolume(): Int = if (onSpeaker) shown else super.getDeviceVolume()

    override fun isDeviceMuted(): Boolean = if (onSpeaker) muted else super.isDeviceMuted()

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        val extra = RemoteVolume.commands(onSpeaker)
        if (extra.isEmpty()) return base
        return base.buildUpon().addAll(*extra.toIntArray()).build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

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

    /**
     * The sound has moved, or the speaker has said how loud it is. A session whose volume changed hands has
     * to say so: until it does, Android keeps the slider it already had - the phone's while a speaker has
     * the sound, or the speaker's after the sound has come back here.
     */
    private fun changed(output: AudioOutput) {
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

    private companion object {
        /** Media3's volume flags: nothing to ask for, Android shows its own slider either way. */
        const val NO_FLAGS = 0
    }
}
