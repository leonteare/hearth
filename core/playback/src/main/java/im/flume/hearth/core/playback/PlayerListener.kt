@file:OptIn(UnstableApi::class)

package im.flume.hearth.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import im.flume.hearth.core.playback.PlaybackService.Companion.TICK_MS
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A song really started playing (not just restored while paused): prefetch what follows and start scrobbling. */
private fun PlaybackService.onTrackStarted(item: MediaItem) {
    if (item.isSpoken) return // not a song: nothing to prefetch from Navidrome or scrobble
    prefetchUpcoming()
    scrobbler.onTrackStarted(
        item.mediaId,
        item.mediaMetadata.extras?.getString(EXTRA_ALBUM_ID),
        item.mediaMetadata.durationMs ?: 0L,
    )
}

/**
 * Reacts to PlaybackService's player: song changes (prefetch, scrobbling, volume, wake lock), playing
 * and pausing (listening time, spoken-word progress, periodic queue saves), the end-of-song sleep
 * timer, the end of an episode or book, and playback errors (handed to the recovery code).
 */
internal class PlayerListener(private val service: PlaybackService) : Player.Listener {
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        with(service) {
            currentSongId.value = mediaItem?.mediaId
            mediaItem ?: return
            // Offline and the next song isn't on the phone: go straight to one that is, rather than
            // waiting for it to fail (only for automatic advances; a song picked by hand gets its try).
            // Not for audiobooks: their files must play in order.
            val automatic = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            if (PlaybackErrors.skipAheadOffline(mediaItem.isSpoken, automatic, c.network.isOnline.value) { isOnPhone(mediaItem.mediaId) }) {
                scope.launch { jumpToPlayable() }
            }
            applyVolumeLevelling()
            unpinPassedSongs()
            updateWakeMode()
            // A queue restored (or skipped through) while paused isn't "now playing" yet, and shouldn't use data.
            if (player.playWhenReady) {
                startPending = null
                onTrackStarted(mediaItem)
            } else {
                startPending = mediaItem
            }
            scope.launch {
                refill()
                saveQueue()
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        with(service) {
            ticker?.cancel()
            c.network.setPlaying(isPlaying)
            if (isPlaying) {
                consecutiveErrors = 0
                startPending?.let { pending ->
                    startPending = null
                    if (pending.mediaId == player.currentMediaItem?.mediaId) onTrackStarted(pending)
                }
                if (spokenNow != null && spokenNeedsServerCheck) {
                    spokenNeedsServerCheck = false
                    scope.launch { checkServerPosition() }
                }
                ticker = scope.launch {
                    var lastSave = System.currentTimeMillis()
                    while (isActive) {
                        delay(TICK_MS)
                        if (spokenNow != null) tracker.tick(spokenPositionSec(), TICK_MS) else scrobbler.onListened(TICK_MS)
                        // Cheap: unless the queue itself changed, only the position file is written (see QueueStore).
                        if (QueueSaves.positionSaveDue(lastSave, System.currentTimeMillis())) {
                            saveQueue()
                            lastSave = System.currentTimeMillis()
                        }
                    }
                }
            } else {
                saveQueue()
                if (spokenNow != null) {
                    val pos = spokenPositionSec()
                    scope.launch { tracker.pause(pos) }
                }
            }
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        with(service) {
            // A seek while paused (e.g. scrubbing a chapter) is remembered too.
            if (spokenNow != null && reason == Player.DISCONTINUITY_REASON_SEEK && !player.isPlaying) {
                val pos = spokenPositionSec()
                scope.launch { tracker.seeked(pos) }
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        with(service) {
            if (SleepTimerPolicy.endOfSongReached(playWhenReady, reason, sleepAt)) {
                player.pauseAtEndOfMediaItems = false
                sleepAt = 0
                publishExtras()
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        with(service) {
            if (playbackState == Player.STATE_READY) recoveryAttempts = 0
            // Played to the very end: record the end (marks it finished) and send it.
            if (playbackState == Player.STATE_ENDED && spokenNow != null) {
                val now = spokenNow
                val end = now?.timeline?.durationSec ?: 0.0
                scope.launch {
                    tracker.pause(end)
                    // A finished episode or book leaves this phone straight away rather than when the next thing
                    // plays: it's no longer held by the player, so deleting it is safe. Playing it again
                    // just streams it from the server.
                    if (now != null) {
                        c.spoken.downloads.current = null
                        runCatching { c.spoken.downloads.autoDeleteFinished() }
                    }
                }
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        with(service) {
            updateWakeMode()
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        with(service) {
            scope.launch { refill() }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        with(service) {
            val action = PlaybackErrors.classify(error.errorCode, httpStatus(error))
            // Offline mode / no network fail on purpose; only a real failure says anything about the server.
            // (Audiobookshelf failing says nothing about Navidrome.)
            if (PlaybackErrors.blamesServer(action, c.network.cannotStream, spoken = spokenNow != null)) c.network.reportServerFailure()
            scope.launch { recover(action) }
        }
    }
}
