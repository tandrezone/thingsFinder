package app.thingsfinder.ui.common

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thingsfinder.R
import app.thingsfinder.data.AddItemOutcome
import app.thingsfinder.data.BarcodeRepository
import app.thingsfinder.data.BarcodeResolution
import app.thingsfinder.data.InventoryRepository
import app.thingsfinder.data.MoveDestination
import app.thingsfinder.data.db.ItemEntity
import app.thingsfinder.domain.ImportParser
import app.thingsfinder.domain.ItemDraft
import app.thingsfinder.domain.ItemLocation
import app.thingsfinder.domain.OcrLines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Barcode field status in the add-item sheet. */
sealed interface BarcodeLookupState {
    data object Idle : BarcodeLookupState
    data class Looking(val barcode: String) : BarcodeLookupState
    data class Registered(val barcode: String, val name: String) : BarcodeLookupState
    data class Suggested(val barcode: String, val name: String, val source: String) : BarcodeLookupState
    data class Unknown(val barcode: String) : BarcodeLookupState
}

/** Candidate items from a photo (OCR) or a JSON file, edited before anything is saved. */
data class ReviewState(
    val source: Source,
    val lines: List<ItemDraft>,
    val problems: List<String> = emptyList(),
) {
    enum class Source { Photo, Json }
}

sealed interface ScreenEvent {
    /** The container this screen shows was deleted — pop back. */
    data object Deleted : ScreenEvent
}

/**
 * Everything the "Items" section does, shared by the place and box screens
 * (PHP: handle_item_action / handle_scan_action / render_items_section,
 * which index.php also shares between both pages).
 */
