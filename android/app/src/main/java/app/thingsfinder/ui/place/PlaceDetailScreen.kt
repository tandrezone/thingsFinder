package app.thingsfinder.ui.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.thingsfinder.R
import app.thingsfinder.data.db.BoxSummary
import app.thingsfinder.data.db.ItemEntity
import app.thingsfinder.data.db.PlaceEntity
import app.thingsfinder.ui.common.ReviewState
import app.thingsfinder.ui.common.ScreenEvent
import app.thingsfinder.ui.common.UiState
import app.thingsfinder.ui.common.containerFactory
import app.thingsfinder.ui.common.text
import app.thingsfinder.ui.components.ConfirmDialog
import app.thingsfinder.ui.components.ContentWidth
import app.thingsfinder.ui.components.EmptyState
import app.thingsfinder.ui.components.ErrorState
import app.thingsfinder.ui.components.ItemsOverlays
import app.thingsfinder.ui.components.ItemsUiState
import app.thingsfinder.ui.components.LoadingState
import app.thingsfinder.ui.components.MenuAction
import app.thingsfinder.ui.components.MoveBoxDialog
import app.thingsfinder.ui.components.NameDialog
import app.thingsfinder.ui.components.OverflowMenu
import app.thingsfinder.ui.components.ScreenPreviews
import app.thingsfinder.ui.components.SectionHeader
import app.thingsfinder.ui.components.itemsSection
import app.thingsfinder.ui.components.pluralText
import app.thingsfinder.ui.components.rememberItemsUiState
import app.thingsfinder.ui.theme.ThingsFinderTheme

