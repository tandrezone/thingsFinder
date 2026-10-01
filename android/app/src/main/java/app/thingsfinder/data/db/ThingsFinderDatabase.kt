package app.thingsfinder.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlaceEntity::class, BoxEntity::class, ItemEntity::class, BarcodeEntity::class, TombstoneEntity::class],
    version = ThingsFinderDatabase.VERSION,
    exportSchema = true,
)
abstract class ThingsFinderDatabase : RoomDatabase() {
    abstract fun placeDao(): PlaceDao
    abstract fun boxDao(): BoxDao
    abstract fun itemDao(): ItemDao
    abstract fun barcodeDao(): BarcodeDao
    abstract fun tombstoneDao(): TombstoneDao

    companion object {
        const val FILE_NAME = "thingsfinder.db"
        const val VERSION = 3

        /** v2: sync_tombstones, for cloud sync. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_tombstones` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, `deleted_at` INTEGER NOT NULL)",
                )
            }
        }

        /** v3: places.share_token, for a place's add-item / remove-item QR codes. Existing places get a random one. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `places` ADD COLUMN `share_token` TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE `places` SET `share_token` = lower(hex(randomblob(16)))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_places_share_token` ON `places` (`share_token`)")
            }
        }

        fun create(context: Context): ThingsFinderDatabase =
            Room.databaseBuilder(context.applicationContext, ThingsFinderDatabase::class.java, FILE_NAME)
                // Room enables PRAGMA foreign_keys itself when entities declare foreign keys,
                // so ON DELETE CASCADE behaves exactly like the server's SQLite schema.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
