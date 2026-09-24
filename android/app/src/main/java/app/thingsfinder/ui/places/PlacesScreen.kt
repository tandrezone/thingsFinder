package app.thingsfinder.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.thingsfinder.R
import app.thingsfinder.data.db.PlaceSummary
import app.thingsfinder.ui.common.UiState
import app.thingsfinder.ui.common.containerFactory
import app.thingsfinder.ui.common.text
import app.thingsfinder.ui.components.ConfirmDialog
import app.thingsfinder.ui.components.ContentWidth
import app.thingsfinder.ui.components.EmptyState
import app.thingsfinder.ui.components.ErrorState
import app.thingsfinder.ui.components.LoadingState
import app.thingsfinder.ui.components.MenuAction
import app.thingsfinder.ui.components.NameDialog
import app.thingsfinder.ui.components.OverflowMenu
import app.thingsfinder.ui.components.ScreenPreviews
import app.thingsfinder.ui.components.StatePreviews
import app.thingsfinder.ui.components.pluralText
import app.thingsfinder.ui.theme.ThingsFinderTheme

@Composable
fun PlacesScreen(
    onOpenPlace: (Long) -> Unit,
    onScanBox: () -> Unit,
    vm: PlacesViewModel = viewModel(factory = containerFactory { PlacesViewModel(it.inventory) }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }

    PlacesContent(
        state = state,
        snackbar = snackbar,
        onOpenPlace = onOpenPlace,
        onScanBox = onScanBox,
        onRetry = vm::retry,
        onCreate = { vm.createPlace(it) },
        onRename = vm::renamePlace,
        onDelete = vm::deletePlace,
    )
}

private sealed interface PlaceDialog {
    data object Create : PlaceDialog
    data class Rename(val place: PlaceSummary) : PlaceDialog
    data class Delete(val place: PlaceSummary) : PlaceDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesContent(
    state: UiState<List<PlaceSummary>>,
    snackbar: SnackbarHostState,
    onOpenPlace: (Long) -> Unit,
    onScanBox: () -> Unit,
    onRetry: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var dialog by remember { mutableStateOf<PlaceDialog?>(null) }
    // The create dialog is the only one with typed input worth restoring after rotation.
    var createOpen by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.places_title)) },
                actions = {
                    IconButton(onClick = onScanBox) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.action_scan_box))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (state is UiState.Content) {
                ExtendedFloatingActionButton(
                    onClick = { createOpen = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add_place)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (state) {
            UiState.Loading -> LoadingState(Modifier.padding(padding))
            is UiState.Error -> ErrorState(state.message.text(), Modifier.padding(padding), onRetry)
            UiState.NotFound -> ErrorState(stringResource(R.string.error_loading), Modifier.padding(padding), onRetry)
            is UiState.Content -> ContentWidth(Modifier.padding(padding)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            stringResource(R.string.places_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    if (state.data.isEmpty()) {
                        item {
                            EmptyState(
                                Icons.Outlined.Inbox,
                                stringResource(R.string.places_empty),
                                hint = stringResource(R.string.places_empty_hint),
                            )
                        }
                    }
                    items(state.data, key = { it.id }) { place ->
                        PlaceCard(
                            place = place,
                            onOpen = { onOpenPlace(place.id) },
                            onRename = { dialog = PlaceDialog.Rename(place) },
                            onDelete = { dialog = PlaceDialog.Delete(place) },
                        )
                    }
                }
            }
        }
    }

    if (createOpen) {
        NameDialog(
            title = stringResource(R.string.action_add_place),
            label = stringResource(R.string.field_place_name),
            placeholder = stringResource(R.string.field_place_name_hint),
            confirmLabel = stringResource(R.string.action_add),
            onConfirm = {
                onCreate(it)
                createOpen = false
            },
            onDismiss = { createOpen = false },
        )
    }
    when (val d = dialog) {
        is PlaceDialog.Rename -> NameDialog(
            title = stringResource(R.string.rename_place_title),
            label = stringResource(R.string.field_place_name),
            initial = d.place.name,
            confirmLabel = stringResource(R.string.action_rename),
            onConfirm = {
                onRename(d.place.id, it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is PlaceDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.delete_place_title, d.place.name),
            text = stringResource(R.string.delete_place_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                onDelete(d.place.id)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        PlaceDialog.Create, null -> Unit
    }
}

@Composable
private fun PlaceCard(place: PlaceSummary, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    OutlinedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralText(R.plurals.box_count, place.boxCount) + " · " + pluralText(R.plurals.item_count, place.itemCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OverflowMenu(
                contentDescription = stringResource(R.string.cd_manage_place, place.name),
                actions = listOf(
                    MenuAction(stringResource(R.string.action_rename), Icons.Outlined.Edit, onClick = onRename),
                    MenuAction(stringResource(R.string.action_delete), Icons.Outlined.Delete, destructive = true, onClick = onDelete),
                ),
            )
        }
    }
}

// ---- previews ------------------------------------------------------------

private val samplePlaces = listOf(
    PlaceSummary(1, "Attic", "attic", 4, 37),
    PlaceSummary(2, "Garage", "garage", 6, 112),
    PlaceSummary(3, "Kitchen", "kitchen", 0, 3),
)

@ScreenPreviews
@Composable
private fun PlacesContentPreview() = ThingsFinderTheme {
    PlacesContent(UiState.Content(samplePlaces), remember { SnackbarHostState() }, {}, {}, {}, {}, { _, _ -> }, {})
}

@StatePreviews
@Composable
private fun PlacesEmptyPreview() = ThingsFinderTheme {
    PlacesContent(UiState.Content(emptyList()), remember { SnackbarHostState() }, {}, {}, {}, {}, { _, _ -> }, {})
}

@StatePreviews
@Composable
private fun PlacesLoadingPreview() = ThingsFinderTheme {
    PlacesContent(UiState.Loading, remember { SnackbarHostState() }, {}, {}, {}, {}, { _, _ -> }, {})
}

@StatePreviews
@Composable
private fun PlacesErrorPreview() = ThingsFinderTheme {
    PlacesContent(
        UiState.Error(app.thingsfinder.ui.common.errorMsg(R.string.error_loading)),
        remember { SnackbarHostState() }, {}, {}, {}, {}, { _, _ -> }, {},
    )
}
