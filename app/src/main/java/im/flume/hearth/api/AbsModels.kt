package im.flume.hearth.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Audiobookshelf (v2.37) response shapes: only the fields Hearth uses. Unknown keys are ignored and
// almost everything is optional, since list (minified) and expanded responses carry different subsets.

@Serializable
data class AbsLoginResponse(val user: AbsUser, val userDefaultLibraryId: String? = null, val serverSettings: AbsServerSettings? = null)

@Serializable
data class AbsServerSettings(val version: String? = null)

@Serializable
data class AbsUser(
    val id: String,
    val username: String,
    val type: String? = null,
    /** Short-lived JWT (1 hour by default). */
    val accessToken: String? = null,
    /** Long-lived, rotating; only returned when asked for with x-return-tokens / x-refresh-token. */
    val refreshToken: String? = null,
    val mediaProgress: List<AbsMediaProgress> = emptyList(),
    val permissions: AbsPermissions? = null,
)

@Serializable
data class AbsPermissions(val download: Boolean = true, val update: Boolean = false, val delete: Boolean = false)

@Serializable
data class AbsMediaProgress(
    val id: String? = null,
    val libraryItemId: String,
    val episodeId: String? = null,
    val duration: Double = 0.0,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    val hideFromContinueListening: Boolean = false,
    /** Server clock, epoch ms. */
    val lastUpdate: Long = 0,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
)

@Serializable
data class AbsLibrariesResponse(val libraries: List<AbsLibrary> = emptyList())

@Serializable
data class AbsLibrary(
    val id: String,
    val name: String,
    /** "podcast" or "book". */
    val mediaType: String,
    val folders: List<AbsFolder> = emptyList(),
)

@Serializable
data class AbsFolder(val id: String, val fullPath: String)

@Serializable
data class AbsItemsPage(
    val results: List<AbsLibraryItem> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
)

@Serializable
data class AbsLibraryItem(
    val id: String,
    val libraryId: String? = null,
    val folderId: String? = null,
    val path: String? = null,
    val mediaType: String? = null,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val isMissing: Boolean = false,
    val media: AbsMedia = AbsMedia(),
)

/** Podcast or book media; which fields are set depends on [AbsLibraryItem.mediaType]. */
@Serializable
data class AbsMedia(
    val id: String? = null,
    val metadata: AbsMetadata = AbsMetadata(),
    val coverPath: String? = null,
    val tags: List<String> = emptyList(),
    // Podcast
    val numEpisodes: Int? = null,
    val autoDownloadEpisodes: Boolean? = null,
    val autoDownloadSchedule: String? = null,
    val maxNewEpisodesToDownload: Int? = null,
    val episodes: List<AbsEpisode>? = null,
    // Book
    val duration: Double? = null,
    val size: Long? = null,
    val numTracks: Int? = null,
    val numChapters: Int? = null,
    val chapters: List<AbsChapter>? = null,
    val tracks: List<AbsAudioTrack>? = null,
)

@Serializable
data class AbsMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val descriptionPlain: String? = null,
    val language: String? = null,
    val explicit: Boolean? = null,
    val genres: List<String> = emptyList(),
    // Podcast
    val author: String? = null,
    val feedUrl: String? = null,
    val imageUrl: String? = null,
    val itunesId: String? = null,
    val type: String? = null,
    // Book (expanded has authors/narrators/series lists; minified has the joined names)
    val authors: List<AbsNamed> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: List<AbsSeries> = emptyList(),
    val authorName: String? = null,
    val narratorName: String? = null,
    val seriesName: String? = null,
    val publishedYear: String? = null,
)

@Serializable
data class AbsNamed(val id: String? = null, val name: String)

@Serializable
data class AbsSeries(val id: String? = null, val name: String, val sequence: String? = null)

@Serializable
data class AbsEpisode(
    val id: String,
    val libraryItemId: String? = null,
    val index: Int? = null,
    val season: String? = null,
    val episode: String? = null,
    val episodeType: String? = null,
    val title: String = "",
    val subtitle: String? = null,
    val description: String? = null,
    val pubDate: String? = null,
    val publishedAt: Long? = null,
    val addedAt: Long = 0,
    val chapters: List<AbsChapter> = emptyList(),
    val audioFile: AbsAudioFile? = null,
    val audioTrack: AbsAudioTrack? = null,
    val duration: Double? = null,
    val size: Long? = null,
)

@Serializable
data class AbsAudioFile(
    val index: Int? = null,
    val ino: String,
    val duration: Double = 0.0,
    val mimeType: String? = null,
    val metadata: AbsFileMetadata? = null,
    val exclude: Boolean = false,
)

@Serializable
data class AbsFileMetadata(val filename: String? = null, val ext: String? = null, val size: Long = 0)

/** An audio file with its place on the item's timeline; [contentUrl] is relative to the server and has no token. */
@Serializable
data class AbsAudioTrack(
    val index: Int = 0,
    val ino: String? = null,
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val title: String? = null,
    val contentUrl: String? = null,
    val mimeType: String? = null,
    val metadata: AbsFileMetadata? = null,
)

@Serializable
data class AbsChapter(val id: Int = 0, val start: Double, val end: Double, val title: String = "")

/** One result of GET /api/search/podcast (the iTunes directory). */
@Serializable
data class AbsPodcastSearchResult(
    val id: Long? = null,
    val title: String = "",
    val artistName: String? = null,
    val description: String? = null,
    val cover: String? = null,
    val genres: List<String> = emptyList(),
    val trackCount: Int? = null,
    val feedUrl: String? = null,
    val pageUrl: String? = null,
    val explicit: Boolean = false,
    val releaseDate: String? = null,
)

@Serializable
data class AbsFeedResponse(val podcast: AbsFeed)

@Serializable
data class AbsFeed(val metadata: AbsFeedMetadata = AbsFeedMetadata(), val episodes: List<JsonElement> = emptyList())

@Serializable
data class AbsFeedMetadata(
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
    val image: String? = null,
    val categories: List<String> = emptyList(),
    val feedUrl: String? = null,
    val language: String? = null,
    val explicit: String? = null,
    val type: String? = null,
    @SerialName("link") val link: String? = null,
)

@Serializable
data class AbsOpmlParseResponse(val feeds: List<AbsOpmlFeed> = emptyList())

@Serializable
data class AbsOpmlFeed(val title: String? = null, val feedUrl: String)

@Serializable
data class AbsLocalSyncResponse(val results: List<AbsLocalSyncResult> = emptyList())

@Serializable
data class AbsLocalSyncResult(val id: String, val success: Boolean = false, val progressSynced: Boolean = false, val error: String? = null)
