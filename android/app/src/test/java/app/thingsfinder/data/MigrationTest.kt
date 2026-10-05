package app.thingsfinder.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thingsfinder.data.db.ThingsFinderDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds a version-2 database file straight from the exported schema
 * (app/schemas/…/2.json), then lets Room open it with the real migrations —
 * Room checks the migrated tables against the current entities and throws
 * if they differ.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {

    private fun createFromSchema(version: Int, file: File): SQLiteDatabase {
        val schema = File("schemas/${ThingsFinderDatabase::class.java.name}/$version.json")
        val database = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (entity in database["entities"]!!.jsonArray) {
            val e = entity.jsonObject
            val table = e["tableName"]!!.jsonPrimitive.content
            db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        database["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }

    @Test fun `v2 to v3 gives every existing place its own share token`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        createFromSchema(2, file).use { db ->
            db.execSQL("INSERT INTO places (uuid, name, slug, created_at, updated_at) VALUES ('u1', 'Garage', 'garage', 1, 1)")
            db.execSQL("INSERT INTO places (uuid, name, slug, created_at, updated_at) VALUES ('u2', 'Attic', 'attic', 1, 1)")
            db.execSQL("INSERT INTO boxes (uuid, place_id, name, slug, share_token, created_at, updated_at) VALUES ('b1', 1, 'Bin', 'bin', 'abcdefabcdefabcdefabcdefabcdefab', 1, 1)")
        }

        val room = Room.databaseBuilder(context, ThingsFinderDatabase::class.java, file.path)
            .addMigrations(ThingsFinderDatabase.MIGRATION_1_2, ThingsFinderDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        val places = room.placeDao().getAll()
        assertEquals(listOf("Attic", "Garage"), places.map { it.name })
        assertEquals(2, places.map { it.shareToken }.toSet().size)
        assertTrue(places.all { it.shareToken.matches(Regex("^[0-9a-f]{32}$")) })
        assertEquals("Garage", room.placeDao().findByToken(places.last().shareToken)?.name)
        assertEquals("Bin", room.boxDao().findByToken("abcdefabcdefabcdefabcdefabcdefab")?.name)
        room.close()
        file.delete()
        Unit
    }
}
