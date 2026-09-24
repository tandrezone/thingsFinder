package app.thingsfinder.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thingsfinder.R
import app.thingsfinder.data.InventoryRepository
import app.thingsfinder.data.db.PlaceSummary
import app.thingsfinder.ui.common.UiMessage
import app.thingsfinder.ui.common.UiState
import app.thingsfinder.ui.common.errorMsg
import app.thingsfinder.ui.common.msg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** PHP: the home route — list of places with counts; create / rename / delete. */
class PlacesViewModel(private val inventory: InventoryRepository) : ViewModel() {

    private val retry = MutableStateFlow(0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<List<PlaceSummary>>> = retry
        .flatMapLatest {
            inventory.observePlaceSummaries()
                .map<List<PlaceSummary>, UiState<List<PlaceSummary>>> { UiState.Content(it) }
                .catch { emit(UiState.Error(errorMsg(R.string.error_loading))) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    fun retry() {
        retry.value++
    }

    fun createPlace(name: String, onCreated: (Long) -> Unit = {}) = launch {
        val id = inventory.createPlace(name)
        if (id == null) {
            _messages.send(errorMsg(R.string.msg_place_name_empty))
        } else {
            _messages.send(msg(R.string.msg_place_created, name.trim()))
            onCreated(id)
        }
    }

    fun renamePlace(id: Long, name: String) = launch {
        _messages.send(if (inventory.renamePlace(id, name)) msg(R.string.msg_place_renamed) else errorMsg(R.string.msg_place_name_empty))
    }

    fun deletePlace(id: Long) = launch {
        inventory.deletePlace(id)
        _messages.send(msg(R.string.msg_place_deleted))
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
