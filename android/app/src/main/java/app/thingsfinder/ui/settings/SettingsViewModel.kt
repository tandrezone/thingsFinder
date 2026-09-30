package app.thingsfinder.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thingsfinder.R
import app.thingsfinder.data.Settings
import app.thingsfinder.data.backup.BackupDocument
import app.thingsfinder.data.backup.BackupException
import app.thingsfinder.data.backup.BackupRepository
import app.thingsfinder.data.db.DatabaseFiles
import app.thingsfinder.data.db.DatabaseImportException
import app.thingsfinder.data.db.DatabaseSummary
import app.thingsfinder.platform.FileIo
import app.thingsfinder.sync.ApiResult
import app.thingsfinder.sync.CloudApi
import app.thingsfinder.sync.CloudSessionStore
import app.thingsfinder.sync.CloudState
import app.thingsfinder.sync.ServerUrl
import app.thingsfinder.sync.SyncEngine
import app.thingsfinder.sync.SyncOutcome
import app.thingsfinder.ui.common.UiMessage
import app.thingsfinder.ui.common.errorMsg
import app.thingsfinder.ui.common.msg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** A parsed JSON backup waiting for the "replace everything?" confirmation. */
data class PendingRestore(val document: BackupDocument, val places: Int, val boxes: Int, val items: Int)

/** A copied-in .sqlite file that passed validation, waiting for confirmation. */
data class PendingDbImport(val file: File, val summary: DatabaseSummary)

/** Result of the sign-in dialog, so it can show the error inline and stay open. */
sealed interface SignInResult {
    data object Success : SignInResult
    data class Error(val message: UiMessage) : SignInResult
}

class SettingsViewModel(
    private val settings: Settings,
    private val backup: BackupRepository,
    private val files: FileIo,
    private val session: CloudSessionStore,
    private val api: CloudApi,
    private val sync: SyncEngine,
    private val databaseFiles: DatabaseFiles,
) : ViewModel() {

    val externalLookup: StateFlow<Boolean> = settings.externalLookupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val cloud: StateFlow<CloudState> = session.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudState())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    private val _pendingDbImport = MutableStateFlow<PendingDbImport?>(null)
    val pendingDbImport: StateFlow<PendingDbImport?> = _pendingDbImport.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    fun setExternalLookup(enabled: Boolean) {
        viewModelScope.launch { settings.setExternalLookupEnabled(enabled) }
    }

    // ---- cloud sync -------------------------------------------------------

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            session.setEnabled(enabled)
            if (enabled && session.current().signedIn) runSync(announce = false)
        }
    }

    fun signIn(rawServer: String, username: String, password: String, deviceName: String, onResult: (SignInResult) -> Unit) {
        val server = ServerUrl.normalize(rawServer)
        if (server == null) {
            onResult(SignInResult.Error(errorMsg(R.string.cloud_error_server_url)))
            return
        }
        if (username.isBlank() || password.isEmpty()) {
            onResult(SignInResult.Error(errorMsg(R.string.cloud_error_credentials_missing)))
            return
        }
        viewModelScope.launch {
            _syncing.value = true
            val result = try {
                api.login(server, username.trim(), password, deviceName)
            } finally {
                _syncing.value = false
            }
            when (result) {
                is ApiResult.Success -> {
                    session.signedIn(server, result.value.user.username, result.value.token)
                    onResult(SignInResult.Success)
                    _messages.send(msg(R.string.cloud_signed_in, result.value.user.username))
                    runSync(announce = true)
                }
                is ApiResult.Unauthorized -> onResult(SignInResult.Error(errorMsg(R.string.cloud_error_wrong_password)))
                is ApiResult.NetworkError -> onResult(SignInResult.Error(errorMsg(R.string.cloud_error_unreachable, hostOf(server))))
                is ApiResult.HttpError -> onResult(
                    SignInResult.Error(
                        if (result.code == 404) errorMsg(R.string.cloud_error_no_sync_api)
                        else errorMsg(R.string.cloud_error_http, result.code, result.message),
                    ),
                )
                is ApiResult.BadResponse -> onResult(SignInResult.Error(errorMsg(R.string.cloud_error_no_sync_api)))
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val state = session.current()
            state.token?.let { token -> runCatching { api.logout(state.serverUrl, token) } }
            session.signedOut()
            _messages.send(msg(R.string.cloud_signed_out))
        }
    }

    fun syncNow() {
        viewModelScope.launch { runSync(announce = true) }
    }

    private suspend fun runSync(announce: Boolean) {
        if (_syncing.value) return
        _syncing.value = true
        val outcome = try {
            sync.syncNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncOutcome.Failed(e.message ?: "Sync failed", retryable = true)
        } finally {
            _syncing.value = false
        }
        when (outcome) {
            is SyncOutcome.Synced -> if (announce) _messages.send(msg(R.string.cloud_synced, outcome.pushed, outcome.pulled))
            SyncOutcome.SignedOut -> _messages.send(errorMsg(R.string.cloud_error_signed_out))
            is SyncOutcome.Failed -> _messages.send(errorMsg(R.string.cloud_error_sync_failed, outcome.message))
            SyncOutcome.Disabled, SyncOutcome.NotSignedIn -> Unit
        }
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')

    // ---- SQLite database file ---------------------------------------------

    fun exportDatabase(uri: Uri) = work {
        databaseFiles.export(uri)
        _messages.send(msg(R.string.msg_db_exported))
    }

    fun chooseDatabaseFile(uri: Uri) = work {
        try {
            val (file, summary) = databaseFiles.stage(uri)
            _pendingDbImport.value = PendingDbImport(file, summary)
        } catch (e: DatabaseImportException) {
            _messages.send(errorMsg(R.string.msg_db_import_invalid, e.message.orEmpty()))
        }
    }

    fun confirmDatabaseImport() {
        val pending = _pendingDbImport.value ?: return
        _pendingDbImport.value = null
        work {
            // Everything in the new file is "new" to the cloud: send it all, fetch everything.
            session.resetCursors()
            databaseFiles.replaceAndRestart(pending.file) // the process restarts here
        }
    }

    fun cancelDatabaseImport() {
        _pendingDbImport.value?.let { databaseFiles.discard(it.file) }
        _pendingDbImport.value = null
    }

    // ---- JSON backup ------------------------------------------------------

    fun export(uri: Uri) = work {
        files.writeText(uri, backup.exportJson())
        _messages.send(msg(R.string.msg_backup_exported))
    }

    fun chooseRestoreFile(uri: Uri) = work {
        val doc = try {
            backup.parse(files.readText(uri))
        } catch (e: BackupException) {
            _messages.send(errorMsg(R.string.msg_backup_invalid))
            return@work
        }
        _pendingRestore.value = PendingRestore(
            document = doc,
            places = doc.places.size,
            boxes = doc.places.sumOf { it.boxes.size },
            items = doc.places.sumOf { p -> p.items.size + p.boxes.sumOf { it.items.size } },
        )
    }

    fun confirmRestore() {
        val pending = _pendingRestore.value ?: return
        _pendingRestore.value = null
        work {
            val summary = backup.restore(pending.document)
            session.resetCursors()
            _messages.send(msg(R.string.msg_backup_restored, summary.places, summary.boxes, summary.items))
            if (session.current().let { it.enabled && it.signedIn }) runSync(announce = false)
        }
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(errorMsg(R.string.msg_backup_failed))
            } finally {
                _busy.value = false
            }
        }
    }
}
