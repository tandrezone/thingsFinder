package app.thingsfinder.ui.settings

import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.thingsfinder.BuildConfig
import app.thingsfinder.R
import app.thingsfinder.sync.CloudState
import app.thingsfinder.sync.ServerUrl
import app.thingsfinder.ui.common.UiMessage
import app.thingsfinder.ui.common.containerFactory
import app.thingsfinder.ui.common.text
import app.thingsfinder.ui.components.ConfirmDialog
import app.thingsfinder.ui.components.ContentWidth
import app.thingsfinder.ui.components.ScreenPreviews
import app.thingsfinder.ui.theme.ThingsFinderTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(
        factory = containerFactory {
            SettingsViewModel(it.settings, it.backup, it.files, it.cloudSession, it.cloudApi, it.syncEngine, it.databaseFiles)
        },
    ),
) {
    val lookup by vm.externalLookup.collectAsStateWithLifecycle()
    val cloud by vm.cloud.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pendingRestore.collectAsStateWithLifecycle()
    val pendingDb by vm.pendingDbImport.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var signInOpen by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }

    val stamp = { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) }
    val exportDb = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri ->
        if (uri != null) vm.exportDatabase(uri)
    }
    val importDb = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.chooseDatabaseFile(uri)
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val restoreJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.chooseRestoreFile(uri)
    }

    SettingsContent(
        cloud = cloud,
        syncing = syncing,
        lookupEnabled = lookup,
        busy = busy,
        snackbar = snackbar,
        onSyncEnabledChange = vm::setSyncEnabled,
        onSignIn = { signInOpen = true },
        onSignOut = { confirmSignOut = true },
        onSyncNow = vm::syncNow,
        onLookupChange = vm::setExternalLookup,
        onExportDatabase = { exportDb.launch("thingsfinder-${stamp()}.sqlite") },
        onImportDatabase = { importDb.launch(arrayOf("application/vnd.sqlite3", "application/x-sqlite3", "application/octet-stream", "*/*")) },
        onExportJson = { exportJson.launch("thingsfinder-backup-${stamp()}.json") },
        onRestoreJson = { restoreJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
    )

    if (signInOpen) {
        SignInDialog(
            initialServer = cloud.serverUrl,
            initialUsername = cloud.username.orEmpty(),
            working = syncing,
            onSignIn = { server, user, password, onError ->
                vm.signIn(server, user, password, deviceName()) { result ->
                    when (result) {
                        SignInResult.Success -> signInOpen = false
                        is SignInResult.Error -> onError(result.message)
                    }
                }
            },
            onDismiss = { signInOpen = false },
        )
    }
    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.cloud_sign_out_title),
            text = stringResource(R.string.cloud_sign_out_text),
            confirmLabel = stringResource(R.string.cloud_sign_out),
            destructive = false,
            onConfirm = {
                confirmSignOut = false
                vm.signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
    pending?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.restore_confirm_title),
            text = stringResource(R.string.restore_confirm_text, p.places, p.boxes, p.items),
            confirmLabel = stringResource(R.string.action_replace_everything),
            onConfirm = vm::confirmRestore,
            onDismiss = vm::cancelRestore,
        )
    }
    pendingDb?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.db_import_confirm_title),
            text = stringResource(R.string.db_import_confirm_text, p.summary.places, p.summary.boxes, p.summary.items),
            confirmLabel = stringResource(R.string.action_replace_and_restart),
            onConfirm = vm::confirmDatabaseImport,
            onDismiss = vm::cancelDatabaseImport,
        )
    }
}

