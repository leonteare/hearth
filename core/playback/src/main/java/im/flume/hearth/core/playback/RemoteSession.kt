// What the media session tells Android while a Sonos speaker has the sound: which state and position it
// reports, which commands it offers, and the volume. Plain sums with no Android and no speaker in them, so
// everything the session says can be tested on the JVM.
package im.flume.hearth.core.playback

import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import kotlin.math.abs

/**
 * The media session has to speak for whatever is actually making the sound. While a speaker has it, the
 * phone's own player is stopped, and a session that reported that is a session Android believes is idle:
 * it then ignores the volume keys, and the notification, the lock screen and a car's buttons all talk to
 * the silent player instead of the speaker. So the session reports the speaker's state and sends the
 * speaker the commands. On this phone none of it applies and every answer is the player's own.
 */
object RemoteSession {
    /**
     * The transport commands the session has to offer while a speaker has the sound. The phone's own player
     * is stopped and offers almost nothing, which greys out the notification's buttons.
     */
    val SPEAKER_COMMANDS: List<Int> = listOf(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_PREPARE,
        Player.COMMAND_STOP,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE,
    )

    /** Further than this from the position the speaker last gave is somebody seeking, not a second passing. */
    const val SEEK_SLOP_MS = 5_000L

    /**
     * Whether a speaker is the thing playing. Unlike the volume (see [RemoteVolume.onSpeaker]) this doesn't
     * wait for the speaker to answer: the session must speak for the speaker from the moment it has the
     * sound, or the first thing Android is told is that nothing is playing.
     */
    fun onSpeaker(output: AudioOutput): Boolean = output is AudioOutput.Sonos

    /** The commands to add; none on this phone, where the player's own already fit. */
    fun commands(onSpeaker: Boolean): List<Int> = if (onSpeaker) SPEAKER_COMMANDS else emptyList()

    /**
     * Ready while a speaker has a queue to play, whatever the phone's stopped player says. A speaker with
     * nothing to play is left as idle: there is no song for a notification to show.
     */
    fun state(onSpeaker: Boolean, hasQueue: Boolean, phoneState: Int): Int =
        if (onSpeaker && hasQueue) Player.STATE_READY else phoneState

    /** Playing, and play-when-ready with it, follow the speaker while it has the sound. */
    fun playing(onSpeaker: Boolean, speakerPlaying: Boolean, phonePlaying: Boolean): Boolean =
        if (onSpeaker) speakerPlaying else phonePlaying

    /** The speaker's position, which [SonosPolling.positionNow] has already carried on between its answers. */
    fun position(speakerMs: Long): Long = speakerMs.coerceAtLeast(0)

    /** The speaker's own length for what it's playing, or the song's as the phone knows it until it says. */
    fun duration(speakerMs: Long, knownMs: Long): Long = if (speakerMs > 0) speakerMs else knownMs

    /** Which song of the phone's queue the speaker is on; its own when the speaker's track isn't in there. */
    fun index(speakerIndex: Int, queueSize: Int, phoneIndex: Int): Int =
        if (speakerIndex in 0 until queueSize) speakerIndex else phoneIndex

    /** Whether the speaker jumped rather than played on since its last answer, so the session is told. */
    fun seeked(lastMs: Long, nowMs: Long): Boolean = abs(nowMs - lastMs) > SEEK_SLOP_MS
}

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
