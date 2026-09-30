package app.thingsfinder.sync

import androidx.room.withTransaction
import app.thingsfinder.data.db.BarcodeEntity
import app.thingsfinder.data.db.BoxEntity
import app.thingsfinder.data.db.ItemEntity
import app.thingsfinder.data.db.PlaceEntity
import app.thingsfinder.data.db.ThingsFinderDatabase
import app.thingsfinder.data.db.TombstoneEntity
import app.thingsfinder.domain.BoxLinks
import app.thingsfinder.domain.Slugs
import app.thingsfinder.domain.clampQuantity
import app.thingsfinder.domain.cleanName
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncOutcome {
    data class Synced(val pushed: Int, val pulled: Int) : SyncOutcome
    data object Disabled : SyncOutcome
    data object NotSignedIn : SyncOutcome
    /** The server rejected the token — the account was signed out on this phone. */
    data object SignedOut : SyncOutcome
    /** Worth retrying later (offline, server down). */
    data class Failed(val message: String, val retryable: Boolean) : SyncOutcome
}

/**
 * One round trip: send what changed on the phone since the last push (plus
 * pending deletes and the barcode register), apply what changed on the
 * server since the last cursor. Conflicts are per row, last write wins by
 * updated_at — the same rule the server applies.
 */
class SyncEngine(
    private val db: ThingsFinderDatabase,
    private val api: CloudApi,
    private val session: CloudSessionStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun syncNow(): SyncOutcome = mutex.withLock {
        val state = session.current()
        if (!state.enabled) return@withLock SyncOutcome.Disabled
        val token = state.token ?: return@withLock SyncOutcome.NotSignedIn

        // Taken *before* reading (minus 1 ms for edits stamped in the same millisecond),
        // so an edit made while the request is in flight is pushed next time.
        val pushStart = now() - 1
        val tombstones = db.tombstoneDao().getAll()
        val request = buildRequest(state.cursor, state.lastPushAt, tombstones)

        when (val result = api.sync(state.serverUrl, token, request)) {
            is ApiResult.Success -> {
                val response = result.value
                val (pulled, resurrected) = apply(response.changes, deletedHere = tombstones.map { it.uuid }.toSet())
                tombstones.maxOfOrNull { it.id }?.let { db.tombstoneDao().deleteUpTo(it) }
                session.synced(
                    // A delete the server refused (edited there since) brought a row back: pull
                    // everything next time so its children come back too.
                    cursor = if (resurrected) 0 else response.serverTime,
                    lastPushAt = retryFrom(request, response.skipped) ?: pushStart,
                    at = now(),
                )
                SyncOutcome.Synced(pushed = request.places.size + request.boxes.size + request.items.size + request.deleted.size, pulled = pulled)
            }
            is ApiResult.Unauthorized -> {
                session.signedOut(error = "Signed out by the server — sign in again to keep syncing.")
                SyncOutcome.SignedOut
            }
            is ApiResult.NetworkError -> fail("Couldn't reach ${hostOf(state.serverUrl)} — will retry.", retryable = true)
            is ApiResult.HttpError -> fail(
                if (result.code == 404) "This server doesn't support sync yet — update thingsFinder on the server." else "Server error (${result.code}): ${result.message}",
                retryable = result.code >= 500,
            )
            is ApiResult.BadResponse -> fail(result.message, retryable = false)
        }
    }

    /**
     * Rows the server couldn't place yet (e.g. their parent was missing) must be sent
     * again: move the push cursor back to just before the oldest of them.
     * "uuid in use" is permanent, so it doesn't hold the cursor back.
     */
    private fun retryFrom(request: SyncRequest, skipped: List<SyncSkipped>): Long? {
        val retry = skipped.filter { it.reason != "uuid in use" }.map { it.uuid }.toSet()
        if (retry.isEmpty()) return null
        val times = request.places.filter { it.uuid in retry }.map { it.updatedAt } +
            request.boxes.filter { it.uuid in retry }.map { it.updatedAt } +
            request.items.filter { it.uuid in retry }.map { it.updatedAt }
        return times.minOrNull()?.minus(1)
    }

    private suspend fun fail(message: String, retryable: Boolean): SyncOutcome {
        session.failed(message)
        return SyncOutcome.Failed(message, retryable)
    }

    internal suspend fun buildRequest(cursor: Long, lastPushAt: Long, tombstones: List<TombstoneEntity>): SyncRequest {
        val places = db.placeDao().changedSince(lastPushAt).map { SyncPlace(it.uuid, it.name, it.updatedAt) }
        val boxes = db.boxDao().changedSince(lastPushAt).map { SyncBox(it.uuid, it.placeUuid, it.name, it.shareToken, it.updatedAt) }
        val items = db.itemDao().changedSince(lastPushAt).mapNotNull {
            // Exactly one parent, like the server's CHECK constraint.
            when {
                it.boxUuid != null -> SyncItem(it.uuid, boxUuid = it.boxUuid, name = it.name, quantity = it.quantity, updatedAt = it.updatedAt)
                it.placeUuid != null -> SyncItem(it.uuid, placeUuid = it.placeUuid, name = it.name, quantity = it.quantity, updatedAt = it.updatedAt)
                else -> null
            }
        }
        return SyncRequest(
            since = cursor,
            places = places,
            boxes = boxes,
            items = items,
            deleted = tombstones.map { SyncDeleted(it.kind, it.uuid, it.deletedAt) },
            // Barcodes saved or renamed on this phone since the last push (created_at doubles
            // as "last changed here"; rows pulled from the server are stored with 0).
            barcodes = db.barcodeDao().changedSince(lastPushAt).map { SyncBarcode(it.barcode, it.name) },
        )
    }

    /**
     * Applies the server's changes in one transaction. Returns how many rows
     * changed here, and whether a row this phone just deleted was re-created.
     */
    internal suspend fun apply(changes: SyncChanges, deletedHere: Set<String> = emptySet()): Pair<Int, Boolean> = db.withTransaction {
        val placeDao = db.placeDao()
        val boxDao = db.boxDao()
        val itemDao = db.itemDao()
        var changed = 0
        var resurrected = false

        for (d in changes.deleted) {
            changed += when (d.kind) {
                TombstoneEntity.PLACES -> placeDao.deleteByUuidIfOlder(d.uuid, d.deletedAt)
                TombstoneEntity.BOXES -> boxDao.deleteByUuidIfOlder(d.uuid, d.deletedAt)
                TombstoneEntity.ITEMS -> itemDao.deleteByUuidIfOlder(d.uuid, d.deletedAt)
                else -> 0
            }
        }

        for (p in changes.places) {
            val name = cleanName(p.name)
            if (name.isEmpty()) continue
            val local = placeDao.findByUuid(p.uuid)
            if (local == null) {
                val slug = Slugs.unique(name) { placeDao.countSlug(it) > 0 }
                placeDao.insert(PlaceEntity(uuid = p.uuid, name = name, slug = slug, createdAt = p.updatedAt, updatedAt = p.updatedAt))
                changed++
                if (p.uuid in deletedHere) resurrected = true
            } else if (local.updatedAt < p.updatedAt) {
                val slug = Slugs.unique(name) { placeDao.countSlug(it, excludeId = local.id) > 0 }
                placeDao.update(local.copy(name = name, slug = slug, updatedAt = p.updatedAt))
                changed++
            }
        }

        for (b in changes.boxes) {
            val name = cleanName(b.name)
            if (name.isEmpty()) continue
            val place = placeDao.findByUuid(b.placeUuid) ?: continue
            val local = boxDao.findByUuid(b.uuid)
            // The server may have handed out a new share token (the phone's collided); adopt it if it's free here.
            val remoteToken = b.shareToken?.takeIf { BoxLinks.tokenFrom(BoxLinks.deepLink(it)) != null }
            if (local == null) {
                val token = remoteToken?.takeIf { boxDao.countToken(it, excludeId = 0) == 0 } ?: BoxLinks.newShareToken()
                val slug = Slugs.unique(name) { boxDao.countSlug(place.id, it) > 0 }
                boxDao.insert(
                    BoxEntity(
                        uuid = b.uuid, placeId = place.id, name = name, slug = slug, shareToken = token,
                        createdAt = b.updatedAt, updatedAt = b.updatedAt,
                    ),
                )
                changed++
                if (b.uuid in deletedHere) resurrected = true
            } else {
                val token = remoteToken?.takeIf { it != local.shareToken && boxDao.countToken(it, excludeId = local.id) == 0 }
                if (local.updatedAt < b.updatedAt) {
                    val slug = Slugs.unique(name) { boxDao.countSlug(place.id, it, excludeId = local.id) > 0 }
                    boxDao.update(
                        local.copy(placeId = place.id, name = name, slug = slug, shareToken = token ?: local.shareToken, updatedAt = b.updatedAt),
                    )
                    changed++
                } else if (token != null) {
                    boxDao.update(local.copy(shareToken = token)) // keep our edit, take the server's token
                    changed++
                }
            }
        }

        for (i in changes.items) {
            val name = cleanName(i.name)
            if (name.isEmpty()) continue
            val boxId = i.boxUuid?.let { boxDao.findByUuid(it)?.id }
            val placeId = if (boxId == null) i.placeUuid?.let { placeDao.findByUuid(it)?.id } else null
            if (boxId == null && placeId == null) continue
            val qty = clampQuantity(i.quantity.toLong())
            val local = itemDao.findByUuid(i.uuid)
            if (local == null) {
                itemDao.insert(
                    ItemEntity(
                        uuid = i.uuid, boxId = boxId, placeId = placeId, name = name, quantity = qty,
                        createdAt = i.updatedAt, updatedAt = i.updatedAt,
                    ),
                )
                changed++
            } else if (local.updatedAt < i.updatedAt) {
                itemDao.update(local.copy(boxId = boxId, placeId = placeId, name = name, quantity = qty, updatedAt = i.updatedAt))
                changed++
            }
        }

        val barcodeDao = db.barcodeDao()
        for (bc in changes.barcodes) {
            val code = bc.barcode.trim()
            val name = cleanName(bc.name)
            if (code.isEmpty() || name.isEmpty()) continue
            val local = barcodeDao.find(code)
            if (local == null || local.name != name) {
                // created_at 0: matches the server now, so it isn't pushed back.
                barcodeDao.upsert(BarcodeEntity(code, name, 0))
                changed++
            }
        }
        changed to resurrected
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')
}
