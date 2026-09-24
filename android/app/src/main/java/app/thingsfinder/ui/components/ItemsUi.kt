package app.thingsfinder.ui.components

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.thingsfinder.R
import app.thingsfinder.data.db.ItemEntity
import app.thingsfinder.domain.ImportPrompt
import app.thingsfinder.domain.ItemDraft
import app.thingsfinder.domain.Limits
import app.thingsfinder.domain.parseQuantity
import app.thingsfinder.platform.CodeScanner
import app.thingsfinder.platform.ScanResult
import app.thingsfinder.platform.Sharing
import app.thingsfinder.ui.common.BarcodeLookupState
import app.thingsfinder.ui.common.ReviewState
import kotlinx.coroutines.launch

/** PHP: one `li.card` in the items list. */
@Composable
fun ItemCard(
    item: ItemEntity,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    canMove: Boolean = true,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            QtyBadge(item.quantity)
            Spacer(Modifier.weight(1f))
            OverflowMenu(
                contentDescription = stringResource(R.string.cd_manage_item, item.name),
                actions = buildList {
                    add(MenuAction(stringResource(R.string.action_edit), Icons.Outlined.Edit, onClick = onEdit))
                    if (canMove) add(MenuAction(stringResource(R.string.action_move), Icons.AutoMirrored.Outlined.DriveFileMove, onClick = onMove))
                    add(MenuAction(stringResource(R.string.action_delete), Icons.Outlined.Delete, destructive = true, onClick = onDelete))
                },
            )
        }
    }
}

/** The "more ways to add" tile — PHP: the photo-scan and JSON-import cards. */
@Composable
fun BulkAddCard(
    ocrRunning: Boolean,
    onPhoto: () -> Unit,
    onImportJson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.bulk_add_title), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPhoto, enabled = !ocrRunning) {
                    if (ocrRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (ocrRunning) R.string.ocr_reading else R.string.action_from_photo))
                }
                OutlinedButton(onClick = onImportJson) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_import_json))
                }
            }
        }
    }
}

/** Shown while photo/JSON candidates are waiting, so closing the review sheet never loses them. */
@Composable
fun PendingReviewCard(review: ReviewState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.review_pending, review.lines.size),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) { Text(stringResource(R.string.action_review)) }
        }
    }
}

/** Draft kept by the screen (rememberSaveable), so closing and reopening the sheet — or rotating — keeps what was typed. */
data class AddItemDraft(val barcode: String = "", val name: String = "", val quantity: String = "1", val autoFilled: Boolean = false)

