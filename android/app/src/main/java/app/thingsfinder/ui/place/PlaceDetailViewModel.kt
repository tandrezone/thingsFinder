package app.thingsfinder.ui.place

import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.thingsfinder.R
import app.thingsfinder.data.BarcodeRepository
import app.thingsfinder.data.InventoryRepository
import app.thingsfinder.data.db.BoxSummary
import app.thingsfinder.data.db.PlaceEntity
import app.thingsfinder.domain.ItemDraft
import app.thingsfinder.domain.ItemLocation
import app.thingsfinder.ui.common.ContainerViewModel
import app.thingsfinder.ui.common.ScreenEvent
import app.thingsfinder.ui.common.UiState
import app.thingsfinder.ui.common.errorMsg
import app.thingsfinder.ui.common.msg
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PlaceDetail(val place: PlaceEntity, val boxes: List<BoxSummary>)

/** PHP: the /place/{slug} route — boxes plus items loose in the place. */
class PlaceDetailViewModel(
    private val placeId: Long,
    inventory: InventoryRepository,
    barcodes: BarcodeRepository,
    extractText: suspend (Uri) -> List<ItemDraft>,
) : ContainerViewModel(inventory, barcodes, extractText) {

    override val location = ItemLocation.InPlace(placeId)

    val state: StateFlow<UiState<PlaceDetail>> =
        combine(inventory.observePlace(placeId), inventory.observeBoxes(placeId)) { place, boxes ->
            val result: UiState<PlaceDetail> =
                if (place == null) UiState.NotFound else UiState.Content(PlaceDetail(place, boxes))
            result
        }
            .catch { emit(UiState.Error(errorMsg(R.string.error_loading))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun renamePlace(name: String) = launchSafely {
        say(if (inventory.renamePlace(placeId, name)) msg(R.string.msg_place_renamed) else errorMsg(R.string.msg_place_name_empty))
    }

    fun deletePlace() = launchSafely {
        inventory.deletePlace(placeId)
        sendEvent(ScreenEvent.Deleted)
    }

    fun createBox(name: String) = launchSafely {
        val id = inventory.createBox(placeId, name)
        say(if (id != null) msg(R.string.msg_box_created, name.trim()) else errorMsg(R.string.msg_box_name_empty))
    }

    fun renameBox(id: Long, name: String) = launchSafely {
        say(if (inventory.renameBox(id, name)) msg(R.string.msg_box_renamed) else errorMsg(R.string.msg_box_name_empty))
    }

    fun moveBox(id: Long, newPlaceId: Long) = launchSafely {
        say(if (inventory.moveBox(id, newPlaceId)) msg(R.string.msg_box_moved) else errorMsg(R.string.msg_box_move_failed))
    }

    fun deleteBox(id: Long) = launchSafely {
        inventory.deleteBox(id)
        say(msg(R.string.msg_box_deleted))
    }
}
