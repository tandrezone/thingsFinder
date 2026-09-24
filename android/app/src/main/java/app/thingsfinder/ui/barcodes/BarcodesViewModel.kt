package app.thingsfinder.ui.barcodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thingsfinder.R
import app.thingsfinder.data.BarcodeRepository
import app.thingsfinder.data.db.BarcodeEntity
import app.thingsfinder.ui.common.UiMessage
import app.thingsfinder.ui.common.UiState
import app.thingsfinder.ui.common.errorMsg
import app.thingsfinder.ui.common.msg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** PHP: the /barcodes register page. */
class BarcodesViewModel(private val barcodes: BarcodeRepository) : ViewModel() {

    val state: StateFlow<UiState<List<BarcodeEntity>>> = barcodes.observeAll()
        .map<List<BarcodeEntity>, UiState<List<BarcodeEntity>>> { UiState.Content(it) }
        .catch { emit(UiState.Error(errorMsg(R.string.error_loading))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    fun add(barcode: String, name: String) = launch {
        _messages.send(
            if (barcodes.save(barcode, name)) msg(R.string.msg_barcode_linked, name.trim())
            else errorMsg(R.string.msg_barcode_both_required),
        )
    }

    fun rename(barcode: String, name: String) = launch {
        _messages.send(if (barcodes.rename(barcode, name)) msg(R.string.msg_barcode_updated) else errorMsg(R.string.msg_item_name_empty))
    }

    fun delete(barcode: String) = launch {
        barcodes.delete(barcode)
        _messages.send(msg(R.string.msg_barcode_removed))
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(errorMsg(R.string.error_generic))
            }
        }
    }
}