/**
 * PHP: the "Add an item" form with its barcode row. Scanning (or typing a
 * barcode and pressing Done) looks it up: a registered barcode fills the
 * name, an unknown one may get a suggestion from the free product
 * databases; either way nothing is saved until Add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemSheet(
    draft: AddItemDraft,
    onDraftChange: (AddItemDraft) -> Unit,
    barcodeState: BarcodeLookupState,
    onResolveBarcode: (String) -> Unit,
    onBarcodeEdited: () -> Unit,
    onAdd: (name: String, quantity: Int, barcode: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var scanError by remember { mutableStateOf<String?>(null) }

    // Apply a lookup result to the name field — only if the person hasn't typed their own name.
    LaunchedEffect(barcodeState) {
        val suggested = when (barcodeState) {
            is BarcodeLookupState.Registered -> barcodeState.name.takeIf { barcodeState.barcode == draft.barcode.trim() }
            is BarcodeLookupState.Suggested -> barcodeState.name.takeIf { barcodeState.barcode == draft.barcode.trim() }
            else -> null
        }
        if (suggested != null && (draft.name.isBlank() || draft.autoFilled)) {
            onDraftChange(draft.copy(name = suggested.take(Limits.MAX_NAME_LENGTH), autoFilled = true))
        }
    }

    val registered = barcodeState is BarcodeLookupState.Registered && barcodeState.barcode == draft.barcode.trim()
    val canAdd = draft.name.isNotBlank() || registered
    val submit = { onAdd(draft.name, parseQuantity(draft.quantity), draft.barcode) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.add_item_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = draft.barcode,
                onValueChange = { v ->
                    val code = v.filter { !it.isWhitespace() }.take(64)
                    if (code != draft.barcode) {
                        onBarcodeEdited()
                        onDraftChange(draft.copy(barcode = code))
                    }
                },
                label = { Text(stringResource(R.string.field_barcode)) },
                placeholder = { Text(stringResource(R.string.field_barcode_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onResolveBarcode(draft.barcode) }),
                trailingIcon = {
                    IconButton(onClick = {
                        scanError = null
                        scope.launch {
                            when (val r = CodeScanner.scanProductBarcode(context)) {
                                is ScanResult.Scanned -> {
                                    onDraftChange(draft.copy(barcode = r.value.take(64)))
                                    onResolveBarcode(r.value)
                                }
                                is ScanResult.Failed -> scanError = r.message
                                ScanResult.Cancelled -> Unit
                            }
                        }
                    }) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.action_scan))
                    }
                },
                supportingText = {
                    val text = scanError ?: when (barcodeState) {
                        is BarcodeLookupState.Looking -> stringResource(R.string.barcode_looking)
                        is BarcodeLookupState.Registered -> stringResource(R.string.barcode_registered)
                        is BarcodeLookupState.Suggested -> stringResource(R.string.barcode_suggested, barcodeState.source)
                        is BarcodeLookupState.Unknown -> stringResource(R.string.barcode_unknown)
                        BarcodeLookupState.Idle -> stringResource(R.string.barcode_help)
                    }
                    Text(text)
                },
                isError = scanError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { if (it.length <= Limits.MAX_NAME_LENGTH) onDraftChange(draft.copy(name = it, autoFilled = false)) },
                    label = { Text(stringResource(R.string.field_item_name)) },
                    placeholder = { Text(stringResource(R.string.field_item_name_hint)) },
                    singleLine = true,
                    enabled = !registered,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                QuantityField(draft.quantity, { onDraftChange(draft.copy(quantity = it)) }, onDone = { if (canAdd) submit() })
            }
            Button(onClick = submit, enabled = canAdd, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_add))
            }
        }
    }
}

/** Choose where the list photo comes from (camera or gallery). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSourceSheet(onPicked: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) onPicked(Uri.parse(uri)) else onDismiss()
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri) else onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.photo_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.photo_explainer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(
                onClick = {
                    val uri = Sharing.newPhotoUri(context)
                    pendingCameraUri = uri.toString()
                    takePicture.launch(uri)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_take_photo))
            }
            OutlinedButton(
                onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Image, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_choose_photo))
            }
        }
    }
}

/**
 * PHP: render_json_import_section() — copy the prompt into any LLM with a
 * photo, save its reply as .json, pick that file here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JsonImportSheet(containerLabel: String, onFileText: (String) -> Unit, onError: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val prompt = remember(containerLabel) { ImportPrompt.text(containerLabel) }
    var copied by remember { mutableStateOf(false) }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = readSmallText(context, uri)
            if (text == null) onError() else onFileText(text)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.import_steps, containerLabel), style = MaterialTheme.typography.bodyMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(12.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("thingsFinder prompt", prompt)))
                        copied = true
                    }
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (copied) R.string.action_copied else R.string.action_copy_prompt))
                }
                val shareTitle = stringResource(R.string.action_share_prompt)
                OutlinedButton(onClick = { Sharing.shareText(context, prompt, shareTitle) }) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_share))
                }
            }
            Text(
                stringResource(R.string.import_format, Limits.IMPORT_MAX_ITEMS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.TextSnippet, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_choose_json))
            }
        }
    }
}

/**
 * PHP: the "Review items from photo" list — edit or remove lines, then add
 * whatever's left. JSON imports go through the same review here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(
    review: ReviewState,
    onUpdate: (Int, ItemDraft) -> Unit,
    onRemove: (Int) -> Unit,
    onAddAll: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editing by remember { mutableStateOf<Int?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(
                stringResource(if (review.source == ReviewState.Source.Photo) R.string.review_title_photo else R.string.review_title_json),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(stringResource(R.string.review_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (review.problems.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        stringResource(R.string.review_skipped, review.problems.size, review.problems.take(3).joinToString(" ")),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Column(
                Modifier.weight(1f, fill = false).heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            ) {
                review.lines.forEachIndexed { index, line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(line.name, modifier = Modifier.weight(1f).padding(vertical = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        QtyBadge(line.quantity)
                        IconButton(onClick = { editing = index }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.cd_edit_line, line.name))
                        }
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cd_remove_line, line.name), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onAddAll, enabled = review.lines.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text(pluralText(R.plurals.action_add_n_items, review.lines.size))
                }
                TextButton(onClick = { confirmDiscard = true }) { Text(stringResource(R.string.action_discard_all)) }
            }
        }
    }

    editing?.let { index ->
        val line = review.lines.getOrNull(index)
        if (line == null) {
            editing = null
        } else {
            ItemEditDialog(
                title = stringResource(R.string.review_edit_line),
                initialName = line.name,
                initialQuantity = line.quantity,
                onConfirm = { name, qty ->
                    onUpdate(index, ItemDraft(name.trim(), qty))
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.review_discard_title),
            text = stringResource(R.string.review_discard_text),
            confirmLabel = stringResource(R.string.action_discard_all),
            onConfirm = {
                confirmDiscard = false
                onDiscard()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
fun pluralText(id: Int, count: Int): String =
    LocalContext.current.resources.getQuantityString(id, count, count)

private suspend fun readSmallText(context: android.content.Context, uri: Uri): String? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCompat(2 * 1024 * 1024 + 1)
                if (bytes.size > 2 * 1024 * 1024) null else bytes.toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

/** InputStream.readNBytes is API 33+; this reads at most [limit] bytes on any API level. */
internal fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}