abstract class ContainerViewModel(
    protected val inventory: InventoryRepository,
    private val barcodes: BarcodeRepository,
    private val extractText: suspend (Uri) -> List<ItemDraft>,
) : ViewModel() {

    abstract val location: ItemLocation

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    private val _events = Channel<ScreenEvent>(Channel.BUFFERED)
    val events: Flow<ScreenEvent> = _events.receiveAsFlow()

    val items: StateFlow<UiState<List<ItemEntity>>> by lazy {
        inventory.observeItems(location)
            .map<List<ItemEntity>, UiState<List<ItemEntity>>> { UiState.Content(it) }
            .catch { emit(UiState.Error(errorMsg(R.string.error_loading))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)
    }

    val destinations: StateFlow<List<MoveDestination>> by lazy {
        inventory.observeMoveDestinations()
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    private val _barcodeState = MutableStateFlow<BarcodeLookupState>(BarcodeLookupState.Idle)
    val barcodeState: StateFlow<BarcodeLookupState> = _barcodeState.asStateFlow()
    private var lookupJob: Job? = null

    private val _review = MutableStateFlow<ReviewState?>(null)
    val review: StateFlow<ReviewState?> = _review.asStateFlow()

    private val _ocrRunning = MutableStateFlow(false)
    val ocrRunning: StateFlow<Boolean> = _ocrRunning.asStateFlow()

    protected fun say(message: UiMessage) {
        _messages.trySend(message)
    }

    protected fun sendEvent(event: ScreenEvent) {
        _events.trySend(event)
    }

    /** Runs [block], turning unexpected failures into a snackbar instead of a crash. */
    protected fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(errorMsg(R.string.error_generic))
            }
        }
    }

    // ---- barcode --------------------------------------------------------

    fun resolveBarcode(raw: String) {
        val code = raw.trim()
        lookupJob?.cancel()
        if (code.isEmpty()) {
            _barcodeState.value = BarcodeLookupState.Idle
            return
        }
        _barcodeState.value = BarcodeLookupState.Looking(code)
        lookupJob = viewModelScope.launch {
            _barcodeState.value = try {
                when (val r = barcodes.resolve(code)) {
                    is BarcodeResolution.Registered -> BarcodeLookupState.Registered(code, r.name)
                    is BarcodeResolution.Suggested -> BarcodeLookupState.Suggested(code, r.name, r.source)
                    BarcodeResolution.Unknown -> BarcodeLookupState.Unknown(code)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BarcodeLookupState.Unknown(code)
            }
        }
    }

    fun clearBarcodeState() {
        lookupJob?.cancel()
        _barcodeState.value = BarcodeLookupState.Idle
    }

    // ---- items ----------------------------------------------------------

    /** Returns via [onDone] whether the sheet can close (true = item added). */
    fun addItem(name: String, quantity: Int, barcode: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val outcome = try {
                inventory.addItem(location, name, quantity, barcode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(errorMsg(R.string.error_generic))
                onDone(false)
                return@launch
            }
            when (outcome) {
                is AddItemOutcome.Added -> {
                    clearBarcodeState()
                    onDone(true)
                    say(
                        when {
                            outcome.recognized -> msg(R.string.msg_item_added_recognized, outcome.name)
                            outcome.remembered -> msg(R.string.msg_item_added_remembered, outcome.name)
                            else -> msg(R.string.msg_item_added, outcome.name)
                        },
                    )
                }
                AddItemOutcome.NeedsName -> {
                    say(errorMsg(R.string.msg_barcode_needs_name))
                    onDone(false)
                }
                AddItemOutcome.EmptyName -> {
                    say(errorMsg(R.string.msg_item_name_empty))
                    onDone(false)
                }
            }
        }
    }

    fun updateItem(id: Long, name: String, quantity: Int) = launchSafely {
        if (inventory.updateItem(id, name, quantity)) say(msg(R.string.msg_item_updated))
        else say(errorMsg(R.string.msg_item_name_empty))
    }

    fun moveItem(id: Long, destination: ItemLocation) = launchSafely {
        if (inventory.moveItem(id, destination)) say(msg(R.string.msg_item_moved))
        else say(errorMsg(R.string.msg_item_move_failed))
    }

    /** Remove mode's −1. */
    fun takeOne(id: Long) = launchSafely {
        inventory.takeOne(id)?.let { left -> say(msg(R.string.msg_item_took_one, left)) }
    }

    fun deleteItem(id: Long) = launchSafely {
        inventory.deleteItem(id)
        say(msg(R.string.msg_item_deleted))
    }

    // ---- photo (OCR) & JSON import, both reviewed before saving ------------

    fun extractFromPhoto(uri: Uri) {
        if (_ocrRunning.value) return
        _ocrRunning.value = true
        viewModelScope.launch {
            try {
                val found = extractText(uri)
                if (found.isEmpty()) {
                    say(errorMsg(R.string.msg_ocr_nothing_found))
                } else {
                    val existing = _review.value?.takeIf { it.source == ReviewState.Source.Photo }?.lines.orEmpty()
                    val (merged, added) = OcrLines.merge(existing, found)
                    _review.value = ReviewState(ReviewState.Source.Photo, merged)
                    say(msg(R.string.msg_ocr_found, added))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(errorMsg(R.string.msg_ocr_failed))
            } finally {
                _ocrRunning.value = false
            }
        }
    }

    fun importJson(text: String) {
        val result = ImportParser.parseText(text)
        if (result.items.isEmpty()) {
            val reason = result.errors.firstOrNull()
            say(if (reason != null) errorMsg(R.string.msg_import_none_reason, reason) else errorMsg(R.string.msg_import_none))
            return
        }
        _review.value = ReviewState(ReviewState.Source.Json, result.items, result.errors)
    }

    fun fileUnreadable() = say(errorMsg(R.string.msg_file_unreadable))

    fun updateReviewLine(index: Int, draft: ItemDraft) = _review.update { state ->
        if (state == null || index !in state.lines.indices || draft.name.isBlank()) {
            state
        } else {
            state.copy(lines = state.lines.toMutableList().also { it[index] = draft })
        }
    }

    fun removeReviewLine(index: Int) = _review.update { state ->
        if (state == null || index !in state.lines.indices) {
            state
        } else {
            state.copy(lines = state.lines.toMutableList().also { it.removeAt(index) })
        }
    }

    fun discardReview() {
        _review.value = null
    }

    fun addReviewed() {
        val state = _review.value ?: return
        launchSafely {
            val added = inventory.addItems(location, state.lines)
            _review.value = null
            say(
                if (state.problems.isEmpty()) msg(R.string.msg_items_added, added)
                else errorMsg(R.string.msg_items_added_skipped, added, state.problems.size),
            )
        }
    }
}
