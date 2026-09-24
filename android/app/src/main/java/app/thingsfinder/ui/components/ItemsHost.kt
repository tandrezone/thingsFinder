package app.thingsfinder.ui.components

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thingsfinder.R
import app.thingsfinder.data.db.ItemEntity
import app.thingsfinder.domain.ItemLocation
import app.thingsfinder.platform.Sharing
import app.thingsfinder.ui.common.ContainerViewModel
import app.thingsfinder.ui.common.ReviewState
import app.thingsfinder.ui.common.UiState

/** Which item-related overlay is open. Only ids are kept, so the item is re-read from current state. */
@Stable
class ItemsUiState {
    var addOpen by mutableStateOf(false)
    var photoOpen by mutableStateOf(false)
    var importOpen by mutableStateOf(false)
    var reviewOpen by mutableStateOf(false)
    var editId by mutableStateOf<Long?>(null)
    var moveId by mutableStateOf<Long?>(null)
    var deleteId by mutableStateOf<Long?>(null)
}

private val addDraftSaver = listSaver<AddItemDraft, Any>(
    save = { listOf(it.barcode, it.name, it.quantity, it.autoFilled) },
    restore = { AddItemDraft(it[0] as String, it[1] as String, it[2] as String, it[3] as Boolean) },
)

/**
 * The items list for a place or a box plus every sheet and dialog that
 * goes with it (add, edit, move, delete, photo, JSON import, review).
 */
private val itemsUiSaver = listSaver<ItemsUiState, Any?>(
    save = { listOf(it.addOpen, it.photoOpen, it.importOpen, it.reviewOpen, it.editId, it.moveId, it.deleteId) },
    restore = {
        ItemsUiState().apply {
            addOpen = it[0] as Boolean
            photoOpen = it[1] as Boolean
            importOpen = it[2] as Boolean
            reviewOpen = it[3] as Boolean
            editId = it[4] as Long?
            moveId = it[5] as Long?
            deleteId = it[6] as Long?
        }
    },
)

/** Survives rotation, so an open sheet or dialog stays open (and its typed text with it). */
@Composable
fun rememberItemsUiState(): ItemsUiState = rememberSaveable(saver = itemsUiSaver) { ItemsUiState() }

fun LazyListScope.itemsSection(
    items: UiState<List<ItemEntity>>,
    ui: ItemsUiState,
    review: ReviewState?,
    ocrRunning: Boolean,
    emptyText: @Composable () -> String,
) {
    when (items) {
        UiState.Loading -> item(key = "items-loading") { LoadingState() }
        is UiState.Error, UiState.NotFound -> item(key = "items-error") {
            EmptyState(Icons.Outlined.Inventory2, stringResource(R.string.error_loading))
        }
        is UiState.Content -> {
            if (review != null) {
                item(key = "review-pending") { PendingReviewCard(review, onOpen = { ui.reviewOpen = true }) }
            }
            if (items.data.isEmpty()) {
                item(key = "items-empty") { EmptyState(Icons.Outlined.Inventory2, emptyText()) }
            }
            items(items.data, key = { "item-${it.id}" }) { item ->
                ItemCard(
                    item = item,
                    onEdit = { ui.editId = item.id },
                    onMove = { ui.moveId = item.id },
                    onDelete = { ui.deleteId = item.id },
                )
            }
            item(key = "bulk-add") {
                BulkAddCard(ocrRunning = ocrRunning, onPhoto = { ui.photoOpen = true }, onImportJson = { ui.importOpen = true })
            }
        }
    }
}

/** Place once per screen, outside the LazyColumn. */
@Composable
fun ItemsOverlays(vm: ContainerViewModel, ui: ItemsUiState, containerLabel: String, currentLocation: ItemLocation) {
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val destinations by vm.destinations.collectAsStateWithLifecycle()
    val barcodeState by vm.barcodeState.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val ocrRunning by vm.ocrRunning.collectAsStateWithLifecycle()
    var draft by rememberSaveable(stateSaver = addDraftSaver) { mutableStateOf(AddItemDraft()) }

    fun find(id: Long?): ItemEntity? = (items as? UiState.Content)?.data?.firstOrNull { it.id == id }

    // Open the review sheet as soon as new candidates arrive.
    var lastReviewSize by remember { mutableStateOf(0) }
    LaunchedEffect(review) {
        val size = review?.lines?.size ?: 0
        if (review != null && size > lastReviewSize) ui.reviewOpen = true
        if (review == null) ui.reviewOpen = false
        lastReviewSize = size
    }
    // The camera photo is only needed while OCR runs (PHP deletes the upload right after Tesseract reads it).
    LaunchedEffect(ocrRunning) { if (!ocrRunning) Sharing.deletePhoto(context) }

    if (ui.addOpen) {
        AddItemSheet(
            draft = draft,
            onDraftChange = { draft = it },
            barcodeState = barcodeState,
            onResolveBarcode = vm::resolveBarcode,
            onBarcodeEdited = vm::clearBarcodeState,
            onAdd = { name, qty, barcode ->
                vm.addItem(name, qty, barcode) { added ->
                    if (added) {
                        draft = AddItemDraft()
                        ui.addOpen = false
                    }
                }
            },
            onDismiss = { ui.addOpen = false },
        )
    }
    if (ui.photoOpen) {
        PhotoSourceSheet(
            onPicked = { uri ->
                ui.photoOpen = false
                vm.extractFromPhoto(uri)
            },
            onDismiss = { ui.photoOpen = false },
        )
    }
    if (ui.importOpen) {
        JsonImportSheet(
            containerLabel = containerLabel,
            onFileText = {
                ui.importOpen = false
                vm.importJson(it)
            },
            onError = {
                ui.importOpen = false
                vm.fileUnreadable()
            },
            onDismiss = { ui.importOpen = false },
        )
    }
    val currentReview = review
    if (ui.reviewOpen && currentReview != null) {
        ReviewSheet(
            review = currentReview,
            onUpdate = vm::updateReviewLine,
            onRemove = vm::removeReviewLine,
            onAddAll = vm::addReviewed,
            onDiscard = vm::discardReview,
            onDismiss = { ui.reviewOpen = false },
        )
    }

    find(ui.editId)?.let { item ->
        ItemEditDialog(
            title = stringResource(R.string.edit_item_title),
            initialName = item.name,
            initialQuantity = item.quantity,
            onConfirm = { name, qty ->
                vm.updateItem(item.id, name, qty)
                ui.editId = null
            },
            onDismiss = { ui.editId = null },
        )
    }
    find(ui.moveId)?.let { item ->
        MoveItemDialog(
            destinations = destinations,
            current = currentLocation,
            onMove = {
                vm.moveItem(item.id, it)
                ui.moveId = null
            },
            onDismiss = { ui.moveId = null },
        )
    }
    find(ui.deleteId)?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.delete_item_title, item.name),
            text = stringResource(R.string.delete_item_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                vm.deleteItem(item.id)
                ui.deleteId = null
            },
            onDismiss = { ui.deleteId = null },
        )
    }
}
