package app.thingsfinder.ui.navigation

import kotlinx.serialization.Serializable

// PlaceRoute / BoxRoute `action`: LinkAction.segment ("add" / "remove") when opened from an add-item / remove-item code.
@Serializable data object PlacesRoute
@Serializable data class PlaceRoute(val placeId: Long, val action: String? = null)
@Serializable data class BoxRoute(val boxId: Long, val action: String? = null)
@Serializable data object SearchRoute
@Serializable data object BarcodesRoute
@Serializable data object SettingsRoute
/** [joinToken]: from an invite link / QR — the screen offers to join. */
@Serializable data class GroupsRoute(val joinToken: String? = null)
