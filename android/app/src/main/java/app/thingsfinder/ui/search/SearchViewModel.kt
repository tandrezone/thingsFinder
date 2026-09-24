package app.thingsfinder.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thingsfinder.R
import app.thingsfinder.data.InventoryRepository
import app.thingsfinder.data.db.SearchRow
import app.thingsfinder.ui.common.UiState
import app.thingsfinder.ui.common.errorMsg
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SearchResults(val query: String, val rows: List<SearchRow>)

/** PHP: /search?q= — every item whose name contains the query, with its place (and box). */
class SearchViewModel(
    private val inventory: InventoryRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {

    /** Survives rotation and process death. */
    val query: StateFlow<String> = saved.getStateFlow(KEY, "")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<UiState<SearchResults>> = query
        .debounce { if (it.isBlank()) 0L else 200L }
        .flatMapLatest { q ->
            if (q.isBlank()) {
                flowOf<UiState<SearchResults>>(UiState.Content(SearchResults("", emptyList())))
            } else {
                inventory.search(q)
                    .map<List<SearchRow>, UiState<SearchResults>> { UiState.Content(SearchResults(q.trim(), it)) }
                    .catch { emit(UiState.Error(errorMsg(R.string.error_loading))) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun onQueryChange(value: String) {
        saved[KEY] = value.take(200)
    }

    private companion object {
        const val KEY = "q"
    }
}
