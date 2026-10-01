package app.thingsfinder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {
    /** PHP: the home page query — each place with its box count and total item count (boxed + loose). */
    @Query(
        """
        SELECT places.id, places.name, places.slug,
               (SELECT COUNT(*) FROM boxes WHERE boxes.place_id = places.id) AS box_count,
               (SELECT COUNT(*) FROM items JOIN boxes ON boxes.id = items.box_id WHERE boxes.place_id = places.id)
                 + (SELECT COUNT(*) FROM items WHERE items.place_id = places.id) AS item_count
        FROM places ORDER BY places.name COLLATE NOCASE
        """,
    )
    fun observeSummaries(): Flow<List<PlaceSummary>>

    @Query("SELECT * FROM places ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    fun observe(id: Long): Flow<PlaceEntity?>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun get(id: Long): PlaceEntity?

    @Query("SELECT COUNT(*) FROM places WHERE slug = :slug AND id != :excludeId")
    suspend fun countSlug(slug: String, excludeId: Long = 0): Int

    @Query("SELECT * FROM places WHERE share_token = :token")
    suspend fun findByToken(token: String): PlaceEntity?

    @Query("SELECT COUNT(*) FROM places WHERE share_token = :token AND id != :excludeId")
    suspend fun countToken(token: String, excludeId: Long): Int

    @Insert
    suspend fun insert(place: PlaceEntity): Long

    @Insert
    suspend fun insertAll(places: List<PlaceEntity>)

    @Update
    suspend fun update(place: PlaceEntity)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM places")
    suspend fun deleteAll()

    // ---- sync ----
    @Query("SELECT * FROM places WHERE updated_at > :since")
    suspend fun changedSince(since: Long): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE uuid = :uuid")
    suspend fun findByUuid(uuid: String): PlaceEntity?

    @Query("DELETE FROM places WHERE uuid = :uuid AND updated_at <= :deletedAt")
    suspend fun deleteByUuidIfOlder(uuid: String, deletedAt: Long): Int
}

@Dao
interface BoxDao {
    @Query(
        """
        SELECT boxes.id, boxes.place_id, boxes.name, boxes.slug,
               (SELECT COUNT(*) FROM items WHERE items.box_id = boxes.id) AS item_count
        FROM boxes WHERE place_id = :placeId ORDER BY name COLLATE NOCASE
        """,
    )
    fun observeSummaries(placeId: Long): Flow<List<BoxSummary>>

    @Query(
        """
        SELECT boxes.id, boxes.place_id, boxes.name, boxes.slug, boxes.share_token, places.name AS place_name
        FROM boxes JOIN places ON places.id = boxes.place_id
        ORDER BY places.name COLLATE NOCASE, boxes.name COLLATE NOCASE
        """,
    )
    fun observeAllWithPlace(): Flow<List<BoxWithPlace>>

    @Query(
        """
        SELECT boxes.id, boxes.place_id, boxes.name, boxes.slug, boxes.share_token, places.name AS place_name
        FROM boxes JOIN places ON places.id = boxes.place_id WHERE boxes.id = :id
        """,
    )
    fun observeWithPlace(id: Long): Flow<BoxWithPlace?>

    @Query("SELECT * FROM boxes ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<BoxEntity>

    @Query("SELECT * FROM boxes WHERE id = :id")
    suspend fun get(id: Long): BoxEntity?

    @Query("SELECT * FROM boxes WHERE share_token = :token")
    suspend fun findByToken(token: String): BoxEntity?

    @Query("SELECT COUNT(*) FROM boxes WHERE place_id = :placeId AND slug = :slug AND id != :excludeId")
    suspend fun countSlug(placeId: Long, slug: String, excludeId: Long = 0): Int

    @Insert
    suspend fun insert(box: BoxEntity): Long

    @Insert
    suspend fun insertAll(boxes: List<BoxEntity>)

    @Update
    suspend fun update(box: BoxEntity)

    @Query("DELETE FROM boxes WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- sync ----
    @Query(
        """
        SELECT boxes.uuid, boxes.name, boxes.share_token, boxes.updated_at, places.uuid AS place_uuid
        FROM boxes JOIN places ON places.id = boxes.place_id WHERE boxes.updated_at > :since
        """,
    )
    suspend fun changedSince(since: Long): List<BoxSyncRow>

    @Query("SELECT * FROM boxes WHERE uuid = :uuid")
    suspend fun findByUuid(uuid: String): BoxEntity?

    @Query("SELECT COUNT(*) FROM boxes WHERE share_token = :token AND id != :excludeId")
    suspend fun countToken(token: String, excludeId: Long): Int

    @Query("DELETE FROM boxes WHERE uuid = :uuid AND updated_at <= :deletedAt")
    suspend fun deleteByUuidIfOlder(uuid: String, deletedAt: Long): Int
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE box_id = :boxId ORDER BY name COLLATE NOCASE")
    fun observeInBox(boxId: Long): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE place_id = :placeId ORDER BY name COLLATE NOCASE")
    fun observeInPlace(placeId: Long): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: Long): ItemEntity?

    /** PHP: the /search query. [pattern] is already LIKE-escaped with '\'. */
    @Query(
        """
        SELECT items.id AS item_id, items.name AS item_name, items.quantity,
               boxes.id AS box_id, boxes.name AS box_name,
               places.id AS place_id, places.name AS place_name
        FROM items
        LEFT JOIN boxes ON boxes.id = items.box_id
        JOIN places ON places.id = COALESCE(items.place_id, boxes.place_id)
        WHERE items.name LIKE :pattern ESCAPE '\'
        ORDER BY items.name COLLATE NOCASE
        """,
    )
    fun search(pattern: String): Flow<List<SearchRow>>

    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Insert
    suspend fun insertAll(items: List<ItemEntity>)

    @Update
    suspend fun update(item: ItemEntity)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- sync ----
    @Query(
        """
        SELECT items.uuid, items.name, items.quantity, items.updated_at,
               b.uuid AS box_uuid, p.uuid AS place_uuid
        FROM items
        LEFT JOIN boxes b ON b.id = items.box_id
        LEFT JOIN places p ON p.id = items.place_id
        WHERE items.updated_at > :since
        """,
    )
    suspend fun changedSince(since: Long): List<ItemSyncRow>

    @Query("SELECT * FROM items WHERE uuid = :uuid")
    suspend fun findByUuid(uuid: String): ItemEntity?

    @Query("DELETE FROM items WHERE uuid = :uuid AND updated_at <= :deletedAt")
    suspend fun deleteByUuidIfOlder(uuid: String, deletedAt: Long): Int
}

@Dao
interface BarcodeDao {
    @Query("SELECT * FROM barcode_items ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<BarcodeEntity>>

    @Query("SELECT * FROM barcode_items ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<BarcodeEntity>

    @Query("SELECT * FROM barcode_items WHERE barcode = :barcode")
    suspend fun find(barcode: String): BarcodeEntity?

    /** PHP: remember_barcode() — insert, or relabel an existing barcode. */
    @Upsert
    suspend fun upsert(barcode: BarcodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(barcodes: List<BarcodeEntity>)

    /** Also stamps created_at, which the sync treats as "last changed on this phone". */
    @Query("UPDATE barcode_items SET name = :name, created_at = :at WHERE barcode = :barcode")
    suspend fun rename(barcode: String, name: String, at: Long)

    @Query("DELETE FROM barcode_items WHERE barcode = :barcode")
    suspend fun delete(barcode: String)

    @Query("DELETE FROM barcode_items")
    suspend fun deleteAll()

    /** Saved or renamed on this phone after [since] (see BarcodeRepository). */
    @Query("SELECT * FROM barcode_items WHERE created_at > :since")
    suspend fun changedSince(since: Long): List<BarcodeEntity>
}

@Dao
interface TombstoneDao {
    @Insert
    suspend fun insert(tombstone: TombstoneEntity)

    @Query("SELECT * FROM sync_tombstones ORDER BY id")
    suspend fun getAll(): List<TombstoneEntity>

    /** Drops tombstones a sync has delivered (ids up to and including [maxId]). */
    @Query("DELETE FROM sync_tombstones WHERE id <= :maxId")
    suspend fun deleteUpTo(maxId: Long)

    @Query("DELETE FROM sync_tombstones")
    suspend fun deleteAll()
}
