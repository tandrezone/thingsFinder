package app.thingsfinder.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire format of POST /api/sync on the thingsFinder server (includes/sync.php).
 * Pure Kotlin on purpose — no Android types — so it can be checked against
 * the real PHP endpoint from a plain JVM.
 *
 * Rows are identified by uuid; updated_at (epoch ms) decides conflicts, last
 * write wins. `since` is the server_time the previous sync returned.
 */

@Serializable
data class SyncPlace(
    val uuid: String,
    val name: String,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class SyncBox(
    val uuid: String,
    @SerialName("place_uuid") val placeUuid: String,
    val name: String,
    @SerialName("share_token") val shareToken: String? = null,
    @SerialName("updated_at") val updatedAt: Long,
)

/** Exactly one of [boxUuid] / [placeUuid] is set. */
@Serializable
data class SyncItem(
    val uuid: String,
    @SerialName("box_uuid") val boxUuid: String? = null,
    @SerialName("place_uuid") val placeUuid: String? = null,
    val name: String,
    val quantity: Int = 1,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class SyncDeleted(
    val kind: String,
    val uuid: String,
    @SerialName("deleted_at") val deletedAt: Long,
)

@Serializable
data class SyncBarcode(val barcode: String, val name: String)

@Serializable
data class SyncRequest(
    val since: Long,
    val places: List<SyncPlace> = emptyList(),
    val boxes: List<SyncBox> = emptyList(),
    val items: List<SyncItem> = emptyList(),
    val deleted: List<SyncDeleted> = emptyList(),
    val barcodes: List<SyncBarcode> = emptyList(),
)

@Serializable
data class SyncChanges(
    val places: List<SyncPlace> = emptyList(),
    val boxes: List<SyncBox> = emptyList(),
    val items: List<SyncItem> = emptyList(),
    val deleted: List<SyncDeleted> = emptyList(),
    val barcodes: List<SyncBarcode> = emptyList(),
)

@Serializable
data class SyncSkipped(val kind: String, val uuid: String, val reason: String)

@Serializable
data class SyncResponse(
    @SerialName("server_time") val serverTime: Long,
    val changes: SyncChanges = SyncChanges(),
    val skipped: List<SyncSkipped> = emptyList(),
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class RemoteUser(val id: Long, val username: String)

@Serializable
data class LoginResponse(val token: String, val user: RemoteUser)

@Serializable
data class MeResponse(val user: RemoteUser)

/** The server's error shape: {"error": "message"}. */
@Serializable
data class ErrorResponse(val error: String = "")

/** Normalises what the person types as the server address: https only, no trailing slash. */
object ServerUrl {
    const val DEFAULT = "https://thingsfinder.xyz"

    /** Returns a clean base URL, or null if it isn't a usable https address. */
    fun normalize(raw: String): String? {
        var url = raw.trim().trimEnd('/')
        if (url.isEmpty()) return DEFAULT
        if (!url.contains("://")) url = "https://$url"
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host.isEmpty() || host.any { it.isWhitespace() } || !host.contains('.')) return null
        return url
    }
}