@Composable
fun PlaceDetailScreen(
    placeId: Long,
    onBack: () -> Unit,
    onOpenBox: (Long) -> Unit,
    vm: PlaceDetailViewModel = viewModel(
        key = "place-$placeId",
        factory = containerFactory { PlaceDetailViewModel(placeId, it.inventory, it.barcodes, it.textScanner::extractItems) },
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val ocrRunning by vm.ocrRunning.collectAsStateWithLifecycle()
    val destinations by vm.destinations.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val itemsUi = rememberItemsUiState()

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    LaunchedEffect(vm) { vm.events.collect { if (it == ScreenEvent.Deleted) onBack() } }

    var dialog by remember { mutableStateOf<PlaceScreenDialog?>(null) }
    var addBoxOpen by rememberSaveable { mutableStateOf(false) }
    val detail = (state as? UiState.Content)?.data

    PlaceDetailContent(
        state = state,
        items = items,
        review = review,
        ocrRunning = ocrRunning,
        itemsUi = itemsUi,
        snackbar = snackbar,
        onBack = onBack,
        onOpenBox = onOpenBox,
        onAddItem = { itemsUi.addOpen = true },
        onAddBox = { addBoxOpen = true },
        onPlaceDialog = { dialog = it },
    )

    if (detail != null) {
        ItemsOverlays(vm, itemsUi, containerLabel = detail.place.name, currentLocation = vm.location)
    }
    if (addBoxOpen) {
        NameDialog(
            title = stringResource(R.string.action_add_box),
            label = stringResource(R.string.field_box_name),
            placeholder = stringResource(R.string.field_box_name_hint),
            confirmLabel = stringResource(R.string.action_add),
            onConfirm = {
                vm.createBox(it)
                addBoxOpen = false
            },
            onDismiss = { addBoxOpen = false },
        )
    }
    when (val d = dialog) {
        PlaceScreenDialog.RenamePlace -> detail?.let {
            NameDialog(
                title = stringResource(R.string.rename_place_title),
                label = stringResource(R.string.field_place_name),
                initial = it.place.name,
                confirmLabel = stringResource(R.string.action_rename),
                onConfirm = { name -> vm.renamePlace(name); dialog = null },
                onDismiss = { dialog = null },
            )
        }
        PlaceScreenDialog.DeletePlace -> detail?.let {
            ConfirmDialog(
                title = stringResource(R.string.delete_place_title, it.place.name),
                text = stringResource(R.string.delete_place_text),
                confirmLabel = stringResource(R.string.action_delete),
                onConfirm = { vm.deletePlace(); dialog = null },
                onDismiss = { dialog = null },
            )
        }
        is PlaceScreenDialog.RenameBox -> NameDialog(
            title = stringResource(R.string.rename_box_title),
            label = stringResource(R.string.field_box_name),
            initial = d.box.name,
            confirmLabel = stringResource(R.string.action_rename),
            onConfirm = { name -> vm.renameBox(d.box.id, name); dialog = null },
            onDismiss = { dialog = null },
        )
        is PlaceScreenDialog.MoveBox -> MoveBoxDialog(
            destinations = destinations,
            currentPlaceId = d.box.placeId,
            onMove = { vm.moveBox(d.box.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is PlaceScreenDialog.DeleteBox -> ConfirmDialog(
            title = stringResource(R.string.delete_box_title, d.box.name),
            text = stringResource(R.string.delete_box_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { vm.deleteBox(d.box.id); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

sealed interface PlaceScreenDialog {
    data object RenamePlace : PlaceScreenDialog
    data object DeletePlace : PlaceScreenDialog
    data class RenameBox(val box: BoxSummary) : PlaceScreenDialog
    data class MoveBox(val box: BoxSummary) : PlaceScreenDialog
    data class DeleteBox(val box: BoxSummary) : PlaceScreenDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailContent(
    state: UiState<PlaceDetail>,
    items: UiState<List<ItemEntity>>,
    review: ReviewState?,
    ocrRunning: Boolean,
    itemsUi: ItemsUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenBox: (Long) -> Unit,
    onAddItem: () -> Unit,
    onAddBox: () -> Unit,
    onPlaceDialog: (PlaceScreenDialog) -> Unit,
) {
    val detail = (state as? UiState.Content)?.data
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.place?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (detail != null) {
                        OverflowMenu(
                            contentDescription = stringResource(R.string.cd_manage_place, detail.place.name),
                            actions = listOf(
                                MenuAction(stringResource(R.string.action_rename), Icons.Outlined.Edit) { onPlaceDialog(PlaceScreenDialog.RenamePlace) },
                                MenuAction(stringResource(R.string.action_delete_place), Icons.Outlined.Delete, destructive = true) {
                                    onPlaceDialog(PlaceScreenDialog.DeletePlace)
                                },
                            ),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (detail != null) {
                ExtendedFloatingActionButton(
                    onClick = onAddItem,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add_item)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (state) {
            UiState.Loading -> LoadingState(Modifier.padding(padding))
            UiState.NotFound -> ErrorState(stringResource(R.string.place_not_found), Modifier.padding(padding))
            is UiState.Error -> ErrorState(state.message.text(), Modifier.padding(padding))
            is UiState.Content -> ContentWidth(Modifier.padding(padding)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    boxesSection(state.data.boxes, onOpenBox, onAddBox, onPlaceDialog)
                    item(key = "items-header") {
                        Column {
                            SectionHeader(stringResource(R.string.items_title))
                            Text(
                                stringResource(R.string.place_items_note, state.data.place.name),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    itemsSection(items, itemsUi, review, ocrRunning) { stringResource(R.string.items_empty) }
                }
            }
        }
    }
}

private fun LazyListScope.boxesSection(
    boxes: List<BoxSummary>,
    onOpenBox: (Long) -> Unit,
    onAddBox: () -> Unit,
    onDialog: (PlaceScreenDialog) -> Unit,
) {
    item(key = "boxes-header") { SectionHeader(stringResource(R.string.boxes_title)) }
    if (boxes.isEmpty()) {
        item(key = "boxes-empty") { EmptyState(Icons.Outlined.Inventory2, stringResource(R.string.boxes_empty)) }
    }
    items(boxes, key = { "box-${it.id}" }) { box ->
        OutlinedCard(
            onClick = { onOpenBox(box.id) },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(box.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(pluralText(R.plurals.item_count, box.itemCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OverflowMenu(
                    contentDescription = stringResource(R.string.cd_manage_box, box.name),
                    actions = listOf(
                        MenuAction(stringResource(R.string.action_rename), Icons.Outlined.Edit) { onDialog(PlaceScreenDialog.RenameBox(box)) },
                        MenuAction(stringResource(R.string.action_move), Icons.AutoMirrored.Outlined.DriveFileMove) { onDialog(PlaceScreenDialog.MoveBox(box)) },
                        MenuAction(stringResource(R.string.action_delete), Icons.Outlined.Delete, destructive = true) { onDialog(PlaceScreenDialog.DeleteBox(box)) },
                    ),
                )
            }
        }
    }
    item(key = "add-box") {
        OutlinedButton(onClick = onAddBox) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_add_box))
        }
    }
}

// ---- previews ------------------------------------------------------------

@ScreenPreviews
@Composable
private fun PlaceDetailPreview() = ThingsFinderTheme {
    val place = PlaceEntity(1, "u1", "Garage", "garage", 0, 0)
    PlaceDetailContent(
        state = UiState.Content(
            PlaceDetail(place, listOf(BoxSummary(1, 1, "Tools bin", "tools-bin", 12), BoxSummary(2, 1, "Paint shelf", "paint-shelf", 0))),
        ),
        items = UiState.Content(
            listOf(
                ItemEntity(1, "i1", null, 1, "Rake", 1, 0, 0),
                ItemEntity(2, "i2", null, 1, "Bike pump", 2, 0, 0),
            ),
        ),
        review = null,
        ocrRunning = false,
        itemsUi = rememberItemsUiState(),
        snackbar = remember { SnackbarHostState() },
        onBack = {}, onOpenBox = {}, onAddItem = {}, onAddBox = {}, onPlaceDialog = {},
    )
}
