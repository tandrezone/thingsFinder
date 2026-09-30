package app.thingsfinder.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thingsfinder.data.BarcodeRepository
import app.thingsfinder.data.FakeLookup
import app.thingsfinder.data.FakeSettings
import app.thingsfinder.data.InventoryRepository
import app.thingsfinder.data.db.ThingsFinderDatabase
import app.thingsfinder.data.inMemoryDb
import app.thingsfinder.domain.ItemLocation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncEngineTest {
    private lateinit var db: ThingsFinderDatabase
    private var clock = 10_000L
    private lateinit var inventory: InventoryRepository
    private lateinit var api: FakeCloudApi
    private lateinit var session: FakeSession
    private lateinit var engine: SyncEngine

    @Before fun setUp() {
        db = inMemoryDb()
        inventory = InventoryRepository(db, now = { clock })
        api = FakeCloudApi()
        session = FakeSession()
        engine = SyncEngine(db, api, session, now = { clock })
    }

    @After fun tearDown() = db.close()

    @Test fun `first sync sends everything, later syncs only what changed`() = runBlocking {
        val place = inventory.createPlace("Garage")!!
        val box = inventory.createBox(place, "Bin")!!
        inventory.addItem(ItemLocation.InBox(box), "Hammer", 1)
        inventory.addItem(ItemLocation.InPlace(place), "Rake", 1)
        BarcodeRepository(db, FakeLookup(), FakeSettings()).save("123", "Tape")

        clock = 20_000
        assertTrue(engine.syncNow() is SyncOutcome.Synced)
        val first = api.requests.single()
        assertEquals(0, first.since)
        assertEquals(listOf("Garage"), first.places.map { it.name })
        assertEquals(1, first.boxes.size)
        assertEquals(setOf("Hammer", "Rake"), first.items.map { it.name }.toSet())
        assertTrue(first.items.all { (it.boxUuid == null) != (it.placeUuid == null) })
        assertEquals(listOf("123"), first.barcodes.map { it.barcode })
        assertEquals(1000, session.flow.value.cursor)

        clock = 30_000
        inventory.renamePlace(place, "Big garage")
        engine.syncNow()
        val second = api.requests[1]
        assertEquals(1000, second.since)
        assertEquals(listOf("Big garage"), second.places.map { it.name })
        assertTrue(second.boxes.isEmpty() && second.items.isEmpty())
    }

    @Test fun `deletes travel as tombstones and are cleared once delivered`() = runBlocking {
        val place = inventory.createPlace("Garage")!!
        val uuid = db.placeDao().get(place)!!.uuid
        inventory.deletePlace(place)
        engine.syncNow()
        assertEquals(listOf(SyncDeleted("places", uuid, clock)), api.requests.single().deleted)
        assertTrue(db.tombstoneDao().getAll().isEmpty())
    }

    @Test fun `tombstones survive a failed sync`() = runBlocking {
        val place = inventory.createPlace("Garage")!!
        inventory.deletePlace(place)
        api.next = { ApiResult.NetworkError(IOException("offline")) }
        val outcome = engine.syncNow()
        assertTrue(outcome is SyncOutcome.Failed && outcome.retryable)
        assertEquals(1, db.tombstoneDao().getAll().size)
        assertTrue(session.flow.value.lastError!!.contains("example.test"))
    }

    @Test fun `server changes are applied, newer local edits win`() = runBlocking {
        val place = inventory.createPlace("Garage")!! // updated_at = 10_000
        val placeUuid = db.placeDao().get(place)!!.uuid
        api.next = {
            ApiResult.Success(
                SyncResponse(
                    serverTime = 5000,
                    changes = SyncChanges(
                        places = listOf(
                            SyncPlace(placeUuid, "Older name", updatedAt = 9_000), // older than ours: ignored
                            SyncPlace("web-place-1", "Kitchen", updatedAt = 12_000),
                        ),
                        boxes = listOf(SyncBox("web-box-1", "web-place-1", "Drawer", "0123456789abcdef0123456789abcdef", 12_000)),
                        items = listOf(
                            SyncItem("web-item-1", boxUuid = "web-box-1", name = "Spoons", quantity = 6, updatedAt = 12_000),
                            SyncItem("web-item-2", placeUuid = "nowhere", name = "Orphan", updatedAt = 12_000),
                        ),
                        barcodes = listOf(SyncBarcode("999", "Olive oil")),
                    ),
                ),
            )
        }
        val outcome = engine.syncNow() as SyncOutcome.Synced
        assertEquals(4, outcome.pulled) // place, box, item, barcode
        assertEquals("Garage", db.placeDao().get(place)!!.name)
        val drawer = db.boxDao().findByUuid("web-box-1")!!
        assertEquals("0123456789abcdef0123456789abcdef", drawer.shareToken)
        assertEquals(6, db.itemDao().findByUuid("web-item-1")!!.quantity)
        assertNull(db.itemDao().findByUuid("web-item-2"))
        assertEquals("Olive oil", db.barcodeDao().find("999")!!.name)
    }

    @Test fun `server deletes remove rows unless edited here afterwards`() = runBlocking {
        val a = inventory.createPlace("A")!!
        clock = 50_000
        val b = inventory.createPlace("B")!!
        val ua = db.placeDao().get(a)!!.uuid
        val ub = db.placeDao().get(b)!!.uuid
        api.next = {
            ApiResult.Success(
                SyncResponse(
                    serverTime = 1,
                    changes = SyncChanges(deleted = listOf(SyncDeleted("places", ua, 20_000), SyncDeleted("places", ub, 20_000), SyncDeleted("items", "unknown", 1))),
                ),
            )
        }
        engine.syncNow()
        assertNull(db.placeDao().get(a))
        assertEquals("B", db.placeDao().get(b)!!.name) // edited after the web delete: kept
    }

    @Test fun `401 signs the phone out; disabled or signed out does nothing`() = runBlocking {
        api.next = { ApiResult.Unauthorized("Invalid or expired token") }
        assertEquals(SyncOutcome.SignedOut, engine.syncNow())
        assertNull(session.flow.value.token)
        assertEquals(SyncOutcome.NotSignedIn, engine.syncNow())
        session.signedIn("https://example.test", "tiago", "tok")
        session.setEnabled(false)
        assertEquals(SyncOutcome.Disabled, engine.syncNow())
        assertEquals(1, api.requests.size)
    }

    @Test fun `rows the server couldn't place are sent again next time`() = runBlocking {
        val place = inventory.createPlace("Garage")!! // updated_at 10_000
        val uuid = db.placeDao().get(place)!!.uuid
        clock = 20_000
        api.next = { ApiResult.Success(SyncResponse(serverTime = 1, skipped = listOf(SyncSkipped("places", uuid, "place not found")))) }
        engine.syncNow()
        assertEquals(9_999, session.flow.value.lastPushAt)
        api.next = { ApiResult.Success(SyncResponse(serverTime = 2)) }
        engine.syncNow()
        assertEquals(listOf(uuid), api.requests[1].places.map { it.uuid })
    }

    @Test fun `a delete the server refused triggers a full pull next time`() = runBlocking {
        val place = inventory.createPlace("Garage")!!
        val uuid = db.placeDao().get(place)!!.uuid
        inventory.deletePlace(place)
        api.next = { ApiResult.Success(SyncResponse(serverTime = 77, changes = SyncChanges(places = listOf(SyncPlace(uuid, "Garage (edited on web)", 99_000))))) }
        engine.syncNow()
        assertEquals("Garage (edited on web)", db.placeDao().findByUuid(uuid)!!.name)
        assertEquals(0, session.flow.value.cursor)
    }

    @Test fun `barcodes pulled from the server are not pushed back`() = runBlocking {
        api.next = { ApiResult.Success(SyncResponse(serverTime = 1, changes = SyncChanges(barcodes = listOf(SyncBarcode("555", "Web name"))))) }
        engine.syncNow()
        api.next = { ApiResult.Success(SyncResponse(serverTime = 2)) }
        engine.syncNow()
        assertTrue(api.requests[1].barcodes.isEmpty())
    }
}
