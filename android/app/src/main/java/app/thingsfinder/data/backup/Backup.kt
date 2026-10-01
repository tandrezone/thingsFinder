package app.thingsfinder.data.backup

import androidx.room.withTransaction
import app.thingsfinder.data.db.BarcodeEntity
import app.thingsfinder.data.db.BoxEntity
import app.thingsfinder.data.db.ItemEntity
import app.thingsfinder.data.db.PlaceEntity
import app.thingsfinder.data.db.ThingsFinderDatabase
import app.thingsfinder.domain.BoxLinks
import app.thingsfinder.domain.Limits
import app.thingsfinder.domain.Slugs
import app.thingsfinder.domain.clampQuantity
import app.thingsfinder.domain.cleanName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/*
 * Whole-inventory backup file. Until the backup API exists this is how data
 * leaves the phone; the nested shape mirrors the server's data model
 * (places → boxes → items, plus loose items and the barcode register) so the
 * same document can later be PUT to /api/backup unchanged.
 */

@Serializable
data class BackupDocument(
    val format: String = FORMAT,
    val version: Int = VERSION,
    @SerialName("exported_at") val exportedAt: Long,
    val places: List<BackupPlace>,
    val barcodes: List<BackupBarcode> = emptyList(),
) {
    companion object {
        const val FORMAT = "thingsfinder-backup"
        const val VERSION = 1
    }
}

@Serializable
data class BackupPlace(
    val uuid: String? = null,
    val name: String,
    val slug: String? = null,
    /** Missing in backups made before places had QR codes; a new one is generated on restore. */
    @SerialName("share_token") val shareToken: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    val boxes: List<BackupBox> = emptyList(),
    val items: List<BackupItem> = emptyList(),
)

@Serializable
data class BackupBox(
    val uuid: String? = null,
    val name: String,
    val slug: String? = null,
    @SerialName("share_token") val shareToken: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    val items: List<BackupItem> = emptyList(),
)