private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
    .filter { !it.isNullOrBlank() }
    .joinToString(" ")
    .ifBlank { "Android" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    cloud: CloudState,
    syncing: Boolean,
    lookupEnabled: Boolean,
    busy: Boolean,
    snackbar: SnackbarHostState,
    onSyncEnabledChange: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSyncNow: () -> Unit,
    onLookupChange: (Boolean) -> Unit,
    onExportDatabase: () -> Unit,
    onImportDatabase: () -> Unit,
    onExportJson: () -> Unit,
    onRestoreJson: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ContentWidth(Modifier.padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CloudCard(cloud, syncing, onSyncEnabledChange, onSignIn, onSignOut, onSyncNow)

                SettingsCard {
                    SwitchRow(
                        title = stringResource(R.string.settings_lookup_title),
                        text = stringResource(R.string.settings_lookup_text),
                        checked = lookupEnabled,
                        onCheckedChange = onLookupChange,
                    )
                }

                SettingsCard {
                    Text(stringResource(R.string.settings_database_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_database_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onExportDatabase, enabled = !busy) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_export_database))
                    }
                    OutlinedButton(onClick = onImportDatabase, enabled = !busy) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_import_database))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(
                        stringResource(R.string.settings_backup_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onExportJson, enabled = !busy) {
                            Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_export_backup))
                        }
                        TextButton(onClick = onRestoreJson, enabled = !busy) {
                            Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_restore_backup))
                        }
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                SettingsCard {
                    Text(stringResource(R.string.settings_about_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudCard(
    cloud: CloudState,
    syncing: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val host = cloud.serverUrl.substringAfter("://").substringBefore('/')
    SettingsCard {
        SwitchRow(
            title = stringResource(R.string.cloud_sync_title),
            text = stringResource(R.string.cloud_sync_text, host),
            checked = cloud.enabled,
            onCheckedChange = onEnabledChange,
            icon = { Icon(Icons.Outlined.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        )
        if (!cloud.enabled) return@SettingsCard
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        if (!cloud.signedIn) {
            Text(
                cloud.lastError ?: stringResource(R.string.cloud_not_signed_in, host),
                style = MaterialTheme.typography.bodySmall,
                color = if (cloud.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onSignIn) {
                Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.cloud_sign_in))
            }
        } else {
            Text(
                stringResource(R.string.cloud_signed_in_as, cloud.username.orEmpty(), host),
                style = MaterialTheme.typography.bodyMedium,
            )
            val status = when {
                syncing -> stringResource(R.string.cloud_syncing)
                cloud.lastError != null -> cloud.lastError
                cloud.lastSyncAt > 0 -> stringResource(
                    R.string.cloud_last_synced,
                    DateUtils.getRelativeTimeSpanString(cloud.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                )
                else -> stringResource(R.string.cloud_never_synced)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (syncing) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cloud.lastError != null && !syncing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSyncNow, enabled = !syncing) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.cloud_sync_now))
                }
                TextButton(onClick = onSignOut, enabled = !syncing) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.cloud_sign_out))
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Server address (defaults to thingsfinder.xyz), username and password; errors stay inline so nothing typed is lost. */
@Composable
private fun SignInDialog(
    initialServer: String,
    initialUsername: String,
    working: Boolean,
    onSignIn: (server: String, username: String, password: String, onError: (UiMessage) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var server by rememberSaveable { mutableStateOf(initialServer.ifBlank { ServerUrl.DEFAULT }) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    // Deliberately not rememberSaveable: a password shouldn't be written into saved instance state.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiMessage?>(null) }
    val submit = {
        error = null
        onSignIn(server, username, password) { error = it }
    }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.cloud_sign_in_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.cloud_sign_in_text), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it.trim() },
                    label = { Text(stringResource(R.string.field_server)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.field_username)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.field_password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (showPassword) R.string.cd_hide_password else R.string.cd_show_password),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!working) submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = !working && username.isNotBlank() && password.isNotEmpty()) {
                Text(stringResource(R.string.cloud_sign_in))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

// ---- previews ------------------------------------------------------------

@ScreenPreviews
@Composable
private fun SettingsSignedInPreview() = ThingsFinderTheme {
    SettingsContent(
        cloud = CloudState(username = "tiago", token = "x", lastSyncAt = System.currentTimeMillis() - 5 * 60_000),
        syncing = false, lookupEnabled = true, busy = false, snackbar = remember { SnackbarHostState() },
        onSyncEnabledChange = {}, onSignIn = {}, onSignOut = {}, onSyncNow = {}, onLookupChange = {},
        onExportDatabase = {}, onImportDatabase = {}, onExportJson = {}, onRestoreJson = {},
    )
}

@ScreenPreviews
@Composable
private fun SettingsSignedOutPreview() = ThingsFinderTheme {
    SettingsContent(
        cloud = CloudState(), syncing = false, lookupEnabled = true, busy = false, snackbar = remember { SnackbarHostState() },
        onSyncEnabledChange = {}, onSignIn = {}, onSignOut = {}, onSyncNow = {}, onLookupChange = {},
        onExportDatabase = {}, onImportDatabase = {}, onExportJson = {}, onRestoreJson = {},
    )
}
