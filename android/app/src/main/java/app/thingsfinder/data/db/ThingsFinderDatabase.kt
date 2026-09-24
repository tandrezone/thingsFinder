package app.thingsfinder.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PlaceEntity::class, BoxEntity::class, ItemEntity::class, BarcodeEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ThingsFinderDatabase : RoomDatabase() {
    abstract fun placeDao(): PlaceDao
    abstract fun boxDao(): BoxDao
    abstract fun itemDao(): ItemDao
    abstract fun barcodeDao(): BarcodeDao

    companion object {
        const val FILE_NAME = "thingsfinder.db"

        fun create(context: Context): ThingsFinderDatabase =
            Room.databaseBuilder(context.applicationContext, ThingsFinderDatabase::class.java, FILE_NAME)
                // Room enables PRAGMA foreign_keys itself when entities declare foreign keys,
                // so ON DELETE CASCADE behaves exactly like the server's SQLite schema.
                .build()
    }
}
