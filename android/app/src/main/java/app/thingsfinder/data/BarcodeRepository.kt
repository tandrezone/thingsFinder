package app.thingsfinder.data

import app.thingsfinder.data.db.BarcodeEntity
import app.thingsfinder.data.db.ThingsFinderDatabase
import app.thingsfinder.domain.cleanName
import app.thingsfinder.lookup.BarcodeNameLookup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** What we know about a scanned/typed barcode before an item is added. */
sealed interface BarcodeResolution {
    data class Registered(val name: String) : BarcodeResolution
    data class Suggested(val name: String, val source: String) : BarcodeResolution
    data object Unknown : BarcodeResolution
}

/** The barcode → item name register (PHP: barcode_items, /barcodes, /api/barcodes). */
class BarcodeRepository(
    db: ThingsFinderDatabase,
    private val lookup: BarcodeNameLookup,
    private val settings: Settings,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.barcodeDao()

    fun observeAll(): Flow<List<BarcodeEntity>> = dao.observeAll()

    /** Own register first; then, if enabled, a free external lookup for a suggestion (never saved until the item is added). */
    suspend fun resolve(rawBarcode: String): BarcodeResolution {
        val barcode = rawBarcode.trim()
        if (barcode.isEmpty()) return BarcodeResolution.Unknown
        dao.find(barcode)?.let { return BarcodeResolution.Registered(it.name) }
        if (!settings.externalLookupEnabled.first()) return BarcodeResolution.Unknown
        val suggestion = lookup.lookup(barcode) ?: return BarcodeResolution.Unknown
        return BarcodeResolution.Suggested(suggestion.name, suggestion.source)
    }

    /** PHP: remember_barcode() — create or relabel. */
    suspend fun save(rawBarcode: String, rawName: String): Boolean {
        val barcode = rawBarcode.trim()
        val name = cleanName(rawName)
        if (barcode.isEmpty() || name.isEmpty()) return false
        dao.upsert(BarcodeEntity(barcode, name, now()))
        return true
    }

    suspend fun rename(barcode: String, rawName: String): Boolean {
        val name = cleanName(rawName)
        if (name.isEmpty()) return false
        dao.rename(barcode, name)
        return true
    }

    suspend fun delete(barcode: String) = dao.delete(barcode)
}
