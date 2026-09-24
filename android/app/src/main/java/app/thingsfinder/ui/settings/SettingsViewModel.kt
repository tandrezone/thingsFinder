package app.thingsfinder.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thingsfinder.R
import app.thingsfinder.data.Settings
import app.thingsfinder.data.backup.BackupDocument
import app.thingsfinder.data.backup.BackupException
import app.thingsfinder.data.backup.BackupRepository
import app.thingsfinder.platform.FileIo
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

/** A parsed backup waiting for the "replace everything?" confirmation. */
data class PendingRestore(val document: BackupDocument, val places: Int, val boxes: Int, val items: Int)

class SettingsViewModel(
    private val settings: Settings,
    private val backup: BackupRepository,
    private val files: FileIo,
) : ViewModel() {

    val externalLookup: StateFlow<Boolean> = settings.externalLookupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    fun setExternalLookup(enabled: Boolean) {
        viewModelScope.launch { settings.setExternalLookupEnabled(enabled) }
    }

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
            _messages.send(msg(R.string.msg_backup_restored, summary.places, summary.boxes, summary.items))
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
