package app.thingsfinder.ui.box

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.thingsfinder.R
import app.thingsfinder.data.db.BoxWithPlace
import app.thingsfinder.data.db.ItemEntity
import app.thingsfinder.domain.BoxLinks
import app.thingsfinder.domain.LinkAction
import app.thingsfinder.domain.Slugs
import app.thingsfinder.platform.LabelRenderer
import app.thingsfinder.platform.QrCodes
import app.thingsfinder.platform.Sharing
import app.thingsfinder.ui.common.ReviewState
import app.thingsfinder.ui.common.ScreenEvent
import app.thingsfinder.ui.common.UiState
import app.thingsfinder.ui.common.containerFactory
import app.thingsfinder.ui.common.text
import app.thingsfinder.ui.components.ActionQrCard
import app.thingsfinder.ui.components.ApplyInitialAction
import app.thingsfinder.ui.components.ConfirmDialog
import app.thingsfinder.ui.components.ContentWidth
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
import app.thingsfinder.ui.components.StatePreviews
import app.thingsfinder.ui.components.itemsSection
import app.thingsfinder.ui.components.rememberItemsUiState
import app.thingsfinder.ui.theme.ThingsFinderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BoxDialog { Rename, Move, Delete }

/** [initialAction]: from an add-item / remove-item QR code — open with the add sheet up, or in remove mode. */
@Composable
fun BoxDetailScreen(
    boxId: Long,
    onBack: () -> Unit,
    onOpenPlace: (Long) -> Unit,
    initialAction: LinkAction = LinkAction.Open,
    vm: BoxDetailViewModel = viewModel(
        key = "box-$boxId",
        factory = containerFactory { BoxDetailViewModel(boxId, it.inventory, it.barcodes, it.textScanner::extractItems) },
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val ocrRunning by vm.ocrRunning.collectAsStateWithLifecycle()
    val destinations by vm.destinations.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val itemsUi = rememberItemsUiState()
    var dialog by remember { mutableStateOf<BoxDialog?>(null) }
    ApplyInitialAction(initialAction, itemsUi)

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    LaunchedEffect(vm) { vm.events.collect { if (it == ScreenEvent.Deleted) onBack() } }

    val box = (state as? UiState.Content)?.data
    val shareLabelTitle = stringResource(R.string.action_share_label)
    val shareQrTitle = stringResource(R.string.action_share_qr)

    BoxDetailContent(
        state = state,
        items = items,
        review = review,
        ocrRunning = ocrRunning,
        itemsUi = itemsUi,
        snackbar = snackbar,
        onBack = onBack,
        onOpenPlace = onOpenPlace,
        onAddItem = { itemsUi.addOpen = true },
        onTakeOne = vm::takeOne,
        onDialog = { dialog = it },
        onShareLabel = { b ->
            scope.launch {
                val bmp = withContext(Dispatchers.Default) { LabelRenderer.render(BoxLinks.deepLink(b.shareToken), b.name, b.placeName) }
                Sharing.sharePng(context, bmp, "label-${Slugs.slugify(b.name)}.png", shareLabelTitle)
            }
        },
        onShareQr = { b ->
            scope.launch {
                val bmp = withContext(Dispatchers.Default) { QrCodes.bitmap(BoxLinks.deepLink(b.shareToken), 1024, margin = 2) }
                Sharing.sharePng(context, bmp, "qr-${Slugs.slugify(b.name)}.png", shareQrTitle)
            }
        },
    )

    if (box != null) {
        ItemsOverlays(vm, itemsUi, containerLabel = "${box.placeName} / ${box.name}", currentLocation = vm.location)
        when (dialog) {
            BoxDialog.Rename -> NameDialog(
                title = stringResource(R.string.rename_box_title),
                label = stringResource(R.string.field_box_name),
                initial = box.name,
                confirmLabel = stringResource(R.string.action_rename),
                onConfirm = { vm.renameBox(it); dialog = null },
                onDismiss = { dialog = null },
            )
            BoxDialog.Move -> MoveBoxDialog(
                destinations = destinations,
                currentPlaceId = box.placeId,
                onMove = { vm.moveBox(it); dialog = null },
                onDismiss = { dialog = null },
            )
            BoxDialog.Delete -> ConfirmDialog(
                title = stringResource(R.string.delete_box_title, box.name),
                text = stringResource(R.string.delete_box_text),
                confirmLabel = stringResource(R.string.action_delete),
                onConfirm = { vm.deleteBox(); dialog = null },
                onDismiss = { dialog = null },
            )
            null -> Unit
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxDetailContent(
    state: UiState<BoxWithPlace>,
    items: UiState<List<ItemEntity>>,
    review: ReviewState?,
    ocrRunning: Boolean,
    itemsUi: ItemsUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenPlace: (Long) -> Unit,
    onAddItem: () -> Unit,
    onTakeOne: (Long) -> Unit,
    onDialog: (BoxDialog) -> Unit,
    onShareLabel: (BoxWithPlace) -> Unit,
    onShareQr: (BoxWithPlace) -> Unit,
) {
    val box = (state as? UiState.Content)?.data
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(box?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (box != null) {
                        OverflowMenu(
                            contentDescription = stringResource(R.string.cd_manage_box, box.name),
                            actions = listOf(
                                MenuAction(stringResource(R.string.action_rename), Icons.Outlined.Edit) { onDialog(BoxDialog.Rename) },
                                MenuAction(stringResource(R.string.action_move), Icons.AutoMirrored.Outlined.DriveFileMove) { onDialog(BoxDialog.Move) },
                                MenuAction(stringResource(R.string.action_delete_box), Icons.Outlined.Delete, destructive = true) { onDialog(BoxDialog.Delete) },
                            ),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (box != null) {
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
            UiState.NotFound -> ErrorState(stringResource(R.string.box_not_found), Modifier.padding(padding))
            is UiState.Error -> ErrorState(state.message.text(), Modifier.padding(padding))
            is UiState.Content -> ContentWidth(Modifier.padding(padding)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "place-chip") {
                        AssistChip(
                            onClick = { onOpenPlace(state.data.placeId) },
                            label = { Text(stringResource(R.string.box_in_place, state.data.placeName)) },
                        )
                    }
                    // Remove mode is about the items: keep them at the top.
                    if (!itemsUi.removeMode) {
                        item(key = "qr") { QrCard(state.data, onShareLabel = { onShareLabel(state.data) }, onShareQr = { onShareQr(state.data) }) }
                        item(key = "qr-actions") {
                            ActionQrCard(
                                name = state.data.name,
                                isPlace = false,
                                addLink = BoxLinks.boxLink(state.data.shareToken, LinkAction.Add),
                                removeLink = BoxLinks.boxLink(state.data.shareToken, LinkAction.Remove),
                            )
                        }
                    }
                    item(key = "items-header") { SectionHeader(stringResource(R.string.items_title)) }
                    itemsSection(items, itemsUi, review, ocrRunning, onTakeOne) { stringResource(R.string.box_empty) }
                }
            }
        }
    }
}

/** PHP: the `.qr-block` and "Printable label" blocks on the box page. */
@Composable
private fun QrCard(box: BoxWithPlace, onShareLabel: () -> Unit, onShareQr: () -> Unit) {
    val qr: Bitmap = remember(box.shareToken) { QrCodes.bitmap(BoxLinks.deepLink(box.shareToken), 360) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // Always on white, so the code stays scannable in dark theme too.
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_box_qr, box.name),
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .size(112.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .padding(4.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.qr_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onShareLabel) {
                    Icon(Icons.Outlined.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_share_label))
                }
                OutlinedButton(onClick = onShareQr) {
                    Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_share_qr))
                }
            }
        }
    }
}

