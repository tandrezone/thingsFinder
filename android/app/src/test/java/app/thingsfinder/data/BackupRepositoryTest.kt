package app.thingsfinder.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thingsfinder.data.backup.BackupException
import app.thingsfinder.data.backup.BackupRepository
import app.thingsfinder.domain.ItemDraft
import app.thingsfinder.domain.ItemLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackupRepositoryTest {

    @Test fun `export then restore into an empty phone reproduces everything`() = runBlocking {
        val source = inMemoryDb()
        val repo = InventoryRepository(source)
        val garage = repo.createPlace("Garage")!!
        val bin = repo.createBox(garage, "Bin 2")!!
        repo.addItems(ItemLocation.InBox(bin), listOf(ItemDraft("Hammer"), ItemDraft("Tape", 3)))
        repo.addItem(ItemLocation.InPlace(garage), "Rake", 1)
        BarcodeRepository(source, FakeLookup(), FakeSettings()).save("123", "Tape")
        val token = source.boxDao().get(bin)!!.shareToken
        val placeToken = source.placeDao().get(garage)!!.shareToken
        val json = BackupRepository(source).exportJson()

        val target = inMemoryDb()
        val backup = BackupRepository(target)
        val summary = backup.restore(backup.parse(json))
        assertEquals(1, summary.places)
        assertEquals(1, summary.boxes)
        assertEquals(3, summary.items)
        assertEquals(1, summary.barcodes)
        // Share tokens survive, so printed labels keep working after a restore.
        assertEquals("Bin 2", target.boxDao().findByToken(token)?.name)
        assertEquals("Garage", target.placeDao().findByToken(placeToken)?.name)
        assertEquals(listOf("Hammer", "Rake", "Tape"), target.itemDao().getAll().map { it.name })
        source.close()
        target.close()
    }

    @Test(expected = BackupException::class)
    fun `a random json file is rejected before anything is touched`() {
        BackupRepository(inMemoryDb()).parse("""[{"name":"Hammer"}]""")
    }

    @Test fun `older backups without place tokens get fresh ones`() = runBlocking {
        val db = inMemoryDb()
        val backup = BackupRepository(db)
        backup.restore(
            backup.parse("""{"format":"thingsfinder-backup","version":1,"exported_at":1,"places":[{"name":"Garage"},{"name":"Attic","share_token":"not valid!"}]}"""),
        )
        val tokens = db.placeDao().getAll().map { it.shareToken }
        assertEquals(2, tokens.toSet().size)
        assertTrue(tokens.all { it.matches(Regex("^[0-9a-f]{32}$")) })
        db.close()
    }
}
