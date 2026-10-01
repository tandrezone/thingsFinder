package app.thingsfinder.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thingsfinder.R
import app.thingsfinder.domain.AppLink
import app.thingsfinder.domain.BoxLinks
import app.thingsfinder.domain.InviteKeys
import app.thingsfinder.domain.cleanName
import app.thingsfinder.sync.ActiveGroup
import app.thingsfinder.sync.ApiResult
import app.thingsfinder.sync.CloudApi
import app.thingsfinder.sync.CloudSessionStore
import app.thingsfinder.sync.CloudState
import app.thingsfinder.sync.GroupMember
import app.thingsfinder.sync.JoinGroupRequest
import app.thingsfinder.sync.RemoteGroup
import app.thingsfinder.sync.SwitchOutcome
import app.thingsfinder.sync.SyncEngine
import app.thingsfinder.sync.SyncOutcome
import app.thingsfinder.ui.common.UiMessage
import app.thingsfinder.ui.common.UiState
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

/** GET /api/groups. [defaultGroupId] is the group the server syncs when the phone doesn't name one. */
data class GroupsData(val groups: List<RemoteGroup>, val defaultGroupId: Long?)

/** The selected group, freshly fetched with its members. */
data class GroupDetail(val group: RemoteGroup, val members: List<GroupMember>)

fun RemoteGroup.toActive() = ActiveGroup(id, name, permission)

/**
 * Settings → Groups: list, switch, create, join (QR / link / name + key),
 * invite, members, leave; rename / new invite / remove members / delete
 * for owners. Switching goes through [SyncEngine.switchGroup], which never
 * drops changes that haven't reached the server.
 */
