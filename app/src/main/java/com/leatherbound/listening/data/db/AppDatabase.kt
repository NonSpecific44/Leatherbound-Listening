package com.leatherbound.listening.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SourceEntity::class, BookEntity::class, TrackEntity::class, ChapterEntity::class, ListeningDayEntity::class, BookmarkEntity::class,
        CollectionEntity::class, CollectionBookEntity::class],
    version = 4,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "leatherbound.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN connections TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN genres TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collections` (`id` TEXT NOT NULL, `sourceId` INTEGER NOT NULL, " +
                        "`remoteId` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collections_sourceId` ON `collections` (`sourceId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collection_books` (`collectionId` TEXT NOT NULL, `bookId` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, PRIMARY KEY(`collectionId`, `bookId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_books_bookId` ON `collection_books` (`bookId`)")
                // Genres arrive with the next sync; make it happen on the next launch.
                db.execSQL("UPDATE sources SET lastSyncedAt = 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN playbackSpeed REAL")
                db.execSQL("ALTER TABLE books ADD COLUMN scanSignature TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`bookId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
                // Take server tokens out of stored URLs; they are filled in at use time from the encrypted copy.
                db.execSQL(
                    "UPDATE tracks SET url = REPLACE(url, (SELECT s.token FROM sources s JOIN books b ON b.sourceId = s.id WHERE b.id = tracks.bookId), '{token}') " +
                        "WHERE EXISTS (SELECT 1 FROM sources s JOIN books b ON b.sourceId = s.id WHERE b.id = tracks.bookId AND s.token != '')"
                )
                db.execSQL(
                    "UPDATE books SET coverUrl = REPLACE(coverUrl, (SELECT token FROM sources WHERE sources.id = books.sourceId), '{token}') " +
                        "WHERE coverUrl IS NOT NULL AND EXISTS (SELECT 1 FROM sources WHERE sources.id = books.sourceId AND token != '')"
                )
            }
        }
    }
}
