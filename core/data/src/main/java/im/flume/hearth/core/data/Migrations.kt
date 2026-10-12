// Every Room schema migration, one step per version, registered in AppDatabase.create.
// Each step must match the schema KSP exports to core/data/schemas: MigrationTest checks both.
package im.flume.hearth.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `lyrics` (`songId` TEXT NOT NULL, `json` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))")
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN trackGain REAL")
        db.execSQL("ALTER TABLE songs ADD COLUMN albumGain REAL")
        db.execSQL("ALTER TABLE songs ADD COLUMN trackPeak REAL")
    }
}

/** Playlist comment (holds sharing details) and public flag. Must match [PlaylistEntity] exactly. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_3_4_SQL.forEach(db::execSQL)
    }
}

val MIGRATION_3_4_SQL = listOf(
    "ALTER TABLE playlists ADD COLUMN comment TEXT",
    "ALTER TABLE playlists ADD COLUMN isPublic INTEGER NOT NULL DEFAULT 0",
)

/** When each download finished, and per-song download choices. Must match the entities exactly. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_4_5_SQL.forEach(db::execSQL)
    }
}

val MIGRATION_4_5_SQL = listOf(
    "ALTER TABLE downloads ADD COLUMN completedAt INTEGER NOT NULL DEFAULT 0",
    "CREATE TABLE IF NOT EXISTS `song_download_prefs` (`songId` TEXT NOT NULL, `wanted` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))",
)

/** Song ISRCs (to spot music already on the server) and the Tidarr requests table. Must match the entities exactly. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_5_6_SQL.forEach(db::execSQL)
    }
}

val MIGRATION_5_6_SQL = listOf(
    "ALTER TABLE songs ADD COLUMN isrc TEXT",
    "CREATE TABLE IF NOT EXISTS `requests` (`tidalId` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `coverUuid` TEXT, `isrc` TEXT, `albumTitle` TEXT, `durationSec` INTEGER, `requestedAt` INTEGER NOT NULL, `requestedBy` TEXT NOT NULL, `status` TEXT NOT NULL, `errorMessage` TEXT, `matchedIds` TEXT, `updatedAt` INTEGER NOT NULL, `seenInQueue` INTEGER NOT NULL, `finishedAt` INTEGER, `lookupAttempts` INTEGER NOT NULL, PRIMARY KEY(`type`, `tidalId`))",
)

/** What each request does when it arrives (like it, download it). Must match [RequestEntity] exactly. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_6_7_SQL.forEach(db::execSQL)
    }
}

val MIGRATION_6_7_SQL = listOf(
    "ALTER TABLE requests ADD COLUMN `like` INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE requests ADD COLUMN `toPhone` INTEGER NOT NULL DEFAULT 1",
)

/** Podcasts and audiobooks from Audiobookshelf (new tables only). Must match the spoken entities exactly. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_7_8_SQL.forEach(db::execSQL)
    }
}

val MIGRATION_7_8_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `abs_podcasts` (`id` TEXT NOT NULL, `libraryId` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, `description` TEXT, `feedUrl` TEXT, `imageUrl` TEXT, `language` TEXT, `explicit` INTEGER NOT NULL, `tags` TEXT NOT NULL, `autoDownloadEpisodes` INTEGER NOT NULL, `numEpisodes` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `episodesFetchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `abs_episodes` (`id` TEXT NOT NULL, `libraryItemId` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT, `description` TEXT, `publishedAt` INTEGER NOT NULL, `durationSec` REAL NOT NULL, `sizeBytes` INTEGER NOT NULL, `ino` TEXT, `mimeType` TEXT, `season` TEXT, `episode` TEXT, `episodeType` TEXT, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_abs_episodes_libraryItemId` ON `abs_episodes` (`libraryItemId`)",
    "CREATE INDEX IF NOT EXISTS `index_abs_episodes_publishedAt` ON `abs_episodes` (`publishedAt`)",
    "CREATE TABLE IF NOT EXISTS `abs_books` (`id` TEXT NOT NULL, `libraryId` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT, `author` TEXT, `narrator` TEXT, `series` TEXT, `description` TEXT, `publishedYear` TEXT, `durationSec` REAL NOT NULL, `sizeBytes` INTEGER NOT NULL, `numTracks` INTEGER NOT NULL, `numChapters` INTEGER NOT NULL, `tags` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `detailsFetchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `abs_tracks` (`libraryItemId` TEXT NOT NULL, `trackIndex` INTEGER NOT NULL, `ino` TEXT NOT NULL, `startOffsetSec` REAL NOT NULL, `durationSec` REAL NOT NULL, `mimeType` TEXT, `sizeBytes` INTEGER NOT NULL, PRIMARY KEY(`libraryItemId`, `trackIndex`))",
    "CREATE TABLE IF NOT EXISTS `abs_chapters` (`libraryItemId` TEXT NOT NULL, `episodeId` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `startSec` REAL NOT NULL, `endSec` REAL NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`libraryItemId`, `episodeId`, `chapterIndex`))",
    "CREATE TABLE IF NOT EXISTS `abs_progress` (`libraryItemId` TEXT NOT NULL, `episodeId` TEXT NOT NULL, `currentTime` REAL NOT NULL, `duration` REAL NOT NULL, `isFinished` INTEGER NOT NULL, `lastUpdate` INTEGER NOT NULL, `finishedAt` INTEGER, `dirty` INTEGER NOT NULL, `markedUnplayed` INTEGER NOT NULL, PRIMARY KEY(`libraryItemId`, `episodeId`))",
    "CREATE TABLE IF NOT EXISTS `abs_outbox` (`key` TEXT NOT NULL, `seq` INTEGER NOT NULL, `userId` TEXT NOT NULL, `kind` TEXT NOT NULL, `libraryItemId` TEXT NOT NULL, `episodeId` TEXT NOT NULL, `payload` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
    "CREATE TABLE IF NOT EXISTS `abs_downloads` (`libraryItemId` TEXT NOT NULL, `episodeId` TEXT NOT NULL, `state` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `auto` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `error` TEXT, PRIMARY KEY(`libraryItemId`, `episodeId`))",
    "CREATE TABLE IF NOT EXISTS `abs_play_prefs` (`libraryItemId` TEXT NOT NULL, `speed` REAL NOT NULL, PRIMARY KEY(`libraryItemId`))",
)

/**
 * Episodes remember their feed audio URL and guid, to line them up with the show's full feed.
 * Every podcast is marked "episodes never fetched" so the next sync fills the new columns in.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_8_9_SQL.forEach(db::execSQL)
    }
}

val MIGRATION_8_9_SQL = listOf(
    "ALTER TABLE abs_episodes ADD COLUMN `enclosureUrl` TEXT",
    "ALTER TABLE abs_episodes ADD COLUMN `guid` TEXT",
    "UPDATE abs_podcasts SET episodesFetchedAt = 0",
)

/** Audiobooks from folders on the phone, their files and progress (new tables only). Must match the entities exactly. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_9_10_SQL.forEach(db::execSQL)
    }
}

val MIGRATION_9_10_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `local_books` (`id` TEXT NOT NULL, `treeUri` TEXT NOT NULL, `relPath` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, `durationSec` REAL NOT NULL, `sizeBytes` INTEGER NOT NULL, `numFiles` INTEGER NOT NULL, `coverPath` TEXT, `addedAt` INTEGER NOT NULL, `scannedAt` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, `serverItemId` TEXT, `uploadState` TEXT, `uploadError` TEXT, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `local_book_files` (`bookId` TEXT NOT NULL, `fileIndex` INTEGER NOT NULL, `uri` TEXT NOT NULL, `relPath` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, `durationSec` REAL NOT NULL, `mimeType` TEXT, `title` TEXT, PRIMARY KEY(`bookId`, `fileIndex`))",
    "CREATE TABLE IF NOT EXISTS `local_book_progress` (`bookId` TEXT NOT NULL, `positionSec` REAL NOT NULL, `durationSec` REAL NOT NULL, `finished` INTEGER NOT NULL, `finishedAt` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`))",
)

/**
 * Phone books get details found online (narrator, description…) and remember what their files
 * said, so a match can be undone. Existing books start with their current names as the files' own.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_10_11_SQL.forEach(db::execSQL)
    }
}

val MIGRATION_10_11_SQL = listOf(
    "ALTER TABLE local_books ADD COLUMN `fileTitle` TEXT",
    "ALTER TABLE local_books ADD COLUMN `fileAuthor` TEXT",
    "ALTER TABLE local_books ADD COLUMN `narrator` TEXT",
    "ALTER TABLE local_books ADD COLUMN `description` TEXT",
    "ALTER TABLE local_books ADD COLUMN `publishedYear` TEXT",
    "ALTER TABLE local_books ADD COLUMN `series` TEXT",
    "ALTER TABLE local_books ADD COLUMN `matchState` TEXT",
    "ALTER TABLE local_books ADD COLUMN `matchSource` TEXT",
    "ALTER TABLE local_books ADD COLUMN `matchCheckedAt` INTEGER NOT NULL DEFAULT 0",
    "UPDATE local_books SET fileTitle = title, fileAuthor = author",
)
