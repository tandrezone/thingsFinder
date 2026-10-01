package app.thingsfinder.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thingsfinder.data.db.ThingsFinderDatabase
import app.thingsfinder.domain.ItemDraft
import app.thingsfinder.domain.ItemLocation
import app.thingsfinder.lookup.LookupSuggestion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class InventoryRepositoryTest {
    private lateinit var db: ThingsFinderDatabase
    private lateinit var repo: InventoryRepository
    private lateinit var lookup: FakeLookup
    private lateinit var settings: FakeSettings
    private lateinit var barcodes: BarcodeRepository

    @Before fun setUp() {
        db = inMemoryDb()
        repo = InventoryRepository(db)
        lookup = FakeLookup()
        settings = FakeSettings()
        barcodes = BarcodeRepository(db, lookup, settings)
    }

    @After fun tearDown() = db.close()

    @Test fun `place slugs are de-duplicated like the web app`() = runBlocking {
        val a = repo.createPlace("Garage")!!
        val b = repo.createPlace("garage")!!
        assertEquals(listOf("garage", "garage-2"), listOf(db.placeDao().get(a)!!.slug, db.placeDao().get(b)!!.slug))
        assertNull(repo.createPlace("   "))
        assertTrue(repo.renamePlace(b, "Attic"))
        assertEquals("attic", db.placeDao().get(b)!!.slug)
    }

    @Test fun `box slugs are unique per place, and a move re-deduplicates`() = runBlocking {
        val garage = repo.createPlace("Garage")!!
        val attic = repo.createPlace("Attic")!!
        val g = repo.createBox(garage, "Tools")!!
        repo.createBox(attic, "Tools")!!
        assertEquals("tools", db.boxDao().get(g)!!.slug)
        assertTrue(repo.moveBox(g, attic))
        assertEquals("tools-2", db.boxDao().get(g)!!.slug)
        assertEquals(32, db.boxDao().get(g)!!.shareToken.length)
    }

    @Test fun `an unknown barcode plus a name adds the item and remembers the barcode`() = runBlocking {
        val box = repo.createBox(repo.createPlace("Garage")!!, "Bin")!!
        val out = repo.addItem(ItemLocation.InBox(box), "Glue gun", 2, "4006381333931")
        assertEquals(AddItemOutcome.Added("Glue gun", recognized = false, remembered = true), out)
        assertEquals("Glue gun", db.barcodeDao().find("4006381333931")!!.name)
        assertEquals(2, db.itemDao().getAll().single().quantity)
    }

    @Test fun `a registered barcode always wins over the typed name`() = runBlocking {
        val place = repo.createPlace("Kitchen")!!
        barcodes.save("123", "Olive oil")
        val out = repo.addItem(ItemLocation.InPlace(place), "whatever", 1, "123")
        assertEquals(AddItemOutcome.Added("Olive oil", recognized = true, remembered = false), out)
        assertEquals(AddItemOutcome.NeedsName, repo.addItem(ItemLocation.InPlace(place), "", 1, "999"))
        assertEquals(AddItemOutcome.EmptyName, repo.addItem(ItemLocation.InPlace(place), " ", 1, ""))
    }

    @Test fun `items move between boxes and places, keeping exactly one parent`() = runBlocking {
        val place = repo.createPlace("Garage")!!
        val box = repo.createBox(place, "Bin")!!
        repo.addItem(ItemLocation.InPlace(place), "Rake", 1)
        val id = db.itemDao().getAll().single().id
        assertTrue(repo.moveItem(id, ItemLocation.InBox(box)))
        db.itemDao().get(id)!!.let { assertEquals(box, it.boxId); assertNull(it.placeId) }
        assertTrue(repo.moveItem(id, ItemLocation.InPlace(place)))
        db.itemDao().get(id)!!.let { assertNull(it.boxId); assertEquals(place, it.placeId) }
        assertTrue(!repo.moveItem(id, ItemLocation.InBox(9999)))
    }

    @Test fun `deleting a place cascades to its boxes and every item`() = runBlocking {
        val place = repo.createPlace("Garage")!!
        val box = repo.createBox(place, "Bin")!!
        repo.addItems(ItemLocation.InBox(box), listOf(ItemDraft("a"), ItemDraft("b", 3)))
        repo.addItem(ItemLocation.InPlace(place), "c", 1)
        assertEquals(3, repo.observePlaceSummaries().first().single().itemCount)
        repo.deletePlace(place)
        assertTrue(db.boxDao().getAll().isEmpty())
        assertTrue(db.itemDao().getAll().isEmpty())
    }

    @Test fun `search is a case-insensitive substring match with wildcards escaped`() = runBlocking {
        val place = repo.createPlace("Garage")!!
        val box = repo.createBox(place, "Bin")!!
        repo.addItems(ItemLocation.InBox(box), listOf(ItemDraft("Hot glue gun"), ItemDraft("100% cotton"), ItemDraft("snake_case")))
        repo.addItem(ItemLocation.InPlace(place), "Glue sticks", 30)
        val glue = repo.search("GLUE").first()
        assertEquals(listOf("Glue sticks", "Hot glue gun"), glue.map { it.itemName })
        assertEquals("Bin", glue[1].boxName)
        assertNull(glue[0].boxName)
        assertEquals(listOf("100% cotton"), repo.search("%").first().map { it.itemName })
        assertEquals(listOf("snake_case"), repo.search("_").first().map { it.itemName })
        assertTrue(repo.search("  ").first().isEmpty())
    }

    @Test fun `barcode resolution - register, then suggestion only when enabled`() = runBlocking {
        barcodes.save("111", "Tape")
        assertEquals(BarcodeResolution.Registered("Tape"), barcodes.resolve("111"))
        lookup.answer = LookupSuggestion("Heinz Ketchup", "Open Food Facts")
        assertEquals(BarcodeResolution.Suggested("Heinz Ketchup", "Open Food Facts"), barcodes.resolve("222"))
        settings.flow.value = false
        assertEquals(BarcodeResolution.Unknown, barcodes.resolve("333"))
        assertEquals(1, lookup.calls)
        assertNotNull(db.barcodeDao().find("111"))
        assertNull(db.barcodeDao().find("222")) // suggestions are never saved on their own
    }

    @Test fun `places get their own share token, found by it like boxes`() = runBlocking {
        val garage = repo.createPlace("Garage")!!
        val attic = repo.createPlace("Attic")!!
        val token = db.placeDao().get(garage)!!.shareToken
        assertTrue(token.matches(Regex("^[0-9a-f]{32}$")))
        assertTrue(token != db.placeDao().get(attic)!!.shareToken)
        assertEquals(garage, repo.findPlaceByToken(token)?.id)
        assertNull(repo.findBoxByToken(token))
    }

    @Test fun `taking one out lowers the quantity but never deletes`() = runBlocking {
        val place = repo.createPlace("Garage")!!
        repo.addItem(ItemLocation.InPlace(place), "Batteries", 2)
        val id = db.itemDao().getAll().single().id
        assertEquals(1, repo.takeOne(id))
        assertEquals(1, db.itemDao().get(id)!!.quantity)
        assertNull(repo.takeOne(id)) // the last one is a delete, which the UI confirms
        assertEquals(1, db.itemDao().get(id)!!.quantity)
        assertNull(repo.takeOne(999))
    }
}
