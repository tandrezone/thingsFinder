package app.thingsfinder.ui

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thingsfinder.R
import app.thingsfinder.data.BarcodeRepository
import app.thingsfinder.data.FakeLookup
import app.thingsfinder.data.FakeSettings
import app.thingsfinder.data.InventoryRepository
import app.thingsfinder.data.db.ThingsFinderDatabase
import app.thingsfinder.data.inMemoryDb
import app.thingsfinder.domain.ItemDraft
import app.thingsfinder.domain.ItemLocation
import app.thingsfinder.lookup.LookupSuggestion
import app.thingsfinder.ui.box.BoxDetailViewModel
import app.thingsfinder.ui.common.BarcodeLookupState
import app.thingsfinder.ui.common.ReviewState
import app.thingsfinder.ui.common.ScreenEvent
import app.thingsfinder.ui.common.UiState
import app.thingsfinder.ui.search.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ViewModel state transitions against a real in-memory Room database.
 * runBlocking (not runTest) on purpose: Room runs queries on its own
 * threads, which virtual time would otherwise skip past.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ViewModelTests {
    private lateinit var db: ThingsFinderDatabase
    private lateinit var inventory: InventoryRepository
    private lateinit var lookup: FakeLookup
    private lateinit var barcodes: BarcodeRepository

    @Before fun setUp() {
        // Plain Unconfined (no virtual time), so debounce() and Room's own threads run in real time.
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDb()
        inventory = InventoryRepository(db)
        lookup = FakeLookup()
        barcodes = BarcodeRepository(db, lookup, FakeSettings())
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun <T> Flow<T>.firstMatching(predicate: (T) -> Boolean): T = withTimeout(5_000) { first(predicate) }

    private fun boxVm(boxId: Long, ocr: List<ItemDraft> = emptyList()) =
        BoxDetailViewModel(boxId, inventory, barcodes) { ocr }

    @Test fun `box screen goes Loading to Content, then emits Deleted`() = runBlocking {
        val box = inventory.createBox(inventory.createPlace("Garage")!!, "Bin")!!
        val vm = boxVm(box)
        assertEquals(UiState.Loading, vm.state.value)
        val content = vm.state.firstMatching { it is UiState.Content } as UiState.Content
        assertEquals("Garage", content.data.placeName)
        vm.deleteBox()
        assertEquals(ScreenEvent.Deleted, withTimeout(5_000) { vm.events.first() })
    }

    @Test fun `unknown box is NotFound`() = runBlocking {
        assertEquals(UiState.NotFound, boxVm(42).state.firstMatching { it != UiState.Loading })
    }

    @Test fun `adding an item reports success and shows up in the list`() = runBlocking {
        val box = inventory.createBox(inventory.createPlace("Garage")!!, "Bin")!!
        val vm = boxVm(box)
        val closed = kotlinx.coroutines.CompletableDeferred<Boolean>()
        vm.addItem("Glue gun", 2, "") { closed.complete(it) }
        assertEquals(true, withTimeout(5_000) { closed.await() })
        assertEquals(R.string.msg_item_added, withTimeout(5_000) { vm.messages.first() }.res)
        val items = vm.items.firstMatching { it is UiState.Content && it.data.isNotEmpty() } as UiState.Content
        assertEquals("Glue gun", items.data.single().name)
    }

    @Test fun `barcode lookup goes Looking to Suggested`() = runBlocking {
        val box = inventory.createBox(inventory.createPlace("Garage")!!, "Bin")!!
        lookup.answer = LookupSuggestion("Heinz Ketchup", "Open Food Facts")
        val vm = boxVm(box)
        vm.resolveBarcode("5000157024671")
        assertEquals(
            BarcodeLookupState.Suggested("5000157024671", "Heinz Ketchup", "Open Food Facts"),
            vm.barcodeState.firstMatching { it is BarcodeLookupState.Suggested },
        )
        vm.clearBarcodeState()
        assertEquals(BarcodeLookupState.Idle, vm.barcodeState.value)
    }

    @Test fun `json import goes through review, then adds everything left`() = runBlocking {
        val box = inventory.createBox(inventory.createPlace("Garage")!!, "Bin")!!
        val vm = boxVm(box)
        vm.importJson("""[{"name":"Hammer"},{"name":"Tape","quantity":3},{"nope":1}]""")
        val review = vm.review.value!!
        assertEquals(ReviewState.Source.Json, review.source)
        assertEquals(2, review.lines.size)
        assertEquals(1, review.problems.size)
        vm.removeReviewLine(0)
        vm.addReviewed()
        assertEquals(R.string.msg_items_added_skipped, withTimeout(5_000) { vm.messages.first() }.res)
        assertEquals(null, vm.review.value)
        assertEquals(listOf("Tape"), db.itemDao().getAll().map { it.name })
    }

    @Test fun `photo text is merged into one review list without duplicates`() = runBlocking {
        val place = inventory.createPlace("Kitchen")!!
        val box = inventory.createBox(place, "Drawer")!!
        val vm = boxVm(box, ocr = listOf(ItemDraft("Spoons"), ItemDraft("Forks")))
        vm.extractFromPhoto(android.net.Uri.EMPTY)
        vm.review.firstMatching { it != null }
        vm.extractFromPhoto(android.net.Uri.EMPTY)
        vm.ocrRunning.firstMatching { !it }
        assertEquals(listOf("Spoons", "Forks"), vm.review.value!!.lines.map { it.name })
        assertEquals(ReviewState.Source.Photo, vm.review.value!!.source)
        assertTrue(vm.location == ItemLocation.InBox(box))
    }

    @Test fun `search keeps its query in SavedStateHandle and finds items`() = runBlocking {
        val place = inventory.createPlace("Garage")!!
        inventory.addItem(ItemLocation.InPlace(place), "Hot glue gun", 1)
        val saved = SavedStateHandle()
        val vm = SearchViewModel(inventory, saved)
        vm.onQueryChange("glue")
        assertEquals("glue", saved.get<String>("q"))
        val result = vm.results.firstMatching { it is UiState.Content && it.data.rows.isNotEmpty() } as UiState.Content
        assertEquals("Hot glue gun", result.data.rows.single().itemName)
    }
}
