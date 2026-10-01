package app.thingsfinder.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thingsfinder.data.BarcodeRepository
import app.thingsfinder.data.FakeLookup
import app.thingsfinder.data.FakeSettings
import app.thingsfinder.data.InventoryRepository
import app.thingsfinder.data.db.ThingsFinderDatabase
import app.thingsfinder.data.inMemoryDb
import app.thingsfinder.domain.BoxLinks
import app.thingsfinder.domain.ItemLocation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `401 signs the phone out, disabled or signed out does nothing`() = runBlocking {
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
        api.next = { ApiResult.Success(SyncResponse(serverTime = 77, changes = SyncChanges(places = listOf(SyncPlace(uuid, "Garage (edited on web)", updatedAt = 99_000))))) }
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

    @Test fun `places carry their share token both ways`() = runBlocking {
        val place = inventory.createPlace("Garage")!!
        val cellar = inventory.createPlace("Cellar")!!
        val local = db.placeDao().get(place)!!
        val cellarToken = db.placeDao().get(cellar)!!.shareToken
        val webToken = "fedcba9876543210fedcba9876543210"
        clock = 20_000
        api.next = {
            ApiResult.Success(
                SyncResponse(
                    serverTime = 5,
                    changes = SyncChanges(
                        places = listOf(
                            SyncPlace(local.uuid, "Old name", webToken, updatedAt = 5_000), // older: our name stays, the token is adopted
                            SyncPlace("web-place-1", "Attic", "00112233445566778899aabbccddeeff", updatedAt = 12_000),
                            SyncPlace("web-place-2", "Shed", cellarToken, updatedAt = 12_000), // token taken here: gets a new one
                        ),
                    ),
                ),
            )
        }
        engine.syncNow()
        assertEquals(local.shareToken, api.requests.single().places.first { it.uuid == local.uuid }.shareToken)
        assertEquals(webToken, db.placeDao().get(place)!!.shareToken)
        assertEquals("Garage", db.placeDao().get(place)!!.name)
        assertEquals("Attic", db.placeDao().findByToken("00112233445566778899aabbccddeeff")!!.name)
        val shed = db.placeDao().findByUuid("web-place-2")!!
        assertTrue(shed.shareToken != cellarToken && BoxLinks.isValidToken(shed.shareToken))
    }

    @Test fun `the server's default group is remembered and sent from then on`() = runBlocking {
        api.next = { ApiResult.Success(SyncResponse(serverTime = 1, group = SyncGroup(7, "Family", RemoteGroup.VIEW))) }
        engine.syncNow()
        assertNull(api.requests[0].groupId)
        assertEquals(ActiveGroup(7, "Family", RemoteGroup.VIEW), session.flow.value.activeGroup)
        assertTrue(session.flow.value.activeGroup!!.viewOnly)
        engine.syncNow()
        assertEquals(7L, api.requests[1].groupId)
    }

    @Test fun `view-only skips are permanent and explained`() = runBlocking {
        val place = inventory.createPlace("Garage")!!
        val uuid = db.placeDao().get(place)!!.uuid
        clock = 20_000
        api.next = { ApiResult.Success(SyncResponse(serverTime = 1, skipped = listOf(SyncSkipped("places", uuid, SyncSkipped.READ_ONLY)))) }
        val outcome = engine.syncNow() as SyncOutcome.Synced
        assertFalse(outcome.held)
        assertEquals(19_999, session.flow.value.lastPushAt)
        assertEquals(SyncEngine.VIEW_ONLY_MESSAGE, session.flow.value.lastError)
    }

    @Test fun `switching group pushes first, then swaps the inventory but keeps barcodes`() = runBlocking {
        val place = inventory.createPlace("Garage")!!
        inventory.addItem(ItemLocation.InPlace(place), "Rake", 1)
        inventory.deletePlace(inventory.createPlace("Old")!!)
        BarcodeRepository(db, FakeLookup(), FakeSettings()).save("123", "Tape")
        session.setActiveGroup(ActiveGroup(1, "tiago"))
        clock = 20_000
        api.next = { req ->
            if (req.groupId == 1L) {
                ApiResult.Success(SyncResponse(serverTime = 1, group = SyncGroup(1, "tiago")))
            } else {
                ApiResult.Success(
                    SyncResponse(
                        serverTime = 2,
                        changes = SyncChanges(places = listOf(SyncPlace("fam-1", "Kitchen", updatedAt = 3_000))),
                        group = SyncGroup(2, "Family renamed"),
                    ),
                )
            }
        }
        val outcome = engine.switchGroup(ActiveGroup(2, "Family"))
        assertTrue(outcome is SwitchOutcome.Switched && outcome.pull is SyncOutcome.Synced)
        assertEquals(listOf(1L, 2L), api.requests.map { it.groupId })
        // The pending edits and the delete went to the old group…
        assertEquals(listOf("Garage"), api.requests[0].places.map { it.name })
        assertEquals(1, api.requests[0].deleted.size)
        // …and the new group starts from scratch.
        assertEquals(0, api.requests[1].since)
        assertTrue(api.requests[1].places.isEmpty() && api.requests[1].items.isEmpty() && api.requests[1].deleted.isEmpty())
        assertEquals(listOf("Kitchen"), db.placeDao().getAll().map { it.name })
        assertTrue(db.itemDao().getAll().isEmpty())
        assertTrue(db.tombstoneDao().getAll().isEmpty())
        assertEquals("Tape", db.barcodeDao().find("123")!!.name)
        assertEquals(ActiveGroup(2, "Family renamed"), session.flow.value.activeGroup)
        assertEquals(2, session.flow.value.cursor)
    }

    @Test fun `a failed push refuses the switch and changes nothing`() = runBlocking {
        val place = inventory.createPlace("Garage")!!
        session.setActiveGroup(ActiveGroup(1, "tiago"))
        api.next = { ApiResult.NetworkError(IOException("offline")) }
        assertTrue(engine.switchGroup(ActiveGroup(2, "Family")) is SwitchOutcome.Refused)
        assertEquals("Garage", db.placeDao().get(place)!!.name)
        assertEquals(ActiveGroup(1, "tiago"), session.flow.value.activeGroup)
        assertEquals(1, api.requests.size)
    }

    @Test fun `rows the server held back refuse the switch`() = runBlocking {
        val place = inventory.createPlace("Garage")!!
        val uuid = db.placeDao().get(place)!!.uuid
        clock = 20_000
        api.next = { ApiResult.Success(SyncResponse(serverTime = 1, skipped = listOf(SyncSkipped("places", uuid, "place not found")))) }
        assertTrue(engine.switchGroup(ActiveGroup(2, "Family")) is SwitchOutcome.Refused)
        assertEquals("Garage", db.placeDao().get(place)!!.name)
        assertNull(session.flow.value.activeGroup)
    }

    @Test fun `losing access to the group says so, and switching away still works`() = runBlocking {
        inventory.createPlace("Garage")
        session.setActiveGroup(ActiveGroup(5, "Club"))
        api.next = { req ->
            if (req.groupId == 5L) ApiResult.HttpError(403, "Not a member of that group") else ApiResult.Success(SyncResponse(serverTime = 3, group = SyncGroup(1, "tiago")))
        }
        val outcome = engine.syncNow()
        assertTrue(outcome is SyncOutcome.NoAccess)
        assertTrue(session.flow.value.lastError!!.contains("Club"))
        // Back to the server's default group: nothing can be sent to "Club" any more, so the switch goes ahead.
        assertTrue(engine.switchGroup(null) is SwitchOutcome.Switched)
        assertTrue(db.placeDao().getAll().isEmpty())
        assertNull(api.requests.last().groupId)
        assertEquals(ActiveGroup(1, "tiago"), session.flow.value.activeGroup)
    }
}