// ---- previews ------------------------------------------------------------

private val previewBox = BoxWithPlace(1, 1, "Tools bin 2", "tools-bin-2", "0123456789abcdef0123456789abcdef", "Garage")

@ScreenPreviews
@Composable
private fun BoxDetailPreview() = ThingsFinderTheme {
    BoxDetailContent(
        state = UiState.Content(previewBox),
        items = UiState.Content(
            listOf(
                ItemEntity(1, "a", 1, null, "Hot glue gun", 1, 0, 0),
                ItemEntity(2, "b", 1, null, "9V battery", 4, 0, 0),
                ItemEntity(3, "c", 1, null, "Duct tape", 3, 0, 0),
            ),
        ),
        review = ReviewState(ReviewState.Source.Photo, listOf(app.thingsfinder.domain.ItemDraft("Zip ties"))),
        ocrRunning = false,
        itemsUi = rememberItemsUiState(),
        snackbar = remember { SnackbarHostState() },
        onBack = {}, onOpenPlace = {}, onAddItem = {}, onTakeOne = {}, onDialog = {}, onShareLabel = {}, onShareQr = {},
    )
}

@StatePreviews
@Composable
private fun BoxEmptyPreview() = ThingsFinderTheme {
    BoxDetailContent(
        state = UiState.Content(previewBox), items = UiState.Content(emptyList()), review = null, ocrRunning = true,
        itemsUi = rememberItemsUiState(), snackbar = remember { SnackbarHostState() },
        onBack = {}, onOpenPlace = {}, onAddItem = {}, onTakeOne = {}, onDialog = {}, onShareLabel = {}, onShareQr = {},
    )
}

@StatePreviews
@Composable
private fun BoxNotFoundPreview() = ThingsFinderTheme {
    BoxDetailContent(
        state = UiState.NotFound, items = UiState.Loading, review = null, ocrRunning = false,
        itemsUi = rememberItemsUiState(), snackbar = remember { SnackbarHostState() },
        onBack = {}, onOpenPlace = {}, onAddItem = {}, onTakeOne = {}, onDialog = {}, onShareLabel = {}, onShareQr = {},
    )
}
