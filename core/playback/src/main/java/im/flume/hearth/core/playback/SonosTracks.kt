// Turning what's in the phone's queue into what a speaker needs: the URL it fetches and the metadata it
// refuses to play without.
package im.flume.hearth.core.playback

import androidx.media3.common.MediaItem
import im.flume.hearth.core.network.Credentials
import im.flume.hearth.core.network.SonosMime
import im.flume.hearth.domain.music.playback.SonosStream
import im.flume.hearth.domain.spoken.SonosSpokenFile

/**
 * The same queue entry, addressed to a speaker instead of this phone: a URL on the LAN-only host (the
 * phone's own stream URIs are private schemes only it can resolve) and the title, artist and artwork the
 * speaker shows. Null when this entry is nothing a speaker could fetch - a book kept only on the phone, or
 * music with nobody signed in - and the hand-over then skips it.
 */
internal fun MediaItem.toSonosTrack(baseUrl: String, creds: Credentials?, absToken: String?): SonosTrack? {
    val meta = mediaMetadata
    val title = meta.title?.toString().orEmpty()
    val artist = meta.artist?.toString().orEmpty()
    val album = meta.albumTitle?.toString()
    if (isSpoken) {
        val itemId = spokenItemId ?: return null
        val ino = localConfiguration?.uri?.let { SpokenMedia.parse(it) }?.second ?: return null
        val url = SonosSpokenFile.fileUrl(baseUrl, itemId, ino, absToken) ?: return null
        // Audiobooks are m4b; a podcast episode is an mp3 off the feed. The type has to be the truth: a
        // speaker told the wrong one refuses the track with UPnP error 714.
        val mime = if (spokenEpisodeId == null) SonosMime.MP4 else SonosMime.MP3
        return SonosTrack(url, mime, title, artist, album, SonosSpokenFile.coverUrl(baseUrl, itemId))
    }
    val url = SonosStream.songUrl(baseUrl, mediaId, creds) ?: return null
    val coverArt = meta.extras?.getString(EXTRA_COVER_ART)
    return SonosTrack(url, SonosMime.MP3, title, artist, album, SonosStream.coverUrl(baseUrl, coverArt, creds))
}
