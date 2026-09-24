package app.thingsfinder.data

import androidx.room.withTransaction
import app.thingsfinder.data.db.BarcodeEntity
import app.thingsfinder.data.db.BoxEntity
import app.thingsfinder.data.db.BoxSummary
import app.thingsfinder.data.db.BoxWithPlace
import app.thingsfinder.data.db.ItemEntity
import app.thingsfinder.data.db.PlaceEntity
import app.thingsfinder.data.db.PlaceSummary
import app.thingsfinder.data.db.SearchRow
import app.thingsfinder.data.db.ThingsFinderDatabase
import app.thingsfinder.domain.BoxLinks
import app.thingsfinder.domain.ItemDraft
import app.thingsfinder.domain.ItemLocation
import app.thingsfinder.domain.Slugs
import app.thingsfinder.domain.clampQuantity
import app.thingsfinder.domain.cleanName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/** One place and its boxes, for the move-item / move-box pickers (PHP: list_move_destinations()). */
data class MoveDestination(val placeId: Long, val placeName: String, val boxes: List<BoxWithPlace>)

/** Outcome of adding a single item (PHP: handle_item_action('create_item')). */
sealed interface AddItemOutcome {
    /** [recognized]: the barcode was already registered and supplied the name; [remembered]: a new barcode was learnt. */
    data class Added(val name: String, val recognized: Boolean, val remembered: Boolean) : AddItemOutcome
    /** A barcode was given that isn't registered and no name was typed. */
    data object NeedsName : AddItemOutcome
    data object EmptyName : AddItemOutcome
}

/**
 * The single place where inventory rules live on the phone. Each method
 * notes the PHP code it was ported from, so the two stay in step.
 */
