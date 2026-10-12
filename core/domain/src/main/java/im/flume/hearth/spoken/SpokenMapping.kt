// Turning Audiobookshelf server items into the podcast, book and episode rows stored on the phone.
package im.flume.hearth.spoken

import im.flume.hearth.api.AbsEpisode
import im.flume.hearth.api.AbsLibraryItem

internal fun AbsLibraryItem.toPodcast(old: PodcastEntity?): PodcastEntity = PodcastEntity(
    id = id,
    libraryId = libraryId ?: old?.libraryId.orEmpty(),
    title = media.metadata.title ?: old?.title ?: "Untitled podcast",
    author = media.metadata.author,
    description = media.metadata.descriptionPlain ?: media.metadata.description,
    feedUrl = media.metadata.feedUrl,
    imageUrl = media.metadata.imageUrl,
    language = media.metadata.language,
    explicit = media.metadata.explicit == true,
    tags = media.tags.joinToString("\n"),
    autoDownloadEpisodes = media.autoDownloadEpisodes == true,
    numEpisodes = media.numEpisodes ?: media.episodes?.size ?: old?.numEpisodes ?: 0,
    addedAt = addedAt,
    // Kept as the version the episodes were fetched at; fetchItem sets it.
    updatedAt = old?.updatedAt ?: 0,
    episodesFetchedAt = old?.episodesFetchedAt ?: 0,
)

internal fun AbsLibraryItem.toBook(old: BookEntity?): BookEntity {
    val m = media.metadata
    return BookEntity(
        id = id,
        libraryId = libraryId ?: old?.libraryId.orEmpty(),
        title = m.title ?: old?.title ?: "Untitled book",
        subtitle = m.subtitle,
        author = m.authorName ?: m.authors.joinToString(", ") { it.name }.ifEmpty { null },
        narrator = m.narratorName ?: m.narrators.joinToString(", ").ifEmpty { null },
        series = m.seriesName ?: m.series.joinToString(", ") { s -> s.sequence?.let { "${s.name} #$it" } ?: s.name }.ifEmpty { null },
        description = m.descriptionPlain ?: m.description,
        publishedYear = m.publishedYear,
        durationSec = media.duration ?: old?.durationSec ?: 0.0,
        sizeBytes = media.size ?: old?.sizeBytes ?: 0,
        numTracks = media.numTracks ?: media.tracks?.size ?: old?.numTracks ?: 0,
        numChapters = media.numChapters ?: media.chapters?.size ?: old?.numChapters ?: 0,
        tags = media.tags.joinToString("\n"),
        addedAt = addedAt,
        updatedAt = old?.updatedAt ?: 0,
        detailsFetchedAt = old?.detailsFetchedAt ?: 0,
    )
}

internal fun AbsEpisode.toEntity(libraryItemId: String): EpisodeEntity {
    val file = audioTrack?.let { it.ino to it } ?: audioFile?.let { it.ino to null }
    return EpisodeEntity(
        id = id,
        libraryItemId = libraryItemId,
        title = title.ifBlank { "Untitled episode" },
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        description = description,
        publishedAt = publishedAt ?: addedAt,
        durationSec = duration ?: audioFile?.duration ?: audioTrack?.duration ?: 0.0,
        sizeBytes = size ?: audioFile?.metadata?.size ?: 0,
        ino = file?.first,
        mimeType = audioFile?.mimeType ?: audioTrack?.mimeType,
        season = season?.takeIf { it.isNotBlank() },
        episode = episode?.takeIf { it.isNotBlank() },
        episodeType = episodeType,
        addedAt = addedAt,
        enclosureUrl = enclosure?.url?.trim()?.takeIf { it.isNotEmpty() },
        guid = guid?.takeIf { it.isNotBlank() },
    )
}
