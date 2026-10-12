@file:OptIn(UnstableApi::class)

package im.flume.hearth.core.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import im.flume.hearth.domain.music.PlaySource
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_AFTER_KEY
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_BEFORE_KEY
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_EPISODE_ID
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_ITEM_ID
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_KEY
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_MANUAL
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_POSITION_MS
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_SHUFFLE
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_SLEEP_MS
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_SONG_ID
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_SONG_IDS
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_SOURCE
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_SPEED
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_START_ID
import im.flume.hearth.core.playback.PlaybackService.Companion.ARG_START_SEC
import im.flume.hearth.core.playback.PlaybackService.Companion.BUTTON_COMMANDS
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_ADD_TO_QUEUE
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_BACK_TO_MUSIC
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_LIKE
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_MOVE_AFTER
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_MOVE_NEXT
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_PLAY_NEXT
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_PLAY_SOURCE
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_PLAY_SPOKEN
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_REMOVE
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_REMOVE_QUEUED
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_RESTORE
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_SEEK_SPOKEN
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_SET_SPEED
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_SKIP_BACK
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_SKIP_FORWARD
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_SKIP_TO
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_SLEEP
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_TOGGLE_SHUFFLE
import im.flume.hearth.core.playback.PlaybackService.Companion.CUSTOM_COMMANDS
import im.flume.hearth.core.playback.PlaybackService.Companion.SKIP_BACK_SEC
import im.flume.hearth.core.playback.PlaybackService.Companion.SKIP_FORWARD_SEC
import im.flume.hearth.domain.spoken.SpokenKey
import kotlinx.coroutines.launch

/**
 * PlaybackService's MediaSession callback: who may connect and which commands they get, the app's
 * own custom commands (queue edits, spoken word, sleep timer), items added by other controllers,
 * and picking up where we left off when Play is pressed after the app was killed.
 */
internal class SessionCallback(private val service: PlaybackService) : MediaSession.Callback {
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        with(service) {
            // System UI, Bluetooth and other apps get the standard player controls only; Hearth's own
            // queue and playback commands are for this app.
            if (controller.packageName != packageName) {
                // Only the Like and Shuffle buttons, so the lock screen and notification can use them.
                val buttons = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(CMD_LIKE, Bundle.EMPTY))
                    .add(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
                    .add(SessionCommand(CMD_SKIP_BACK, Bundle.EMPTY))
                    .add(SessionCommand(CMD_SKIP_FORWARD, Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(buttons).build()
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
                CUSTOM_COMMANDS.forEach { add(SessionCommand(it, Bundle.EMPTY)) }
            }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        with(service) {
            if (!CommandAccess.allowed(fromThisApp = controller.packageName == packageName, customCommand.customAction)) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            when (customCommand.customAction) {
                CMD_PLAY_SOURCE -> scope.launch {
                    playSource(
                        PlaySource.fromBundle(args.getBundle(ARG_SOURCE) ?: Bundle.EMPTY),
                        args.getString(ARG_START_ID),
                        args.getBoolean(ARG_SHUFFLE),
                    )
                }
                CMD_PLAY_NEXT -> scope.launch { insertSongs(args.getStringArrayList(ARG_SONG_IDS).orEmpty(), next = true) }
                CMD_ADD_TO_QUEUE -> scope.launch { insertSongs(args.getStringArrayList(ARG_SONG_IDS).orEmpty(), next = false) }
                CMD_TOGGLE_SHUFFLE -> toggleShuffle()
                CMD_LIKE -> toggleLike()
                CMD_MOVE_NEXT -> moveToNext(args.getString(ARG_KEY), args.getString(ARG_SONG_ID))
                CMD_SKIP_TO -> skipTo(args.getString(ARG_KEY), args.getString(ARG_SONG_ID))
                CMD_REMOVE -> removeEntry(args.getString(ARG_KEY), args.getString(ARG_SONG_ID))
                CMD_MOVE_AFTER -> moveAfter(args.getString(ARG_KEY), args.getString(ARG_SONG_ID), args.getString(ARG_AFTER_KEY))
                CMD_RESTORE -> args.getString(ARG_SONG_ID)?.let { songId ->
                    scope.launch {
                        restoreEntry(songId, args.getString(ARG_KEY), args.getBoolean(ARG_MANUAL), args.getString(ARG_AFTER_KEY), args.getString(ARG_BEFORE_KEY))
                    }
                }
                CMD_REMOVE_QUEUED -> scope.launch { removeQueued(args.getStringArrayList(ARG_SONG_IDS).orEmpty()) }
                CMD_SLEEP -> setSleepTimer(args.getLong(ARG_SLEEP_MS))
                CMD_PLAY_SPOKEN -> args.getString(ARG_ITEM_ID)?.let { itemId ->
                    scope.launch {
                        playSpoken(itemId, args.getString(ARG_EPISODE_ID), args.getDouble(ARG_START_SEC, Double.NaN).takeUnless { it.isNaN() })
                    }
                }
                CMD_SKIP_BACK -> skipSpokenBy(-SKIP_BACK_SEC)
                CMD_SKIP_FORWARD -> skipSpokenBy(SKIP_FORWARD_SEC)
                CMD_SEEK_SPOKEN -> seekSpokenTo(args.getLong(ARG_POSITION_MS) / 1000.0)
                CMD_SET_SPEED -> setSpeed(args.getFloat(ARG_SPEED, 1f))
                CMD_BACK_TO_MUSIC -> scope.launch { backToMusic() }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** Items added by other controllers (e.g. system UI) carry only an id; look them up locally. */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        with(service) {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                val songs = c.library.songsByIds(mediaItems.map { it.mediaId })
                future.set(songs.map { it.toMediaItem() }.toMutableList())
            }
            return future
        }
    }

    /** Play pressed on headphones / the car after the app was killed: pick up where we left off. */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        with(service) {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                restoring?.cancel()
                store.load()?.takeIf { it.spokenItemId != null }?.let { saved ->
                    val spoken = loadSavedSpoken(saved)
                    if (spoken == null) {
                        future.setException(UnsupportedOperationException("Nothing to resume"))
                        return@launch
                    }
                    val (now, items, start) = spoken
                    spokenNow = now
                    followBookDetails(now)
                    c.spoken.downloads.current = SpokenKey(now.libraryItemId, now.episodeId.orEmpty())
                    context = emptyList(); pending = ArrayDeque(); shuffled = false; sourceLabel = saved.label
                    tracker.begin(now, start.first, start.second)
                    spokenNeedsServerCheck = true
                    player.setPlaybackSpeed(c.db.spoken().speed(now.libraryItemId) ?: 1f)
                    publishExtras()
                    val (index, ms) = now.timeline.locate(start.first)
                    future.set(MediaSession.MediaItemsWithStartPosition(items, index, ms))
                    return@launch
                }
                val loaded = loadSavedItems()
                if (loaded == null) {
                    future.setException(UnsupportedOperationException("Nothing to resume"))
                    return@launch
                }
                applySavedState(loaded.saved)
                publishExtras()
                future.set(MediaSession.MediaItemsWithStartPosition(loaded.items, loaded.startIndex, loaded.startMs))
            }
            return future
        }
    }
}

/**
 * Which custom commands a controller may send. Other apps (system UI, Bluetooth, the car) get the
 * lock-screen buttons only, so nothing outside Hearth can rewrite the queue.
 */
internal object CommandAccess {
    fun allowed(fromThisApp: Boolean, action: String): Boolean = fromThisApp || action in BUTTON_COMMANDS
}
