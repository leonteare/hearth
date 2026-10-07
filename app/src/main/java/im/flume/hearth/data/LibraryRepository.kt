package im.flume.hearth.data

import android.os.Bundle
import im.flume.hearth.api.SubsonicClient
import im.flume.hearth.download.DownloadRepository

/** Something the user can press play on. Converted to a Bundle to travel to the playback service. */
data class PlaySource(val kind: Kind, val id: String? = null, val label: String = "", val songIds: List<String> = emptyList()) {
    enum class Kind { ALL, ALBUM, ARTIST, PLAYLIST, GENRE, LIKED, DOWNLOADS, SONGS }

    fun toBundle() = Bundle().apply {
        putString("kind", kind.name)
        putString("id", id)
        putString("label", label)
        putStringArrayList("songIds", ArrayList(songIds))
    }

    companion object {
        fun fromBundle(b: Bundle) = PlaySource(
            kind = Kind.valueOf(b.getString("kind") ?: Kind.ALL.name),
            id = b.getString("id"),
            label = b.getString("label").orEmpty(),
            songIds = b.getStringArrayList("songIds").orEmpty(),
        )

        val All = PlaySource(Kind.ALL, label = "Your library")
        val Liked = PlaySource(Kind.LIKED, label = "Liked Songs")
        val Downloads = PlaySource(Kind.DOWNLOADS, label = "Downloads")
    }
}

class LibraryRepository(
    private val db: AppDatabase,
    private val api: SubsonicClient,
    private val downloads: DownloadRepository,
) {
    val dao = db.library()

    /** Songs for [source] in their natural order. When [offlineOnly], keeps just downloaded songs. */
    suspend fun resolve(source: PlaySource, offlineOnly: Boolean): List<SongEntity> {
        val songs = when (source.kind) {
            PlaySource.Kind.ALL -> if (offlineOnly) dao.downloadedSongsOnce() else songsByIds(dao.allSongIds())
            PlaySource.Kind.ALBUM -> dao.albumSongsOnce(source.id!!)
            PlaySource.Kind.ARTIST -> dao.artistSongsOnce(source.id!!)
            PlaySource.Kind.PLAYLIST -> dao.playlistSongsOnce(source.id!!)
            PlaySource.Kind.GENRE -> dao.genreSongsOnce(source.id!!)
            PlaySource.Kind.LIKED -> dao.starredSongsOnce()
            PlaySource.Kind.DOWNLOADS -> dao.downloadedSongsOnce()
            PlaySource.Kind.SONGS -> songsByIds(source.songIds)
        }
        return if (offlineOnly) songs.filter { downloads.isDownloaded(it.id) } else songs
    }

    /** Looks up songs keeping the order of [ids]. Chunked to stay under SQLite's variable limit. */
    suspend fun songsByIds(ids: List<String>): List<SongEntity> {
        val byId = HashMap<String, SongEntity>(ids.size)
        ids.chunked(900).forEach { chunk -> dao.songsByIds(chunk).forEach { byId[it.id] = it } }
        return ids.mapNotNull(byId::get)
    }

    suspend fun setStarred(songId: String, starred: Boolean) {
        dao.setStarred(songId, starred)
        runCatching { if (starred) api.star(songId) else api.unstar(songId) }
            .onFailure { dao.setStarred(songId, !starred); throw it }
    }

    /** Server's "recently played" albums, mapped onto local rows so covers and counts are consistent. */
    suspend fun serverRecentAlbums(limit: Int): List<AlbumEntity> {
        val remote = api.albumList("recent", limit)
        val local = dao.albumsByIds(remote.map { it.id }).associateBy { it.id }
        return remote.map { local[it.id] ?: it.toEntity() }
    }
}
