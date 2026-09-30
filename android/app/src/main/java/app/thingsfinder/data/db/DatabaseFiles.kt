package app.thingsfinder.data.db

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class DatabaseImportException(message: String) : Exception(message)

/** What an import file holds, shown in the "replace everything?" confirmation. */
data class DatabaseSummary(val places: Int, val boxes: Int, val items: Int)

/**
 * Export / import of the raw SQLite database file (the same file Room uses).
 * Unlike the JSON backup, this is a byte-for-byte copy: handy for opening
 * in a SQLite browser, or moving the whole app state to another phone.
 */
class DatabaseFiles(
    private val context: Context,
    private val database: () -> ThingsFinderDatabase,
) {
    private val dbFile: File get() = context.getDatabasePath(ThingsFinderDatabase.FILE_NAME)

    /** Flushes the write-ahead log into the main file, then copies that file to [uri]. */
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val db = database().openHelper.writableDatabase
        db.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write to $uri")
        out.use { o -> dbFile.inputStream().use { it.copyTo(o) } }
    }

    /**
     * Copies [uri] to a temporary file and checks it's a thingsFinder
     * database this app version can open. Nothing on the phone changes yet.
     * Returns the staged file and what's in it.
     */
    suspend fun stage(uri: Uri): Pair<File, DatabaseSummary> = withContext(Dispatchers.IO) {
        val staged = File(context.cacheDir, "import.db")
        staged.delete()
        val input = context.contentResolver.openInputStream(uri) ?: throw DatabaseImportException("Couldn't open that file.")
        input.use { i -> staged.outputStream().use { i.copyTo(it) } }
        try {
            staged to validate(staged)
        } catch (e: Exception) {
            staged.delete()
            throw e
        }
    }

    private fun validate(file: File): DatabaseSummary {
        val header = ByteArray(16)
        val read = file.inputStream().use { it.read(header) }
        if (read < 16 || String(header, 0, 15, Charsets.US_ASCII) != "SQLite format 3") {
            throw DatabaseImportException("That isn't a SQLite database file.")
        }
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            throw DatabaseImportException("That database file is damaged or unreadable.")
        }
        db.use {
            val tables = HashSet<String>()
            it.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            if ("users" in tables && "shares" in tables) {
                throw DatabaseImportException(
                    "That's the web server's database. To bring web data to the phone, sign in under Cloud sync instead.",
                )
            }
            if (!tables.containsAll(listOf("places", "boxes", "items", "barcode_items", "room_master_table"))) {
                throw DatabaseImportException("That isn't a thingsFinder database.")
            }
            val version = it.version
            if (version > ThingsFinderDatabase.VERSION) {
                throw DatabaseImportException("That database comes from a newer version of thingsFinder — update the app first.")
            }
            if (version < 1) throw DatabaseImportException("That isn't a thingsFinder database.")
            it.rawQuery("PRAGMA quick_check", null).use { c ->
                if (!c.moveToFirst() || c.getString(0) != "ok") throw DatabaseImportException("That database file is damaged.")
            }
            fun count(table: String) = it.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            return DatabaseSummary(places = count("places"), boxes = count("boxes"), items = count("items"))
        }
    }

    /**
     * Replaces the app's database with [staged] and restarts the app, so
     * every screen and the database connection start fresh from the new
     * file. Room migrates an older file on first open.
     */
    fun replaceAndRestart(staged: File) {
        database().close()
        val target = dbFile
        File(target.path + "-wal").delete()
        File(target.path + "-shm").delete()
        File(target.path + "-journal").delete()
        staged.copyTo(target, overwrite = true)
        staged.delete()

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: throw IllegalStateException("No launcher activity")
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }

    fun discard(staged: File) {
        staged.delete()
    }
}