@Serializable
data class BackupItem(
    val uuid: String? = null,
    val name: String,
    val quantity: Int = 1,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

@Serializable
data class BackupBarcode(val barcode: String, val name: String)

class BackupException(message: String) : Exception(message)

data class RestoreSummary(val places: Int, val boxes: Int, val items: Int, val barcodes: Int)

class BackupRepository(
    private val db: ThingsFinderDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportJson(): String {
        val places = db.placeDao().getAll()
        val boxes = db.boxDao().getAll().groupBy { it.placeId }
        val items = db.itemDao().getAll()
        val itemsByBox = items.filter { it.boxId != null }.groupBy { it.boxId!! }
        val itemsByPlace = items.filter { it.placeId != null }.groupBy { it.placeId!! }

        val doc = BackupDocument(
            exportedAt = now(),
            places = places.map { p ->
                BackupPlace(
                    uuid = p.uuid, name = p.name, slug = p.slug, shareToken = p.shareToken,
                    createdAt = p.createdAt, updatedAt = p.updatedAt,
                    boxes = boxes[p.id].orEmpty().map { b ->
                        BackupBox(
                            uuid = b.uuid, name = b.name, slug = b.slug, shareToken = b.shareToken,
                            createdAt = b.createdAt, updatedAt = b.updatedAt,
                            items = itemsByBox[b.id].orEmpty().map(::toBackup),
                        )
                    },
                    items = itemsByPlace[p.id].orEmpty().map(::toBackup),
                )
            },
            barcodes = db.barcodeDao().getAll().map { BackupBarcode(it.barcode, it.name) },
        )
        return json.encodeToString(BackupDocument.serializer(), doc)
    }

    /** Parses and validates without touching the database, so the UI can ask for confirmation first. */
    fun parse(text: String): BackupDocument {
        val doc = try {
            json.decodeFromString(BackupDocument.serializer(), text.trim().removePrefix("﻿"))
        } catch (e: Exception) {
            throw BackupException("That file isn't a thingsFinder backup.")
        }
        if (doc.format != BackupDocument.FORMAT) throw BackupException("That file isn't a thingsFinder backup.")
        if (doc.version > BackupDocument.VERSION) {
            throw BackupException("This backup was made by a newer version of thingsFinder.")
        }
        return doc
    }

    /** Replaces everything on the phone with [doc], in one transaction (all or nothing). */
    suspend fun restore(doc: BackupDocument): RestoreSummary = db.withTransaction {
        db.placeDao().deleteAll() // cascades to boxes and items
        db.barcodeDao().deleteAll()

        val t = now()
        val usedUuids = HashSet<String>()
        val usedTokens = HashSet<String>()
        val usedPlaceTokens = HashSet<String>()
        val usedPlaceSlugs = HashSet<String>()
        fun uuidOf(raw: String?): String =
            raw?.takeIf { it.isNotBlank() && usedUuids.add(it) } ?: UUID.randomUUID().toString().also { usedUuids.add(it) }

        var boxCount = 0
        var itemCount = 0
        for (p in doc.places) {
            val placeName = cleanName(p.name)
            if (placeName.isEmpty()) continue
            val placeSlug = Slugs.unique(p.slug?.takeIf { it.isNotBlank() } ?: placeName) { it in usedPlaceSlugs }
                .also { usedPlaceSlugs += it }
            val placeToken = p.shareToken?.takeIf { BoxLinks.isValidToken(it) && usedPlaceTokens.add(it) }
                ?: BoxLinks.newShareToken().also { usedPlaceTokens += it }
            val placeId = db.placeDao().insert(
                PlaceEntity(
                    uuid = uuidOf(p.uuid), name = placeName, slug = placeSlug, shareToken = placeToken,
                    createdAt = p.createdAt ?: t, updatedAt = p.updatedAt ?: t,
                ),
            )
            val usedBoxSlugs = HashSet<String>()
            for (b in p.boxes) {
                val boxName = cleanName(b.name)
                if (boxName.isEmpty()) continue
                val boxSlug = Slugs.unique(b.slug?.takeIf { it.isNotBlank() } ?: boxName) { it in usedBoxSlugs }
                    .also { usedBoxSlugs += it }
                val token = b.shareToken?.takeIf { BoxLinks.isValidToken(it) && usedTokens.add(it) }
                    ?: BoxLinks.newShareToken().also { usedTokens += it }
                val boxId = db.boxDao().insert(
                    BoxEntity(
                        uuid = uuidOf(b.uuid), placeId = placeId, name = boxName, slug = boxSlug, shareToken = token,
                        createdAt = b.createdAt ?: t, updatedAt = b.updatedAt ?: t,
                    ),
                )
                boxCount++
                itemCount += insertItems(b.items, boxId = boxId, placeId = null, t = t, uuidOf = ::uuidOf)
            }
            itemCount += insertItems(p.items, boxId = null, placeId = placeId, t = t, uuidOf = ::uuidOf)
        }

        val barcodes = doc.barcodes.mapNotNull { bc ->
            val code = bc.barcode.trim()
            val name = cleanName(bc.name)
            if (code.isEmpty() || name.isEmpty()) null else BarcodeEntity(code, name, t)
        }
        db.barcodeDao().insertAll(barcodes)

        RestoreSummary(places = usedPlaceSlugs.size, boxes = boxCount, items = itemCount, barcodes = barcodes.size)
    }

    private suspend fun insertItems(
        list: List<BackupItem>,
        boxId: Long?,
        placeId: Long?,
        t: Long,
        uuidOf: (String?) -> String,
    ): Int {
        val rows = list.mapNotNull { i ->
            val name = cleanName(i.name)
            if (name.isEmpty()) {
                null
            } else {
                ItemEntity(
                    uuid = uuidOf(i.uuid), boxId = boxId, placeId = placeId, name = name,
                    quantity = clampQuantity(i.quantity.toLong().coerceAtMost(Limits.MAX_QUANTITY.toLong())),
                    createdAt = i.createdAt ?: t, updatedAt = i.updatedAt ?: t,
                )
            }
        }
        db.itemDao().insertAll(rows)
        return rows.size
    }

    private fun toBackup(i: ItemEntity) =
        BackupItem(uuid = i.uuid, name = i.name, quantity = i.quantity, createdAt = i.createdAt, updatedAt = i.updatedAt)
}
