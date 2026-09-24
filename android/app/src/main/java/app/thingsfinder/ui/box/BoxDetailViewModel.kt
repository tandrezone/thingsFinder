package app.thingsfinder.ui.box

import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.thingsfinder.R
import app.thingsfinder.data.BarcodeRepository
import app.thingsfinder.data.InventoryRepository
import app.thingsfinder.data.db.BoxWithPlace
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** PHP: the /place/{place}/{box} route — items in the box, its QR code and printable label. */
class BoxDetailViewModel(
    private val boxId: Long,
    inventory: InventoryRepository,
    barcodes: BarcodeRepository,
    extractText: suspend (Uri) -> List<ItemDraft>,
) : ContainerViewModel(inventory, barcodes, extractText) {

    override val location = ItemLocation.InBox(boxId)

    val state: StateFlow<UiState<BoxWithPlace>> = inventory.observeBox(boxId)
        .map<BoxWithPlace?, UiState<BoxWithPlace>> { box -> if (box == null) UiState.NotFound else UiState.Content(box) }
        .catch { emit(UiState.Error(errorMsg(R.string.error_loading))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun renameBox(name: String) = launchSafely {
        say(if (inventory.renameBox(boxId, name)) msg(R.string.msg_box_renamed) else errorMsg(R.string.msg_box_name_empty))
    }

    fun moveBox(newPlaceId: Long) = launchSafely {
        say(if (inventory.moveBox(boxId, newPlaceId)) msg(R.string.msg_box_moved) else errorMsg(R.string.msg_box_move_failed))
    }

    fun deleteBox() = launchSafely {
        inventory.deleteBox(boxId)
        sendEvent(ScreenEvent.Deleted)
    }
}