class InventoryRepository(
    private val db: ThingsFinderDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) {
    private val places = db.placeDao()
    private val boxes = db.boxDao()
    private val items = db.itemDao()
    private val barcodes = db.barcodeDao()

    // ---- reads -------------------------------------------------------------

    fun observePlaceSummaries(): Flow<List<PlaceSummary>> = places.observeSummaries()
    fun observePlace(id: Long): Flow<PlaceEntity?> = places.observe(id)
    fun observeBoxes(placeId: Long): Flow<List<BoxSummary>> = boxes.observeSummaries(placeId)
    fun observeBox(id: Long): Flow<BoxWithPlace?> = boxes.observeWithPlace(id)

    fun observeItems(location: ItemLocation): Flow<List<ItemEntity>> = when (location) {
        is ItemLocation.InBox -> items.observeInBox(location.boxId)
        is ItemLocation.InPlace -> items.observeInPlace(location.placeId)
    }

    fun observeMoveDestinations(): Flow<List<MoveDestination>> =
        combine(places.observeAll(), boxes.observeAllWithPlace()) { allPlaces, allBoxes ->
            val byPlace = allBoxes.groupBy { it.placeId }
            allPlaces.map { MoveDestination(it.id, it.name, byPlace[it.id].orEmpty()) }
        }

    /** PHP: the /search query — case-insensitive substring match with LIKE wildcards escaped. */
    fun search(query: String): Flow<List<SearchRow>> {
        val q = query.trim()
        if (q.isEmpty()) return flowOf(emptyList())
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return items.search("%$escaped%")
    }

    suspend fun findBoxByToken(token: String): BoxEntity? = boxes.findByToken(token)

    // ---- places -------------------------------------------------------------

    /** Returns the new place's id, or null if the name is empty. */
    suspend fun createPlace(rawName: String): Long? {
        val name = cleanName(rawName)
        if (name.isEmpty()) return null
        return db.withTransaction {
            val slug = Slugs.unique(name) { places.countSlug(it) > 0 }
            val t = now()
            places.insert(PlaceEntity(uuid = newUuid(), name = name, slug = slug, createdAt = t, updatedAt = t))
        }
    }

    suspend fun renamePlace(id: Long, rawName: String): Boolean {
        val name = cleanName(rawName)
        if (name.isEmpty()) return false
        return db.withTransaction {
            val place = places.get(id) ?: return@withTransaction false
            val slug = Slugs.unique(name) { places.countSlug(it, excludeId = id) > 0 }
            places.update(place.copy(name = name, slug = slug, updatedAt = now()))
            true
        }
    }

    /** Deletes the place, its boxes and every item in either (ON DELETE CASCADE). */
    suspend fun deletePlace(id: Long) = places.delete(id)

    // ---- boxes --------------------------------------------------------------

    suspend fun createBox(placeId: Long, rawName: String): Long? {
        val name = cleanName(rawName)
        if (name.isEmpty()) return null
        return db.withTransaction {
            places.get(placeId) ?: return@withTransaction null
            val slug = Slugs.unique(name) { boxes.countSlug(placeId, it) > 0 }
            val t = now()
            boxes.insert(
                BoxEntity(
                    uuid = newUuid(),
                    placeId = placeId,
                    name = name,
                    slug = slug,
                    shareToken = BoxLinks.newShareToken(),
                    createdAt = t,
                    updatedAt = t,
                ),
            )
        }
    }

    suspend fun renameBox(id: Long, rawName: String): Boolean {
        val name = cleanName(rawName)
        if (name.isEmpty()) return false
        return db.withTransaction {
            val box = boxes.get(id) ?: return@withTransaction false
            val slug = Slugs.unique(name) { boxes.countSlug(box.placeId, it, excludeId = id) > 0 }
            boxes.update(box.copy(name = name, slug = slug, updatedAt = now()))
            true
        }
    }

    /** PHP: move_box_to() — the box keeps its items; its slug is re-deduplicated inside the new place. */
    suspend fun moveBox(id: Long, newPlaceId: Long): Boolean = db.withTransaction {
        val box = boxes.get(id) ?: return@withTransaction false
        places.get(newPlaceId) ?: return@withTransaction false
        if (box.placeId == newPlaceId) return@withTransaction true
        val slug = Slugs.unique(box.name) { boxes.countSlug(newPlaceId, it, excludeId = id) > 0 }
        boxes.update(box.copy(placeId = newPlaceId, slug = slug, updatedAt = now()))
        true
    }

    suspend fun deleteBox(id: Long) = boxes.delete(id)

    // ---- items --------------------------------------------------------------

    /**
     * PHP: handle_item_action('create_item'). A registered barcode always
     * wins over the typed name; an unregistered barcode plus a name adds the
     * item and remembers the barcode. (The external name lookup happens in
     * the UI before this is called, so the person can confirm the suggestion.)
     */
    suspend fun addItem(location: ItemLocation, rawName: String, quantity: Int, rawBarcode: String = ""): AddItemOutcome {
        val barcode = rawBarcode.trim()
        val known = if (barcode.isNotEmpty()) barcodes.find(barcode) else null
        val name = known?.name ?: cleanName(rawName)
        if (name.isEmpty()) return if (barcode.isNotEmpty()) AddItemOutcome.NeedsName else AddItemOutcome.EmptyName

        db.withTransaction {
            requireLocationExists(location)
            items.insert(newItem(location, name, clampQuantity(quantity.toLong())))
            if (barcode.isNotEmpty() && known == null) {
                barcodes.upsert(BarcodeEntity(barcode, name, now()))
            }
        }
        return AddItemOutcome.Added(name, recognized = known != null, remembered = barcode.isNotEmpty() && known == null)
    }

    /** Bulk insert for JSON import and the OCR review ("Add N items"); all-or-nothing, like the PHP transaction. */
    suspend fun addItems(location: ItemLocation, drafts: List<ItemDraft>): Int {
        val clean = drafts.mapNotNull { d -> cleanName(d.name).takeIf { it.isNotEmpty() }?.let { ItemDraft(it, d.quantity) } }
        if (clean.isEmpty()) return 0
        db.withTransaction {
            requireLocationExists(location)
            items.insertAll(clean.map { newItem(location, it.name, clampQuantity(it.quantity.toLong())) })
        }
        return clean.size
    }

    /** PHP: rename_item — name and quantity together. */
    suspend fun updateItem(id: Long, rawName: String, quantity: Int): Boolean {
        val name = cleanName(rawName)
        if (name.isEmpty()) return false
        val item = items.get(id) ?: return false
        items.update(item.copy(name = name, quantity = clampQuantity(quantity.toLong()), updatedAt = now()))
        return true
    }

    /** PHP: move_item_to() — into a box, or loose into a place. */
    suspend fun moveItem(id: Long, destination: ItemLocation): Boolean = db.withTransaction {
        val item = items.get(id) ?: return@withTransaction false
        val moved = when (destination) {
            is ItemLocation.InBox -> {
                boxes.get(destination.boxId) ?: return@withTransaction false
                item.copy(boxId = destination.boxId, placeId = null)
            }
            is ItemLocation.InPlace -> {
                places.get(destination.placeId) ?: return@withTransaction false
                item.copy(boxId = null, placeId = destination.placeId)
            }
        }
        items.update(moved.copy(updatedAt = now()))
        true
    }

    suspend fun deleteItem(id: Long) = items.delete(id)

    private suspend fun requireLocationExists(location: ItemLocation) {
        val exists = when (location) {
            is ItemLocation.InBox -> boxes.get(location.boxId) != null
            is ItemLocation.InPlace -> places.get(location.placeId) != null
        }
        require(exists) { "Destination no longer exists" }
    }

    private fun newItem(location: ItemLocation, name: String, quantity: Int): ItemEntity {
        val t = now()
        return ItemEntity(
            uuid = newUuid(),
            boxId = (location as? ItemLocation.InBox)?.boxId,
            placeId = (location as? ItemLocation.InPlace)?.placeId,
            name = name,
            quantity = quantity,
            createdAt = t,
            updatedAt = t,
        )
    }
}
