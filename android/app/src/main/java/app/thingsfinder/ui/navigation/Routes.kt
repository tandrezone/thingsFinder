package app.thingsfinder.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object PlacesRoute
@Serializable data class PlaceRoute(val placeId: Long)
@Serializable data class BoxRoute(val boxId: Long)
@Serializable data object SearchRoute
@Serializable data object BarcodesRoute
@Serializable data object SettingsRoute
