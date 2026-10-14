// The Room database: its tables, its version, and how it is opened. Migrations live in Migrations.kt.
package im.flume.hearth.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import im.flume.hearth.core.data.spoken.BookEntity
import im.flume.hearth.core.data.spoken.ChapterEntity
import im.flume.hearth.core.data.spoken.EpisodeEntity
import im.flume.hearth.core.data.spoken.LocalBookDao
import im.flume.hearth.core.data.spoken.LocalBookEntity
import im.flume.hearth.core.data.spoken.LocalBookFileEntity
import im.flume.hearth.core.data.spoken.LocalBookProgressEntity
import im.flume.hearth.core.data.spoken.OutboxEntity
import im.flume.hearth.core.data.spoken.PodcastEntity
import im.flume.hearth.core.data.spoken.SpokenDao
import im.flume.hearth.core.data.spoken.SpokenDownloadEntity
import im.flume.hearth.core.data.spoken.SpokenPlayPrefEntity
import im.flume.hearth.core.data.spoken.SpokenProgressEntity
import im.flume.hearth.core.data.spoken.TrackEntity

@Database(
    entities = [
        SongEntity::class, AlbumEntity::class, ArtistEntity::class, PlaylistEntity::class,
        PlaylistSongEntity::class, DownloadEntity::class, PinnedEntity::class,
        PlayHistoryEntity::class, PendingScrobbleEntity::class, LyricsEntity::class,
        SongDownloadPrefEntity::class, RequestEntity::class, LikeOutboxEntity::class,
        PodcastEntity::class, EpisodeEntity::class,
        BookEntity::class, TrackEntity::class,
        ChapterEntity::class, SpokenProgressEntity::class,
        OutboxEntity::class, SpokenDownloadEntity::class,
        SpokenPlayPrefEntity::class,
        LocalBookEntity::class, LocalBookFileEntity::class,
        LocalBookProgressEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun likeOutbox(): LikeOutboxDao
    abstract fun downloads(): DownloadDao
    abstract fun lyrics(): LyricsDao
    abstract fun requests(): RequestDao
    abstract fun spoken(): SpokenDao
    abstract fun localBooks(): LocalBookDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "hearth.db")
                // No destructive fallback: a missing migration should fail loudly, not wipe downloads and history.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .build()
    }
}