class GroupsViewModel(
    private val session: CloudSessionStore,
    private val api: CloudApi,
    private val sync: SyncEngine,
) : ViewModel() {

    val cloud: StateFlow<CloudState> = session.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudState())

    private val _groups = MutableStateFlow<UiState<GroupsData>>(UiState.Loading)
    val groups: StateFlow<UiState<GroupsData>> = _groups.asStateFlow()

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    /** Null while nothing is selected. */
    private val _detail = MutableStateFlow<UiState<GroupDetail>?>(null)
    val detail: StateFlow<UiState<GroupDetail>?> = _detail.asStateFlow()

    /** A switch, join or owner action is running (switching can take a while: it syncs twice). */
    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    /** A group just created or joined — the screen asks whether to switch to it now. */
    private val _offerSwitch = MutableStateFlow<RemoteGroup?>(null)
    val offerSwitch: StateFlow<RemoteGroup?> = _offerSwitch.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    init {
        refresh()
    }

    private fun say(message: UiMessage) {
        _messages.trySend(message)
    }

    /** The group this phone shows: the stored one, or — before the first sync — the server's default. */
    fun activeId(): Long? = cloud.value.activeGroup?.id ?: (groups.value as? UiState.Content)?.data?.defaultGroupId

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (session.current().token == null) return
        when (val r = request(notFound = R.string.groups_error_no_api) { s, t -> api.groups(s, t) }) {
            is Reply.Ok -> {
                val data = GroupsData(r.value.groups, r.value.defaultGroupId)
                _groups.value = UiState.Content(data)
                // Keep the Settings label in step with renames / permission changes made elsewhere.
                val state = session.current()
                val active = state.activeGroup
                data.groups.firstOrNull { it.id == active?.id }?.let { if (it.toActive() != active) session.setActiveGroup(it.toActive()) }
                val keep = _selectedId.value?.takeIf { id -> data.groups.any { it.id == id } }
                select(keep ?: (active?.id ?: data.defaultGroupId)?.takeIf { id -> data.groups.any { it.id == id } } ?: data.groups.firstOrNull()?.id)
            }
            is Reply.Failed -> if (_groups.value is UiState.Content) say(r.message) else _groups.value = UiState.Error(r.message)
        }
    }

    fun select(id: Long?) {
        _selectedId.value = id
        if (id == null) {
            _detail.value = null
            return
        }
        if ((_detail.value as? UiState.Content)?.data?.group?.id != id) _detail.value = UiState.Loading
        viewModelScope.launch {
            val r = request(notFound = R.string.groups_error_gone) { s, t -> api.group(s, t, id) }
            if (_selectedId.value != id) return@launch
            _detail.value = when (r) {
                is Reply.Ok -> UiState.Content(GroupDetail(r.value.group, r.value.members))
                is Reply.Failed -> UiState.Error(r.message)
            }
        }
    }

    // ---- switching ----------------------------------------------------------

    fun switchTo(group: RemoteGroup) = work {
        _offerSwitch.value = null
        switch(group.toActive())
        load()
    }

    fun dismissSwitchOffer() {
        _offerSwitch.value = null
    }

    /** Returns whether the phone now holds [target] (null: the server's default group). */
    private suspend fun switch(target: ActiveGroup?): Boolean {
        return when (val outcome = sync.switchGroup(target)) {
            is SwitchOutcome.Switched -> {
                val name = session.current().activeGroup?.name ?: target?.name.orEmpty()
                say(if (outcome.pull is SyncOutcome.Synced) msg(R.string.groups_switched, name) else errorMsg(R.string.groups_switched_pull_failed, name))
                true
            }
            is SwitchOutcome.Refused -> {
                say(errorMsg(R.string.groups_switch_refused, outcome.message))
                false
            }
            SwitchOutcome.NotSignedIn -> {
                say(errorMsg(R.string.cloud_error_signed_out))
                false
            }
        }
    }

    /**
     * Before leaving or deleting the group this phone shows: switch to another
     * one first (the default, else any), so unsent changes still reach the old
     * group. With no other group, the switch has to wait until the server has
     * dropped this one ([MoveOff.SwitchAfter]).
     */
    private suspend fun moveOffIfActive(groupId: Long): MoveOff {
        if (activeId() != groupId) return MoveOff.NotActive
        val data = (groups.value as? UiState.Content)?.data
        val others = data?.groups.orEmpty().filter { it.id != groupId }
        val target = others.firstOrNull { it.id == data?.defaultGroupId } ?: others.firstOrNull() ?: return MoveOff.SwitchAfter
        return if (switch(target.toActive())) MoveOff.Switched else MoveOff.Refused
    }

    private enum class MoveOff { NotActive, Switched, SwitchAfter, Refused }

    // ---- create / join ------------------------------------------------------

    fun create(rawName: String) = work {
        val name = cleanName(rawName)
        if (name.isEmpty()) return@work say(errorMsg(R.string.groups_error_name_empty))
        val r = request { s, t -> api.createGroup(s, t, name) }
        if (r is Reply.Ok) {
            say(msg(R.string.groups_created, r.value.name))
            _selectedId.value = r.value.id
            load()
            _offerSwitch.value = r.value
        } else if (r is Reply.Failed) {
            say(r.message)
        }
    }

    /** Scanned QR or pasted text: an https://…/join/{token} or thingsfinder://join/{token} link. */
    fun joinFromText(text: String) {
        val link = BoxLinks.parse(text)
        if (link is AppLink.JoinGroup) joinWithToken(link.token) else say(errorMsg(R.string.groups_error_not_invite))
    }

    fun joinWithToken(token: String) = join(JoinGroupRequest(token = token))

    fun joinWithKey(rawName: String, rawKey: String) {
        val name = rawName.trim()
        val key = InviteKeys.normalize(rawKey)
        when {
            name.isEmpty() -> say(errorMsg(R.string.groups_error_name_empty))
            key == null -> say(errorMsg(R.string.groups_error_key_format))
            else -> join(JoinGroupRequest(name = name, key = key))
        }
    }

    private fun join(request: JoinGroupRequest) = work {
        val r = request(notFound = R.string.groups_error_invite_not_found) { s, t -> api.joinGroup(s, t, request) }
        when (r) {
            is Reply.Ok -> {
                val group = r.value
                say(msg(R.string.groups_joined, group.name))
                _selectedId.value = group.id
                load()
                if (group.id != activeId()) _offerSwitch.value = group
            }
            is Reply.Failed -> say(r.message)
        }
    }

    // ---- owner actions, leave -----------------------------------------------

    fun rename(groupId: Long, rawName: String) = work {
        val name = cleanName(rawName)
        if (name.isEmpty()) return@work say(errorMsg(R.string.groups_error_name_empty))
        val r = request { s, t -> api.renameGroup(s, t, groupId, name) }
        if (r is Reply.Failed) return@work say(r.message)
        say(msg(R.string.groups_renamed))
        load()
    }

    fun resetInvite(groupId: Long) = work {
        val r = request { s, t -> api.resetInvite(s, t, groupId) }
        if (r is Reply.Failed) return@work say(r.message)
        say(msg(R.string.groups_invite_reset))
        select(groupId)
    }

    fun removeMember(groupId: Long, member: GroupMember) = work {
        val r = request { s, t -> api.removeMember(s, t, groupId, member.id) }
        if (r is Reply.Failed) return@work say(r.message)
        say(msg(R.string.groups_member_removed, member.username))
        load()
    }

    fun leave(group: RemoteGroup) = leaveOrDelete(group) { s, t -> api.leaveGroup(s, t, group.id) }

    fun delete(group: RemoteGroup) = leaveOrDelete(group) { s, t -> api.deleteGroup(s, t, group.id) }

    private fun leaveOrDelete(group: RemoteGroup, call: suspend (String, String) -> ApiResult<Unit>) = work {
        val moved = moveOffIfActive(group.id)
        if (moved == MoveOff.Refused) return@work
        val r = request(block = call)
        if (r is Reply.Failed) {
            say(r.message)
        } else {
            say(msg(if (group.isOwner) R.string.groups_deleted else R.string.groups_left, group.name))
            // The only group is gone: fetch whatever the server now picks as the default.
            if (moved == MoveOff.SwitchAfter) switch(null)
        }
        _selectedId.value = null
        load()
    }

    // ---- plumbing -----------------------------------------------------------

    private fun work(block: suspend () -> Unit) {
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(errorMsg(R.string.error_generic))
            } finally {
                _working.value = false
            }
        }
    }

    private sealed interface Reply<out T> {
        data class Ok<T>(val value: T) : Reply<T>
        data class Failed(val message: UiMessage) : Reply<Nothing>
    }

    /**
     * Runs [block] with the signed-in server and token. Failures become a
     * message; a 401 also signs the phone out, like a sync would. [notFound]
     * replaces the server's text for a 404.
     */
    private suspend fun <T> request(notFound: Int? = null, block: suspend (server: String, token: String) -> ApiResult<T>): Reply<T> {
        val state = session.current()
        val token = state.token ?: return Reply.Failed(errorMsg(R.string.groups_sign_in_first))
        return when (val r = block(state.serverUrl, token)) {
            is ApiResult.Success -> Reply.Ok(r.value)
            is ApiResult.Unauthorized -> {
                session.signedOut(error = "Signed out by the server — sign in again to keep syncing.")
                Reply.Failed(errorMsg(R.string.cloud_error_signed_out))
            }
            is ApiResult.NetworkError -> Reply.Failed(errorMsg(R.string.cloud_error_unreachable, state.serverUrl.substringAfter("://").substringBefore('/')))
            is ApiResult.HttpError -> Reply.Failed(
                when {
                    r.code == 404 && notFound != null -> errorMsg(notFound)
                    r.code in 400..499 -> errorMsg(R.string.cloud_error_server_says, r.message)
                    else -> errorMsg(R.string.cloud_error_http, r.code, r.message)
                },
            )
            is ApiResult.BadResponse -> Reply.Failed(errorMsg(R.string.groups_error_no_api))
        }
    }
}
