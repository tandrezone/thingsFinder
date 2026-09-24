package app.thingsfinder.ui.barcodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.thingsfinder.R
import app.thingsfinder.data.db.BarcodeEntity
import app.thingsfinder.domain.Limits
import app.thingsfinder.platform.CodeScanner
import app.thingsfinder.platform.ScanResult
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
import app.thingsfinder.ui.theme.ThingsFinderTheme
import kotlinx.coroutines.launch

@Composable
fun BarcodesScreen(vm: BarcodesViewModel = viewModel(factory = containerFactory { BarcodesViewModel(it.barcodes) })) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    BarcodesContent(state, snackbar, onAdd = vm::add, onRename = vm::rename, onDelete = vm::delete)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodesContent(
    state: UiState<List<BarcodeEntity>>,
    snackbar: SnackbarHostState,
    onAdd: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<BarcodeEntity?>(null) }
    var deleting by remember { mutableStateOf<BarcodeEntity?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.barcodes_title)) }) },
        floatingActionButton = {
            if (state is UiState.Content) {
                ExtendedFloatingActionButton(
                    onClick = { addOpen = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add_barcode)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (state) {
            UiState.Loading -> LoadingState(Modifier.padding(padding))
            is UiState.Error -> ErrorState(state.message.text(), Modifier.padding(padding))
            UiState.NotFound -> Unit
            is UiState.Content -> ContentWidth(Modifier.padding(padding)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            stringResource(R.string.barcodes_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.data.isEmpty()) {
                        item { EmptyState(Icons.Outlined.ViewWeek, stringResource(R.string.barcodes_empty), hint = stringResource(R.string.barcodes_empty_hint)) }
                    }
                    items(state.data, key = { it.barcode }) { bc ->
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        ) {
                            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(bc.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        bc.barcode,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OverflowMenu(
                                    contentDescription = stringResource(R.string.cd_manage_barcode, bc.name),
                                    actions = listOf(
                                        MenuAction(stringResource(R.string.action_rename), Icons.Outlined.Edit) { renaming = bc },
                                        MenuAction(stringResource(R.string.action_remove), Icons.Outlined.Delete, destructive = true) { deleting = bc },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (addOpen) {
        AddBarcodeDialog(onConfirm = { code, name -> onAdd(code, name); addOpen = false }, onDismiss = { addOpen = false })
    }
    renaming?.let { bc ->
        NameDialog(
            title = stringResource(R.string.rename_barcode_title),
            label = stringResource(R.string.field_item_name),
            initial = bc.name,
            confirmLabel = stringResource(R.string.action_rename),
            onConfirm = { onRename(bc.barcode, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { bc ->
        ConfirmDialog(
            title = stringResource(R.string.delete_barcode_title),
            text = stringResource(R.string.delete_barcode_text),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = { onDelete(bc.barcode); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun AddBarcodeDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }
    val valid = code.isNotBlank() && name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_add_barcode)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { !it.isWhitespace() }.take(64) },
                    label = { Text(stringResource(R.string.field_barcode)) },
                    singleLine = true,
                    isError = scanError != null,
                    supportingText = scanError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    trailingIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                when (val r = CodeScanner.scanProductBarcode(context)) {
                                    is ScanResult.Scanned -> { code = r.value.take(64); scanError = null }
                                    is ScanResult.Failed -> scanError = r.message
                                    ScanResult.Cancelled -> Unit
                                }
                            }
                        }) { Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.action_scan)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= Limits.MAX_NAME_LENGTH) name = it },
                    label = { Text(stringResource(R.string.field_item_name)) },
                    placeholder = { Text(stringResource(R.string.field_item_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(code, name) }, enabled = valid) { Text(stringResource(R.string.action_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---- previews ------------------------------------------------------------

@ScreenPreviews
@Composable
private fun BarcodesPreview() = ThingsFinderTheme {
    BarcodesContent(
        UiState.Content(listOf(BarcodeEntity("5601234567890", "Duct tape", 0), BarcodeEntity("0012345678905", "AA batteries", 0))),
        remember { SnackbarHostState() }, { _, _ -> }, { _, _ -> }, {},
    )
}

@StatePreviews
@Composable
private fun BarcodesEmptyPreview() = ThingsFinderTheme {
    BarcodesContent(UiState.Content(emptyList()), remember { SnackbarHostState() }, { _, _ -> }, { _, _ -> }, {})
}
