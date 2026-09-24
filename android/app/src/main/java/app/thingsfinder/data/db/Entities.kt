package app.thingsfinder.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Mirrors the server schema in includes/db.php (places / boxes / items /
 * barcode_items) minus users, shares and owner_id: the phone is single-user.
 * `uuid` and `updated_at` are extra, so a future backup/sync API can match
 * rows across devices without relying on local autoincrement ids.
 */

@Entity(
    tableName = "places",
    indices = [Index(value = ["slug"], unique = true), Index(value = ["uuid"], unique = true)],
)
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val name: String,
    val slug: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "boxes",
    foreignKeys = [
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["place_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["place_id", "slug"], unique = true),
        Index(value = ["share_token"], unique = true),
        Index(value = ["uuid"], unique = true),
    ],
)
data class BoxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "place_id") val placeId: Long,
    val name: String,
    val slug: String,
    @ColumnInfo(name = "share_token") val shareToken: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Exactly one of [boxId] / [placeId] is set — enforced by InventoryRepository (Room can't declare CHECK constraints). */
@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = BoxEntity::class,
            parentColumns = ["id"],
            childColumns = ["box_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["place_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["box_id"]),
        Index(value = ["place_id"]),
        Index(value = ["name"]),
        Index(value = ["uuid"], unique = true),
    ],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "box_id") val boxId: Long?,
    @ColumnInfo(name = "place_id") val placeId: Long?,
    val name: String,
    val quantity: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "barcode_items")
data class BarcodeEntity(
    @PrimaryKey val barcode: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

// ---- Query projections -------------------------------------------------

data class PlaceSummary(
    val id: Long,
    val name: String,
    val slug: String,
    @ColumnInfo(name = "box_count") val boxCount: Int,
    @ColumnInfo(name = "item_count") val itemCount: Int,
)

data class BoxSummary(
    val id: Long,
    @ColumnInfo(name = "place_id") val placeId: Long,
    val name: String,
    val slug: String,
    @ColumnInfo(name = "item_count") val itemCount: Int,
)

/** A box together with its place's name — for the move pickers and the box header. */
data class BoxWithPlace(
    val id: Long,
    @ColumnInfo(name = "place_id") val placeId: Long,
    val name: String,
    val slug: String,
    @ColumnInfo(name = "share_token") val shareToken: String,
    @ColumnInfo(name = "place_name") val placeName: String,
)

data class SearchRow(
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "item_name") val itemName: String,
    val quantity: Int,
    @ColumnInfo(name = "box_id") val boxId: Long?,
    @ColumnInfo(name = "box_name") val boxName: String?,
    @ColumnInfo(name = "place_id") val placeId: Long,
    @ColumnInfo(name = "place_name") val placeName: String,
)
