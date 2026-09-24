package app.thingsfinder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.thingsfinder.R
import app.thingsfinder.data.MoveDestination
import app.thingsfinder.domain.ItemLocation
import app.thingsfinder.domain.Limits
import app.thingsfinder.domain.parseQuantity

data class MenuAction(val label: String, val icon: ImageVector, val destructive: Boolean = false, val onClick: () -> Unit)

/** PHP: `details.card-menu` (the "⋮" menu on every card). */
@Composable
fun OverflowMenu(actions: List<MenuAction>, contentDescription: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = contentDescription)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        actions.forEach { a ->
            val tint = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            DropdownMenuItem(
                text = { Text(a.label, color = tint) },
                leadingIcon = { Icon(a.icon, contentDescription = null, tint = tint) },
                onClick = {
                    open = false
                    a.onClick()
                },
            )
        }
    }
}

/** A single-line name prompt (add / rename place, box, barcode name). Text survives rotation. */
@Composable
fun NameDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    placeholder: String? = null,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val focus = remember { FocusRequester() }
    val valid = value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Inside the dialog's own composition, so the requester is attached when this runs.
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.text.length <= Limits.MAX_NAME_LENGTH) value = it },
                label = { Text(label) },
                placeholder = placeholder?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Name + quantity (PHP: the rename_item form). */
@Composable
fun ItemEditDialog(
    title: String,
    initialName: String,
    initialQuantity: Int,
    onConfirm: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var qty by rememberSaveable { mutableStateOf(initialQuantity.toString()) }
    val valid = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= Limits.MAX_NAME_LENGTH) name = it },
                    label = { Text(stringResource(R.string.field_item_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                QuantityField(qty, { qty = it }, onDone = { if (valid) onConfirm(name, parseQuantity(qty)) })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, parseQuantity(qty)) }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun QuantityField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) onValueChange(v) },
        label = { Text(stringResource(R.string.field_quantity)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.width(120.dp),
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** PHP: the move-item `<select>` with one `<optgroup>` per place: "(loose in Place)" plus each of its boxes. */
@Composable
fun MoveItemDialog(
    destinations: List<MoveDestination>,
    current: ItemLocation,
    onMove: (ItemLocation) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_item_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                destinations.forEachIndexed { index, place ->
                    item(key = "p${place.placeId}") {
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text(place.placeName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    item(key = "l${place.placeId}") {
                        val loc = ItemLocation.InPlace(place.placeId)
                        RadioRow(stringResource(R.string.move_loose_in, place.placeName), selected == loc) { selected = loc }
                    }
                    items(place.boxes, key = { "b${it.id}" }) { box ->
                        val loc = ItemLocation.InBox(box.id)
                        RadioRow(box.name, selected == loc) { selected = loc }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(selected) }, enabled = selected != current) { Text(stringResource(R.string.action_move)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** PHP: the move-box `<select>` of places. */
@Composable
fun MoveBoxDialog(
    destinations: List<MoveDestination>,
    currentPlaceId: Long,
    onMove: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(currentPlaceId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_box_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(destinations, key = { it.placeId }) { place ->
                    RadioRow(place.placeName, selected == place.placeId) { selected = place.placeId }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(selected) }, enabled = selected != currentPlaceId) { Text(stringResource(R.string.action_move)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
