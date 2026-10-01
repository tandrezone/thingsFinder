package app.thingsfinder.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSession(initial: CloudState = CloudState(serverUrl = "https://example.test", username = "tiago", token = "tok")) : CloudSessionStore {
    val flow = MutableStateFlow(initial)
    override val state: Flow<CloudState> = flow
    override suspend fun setEnabled(enabled: Boolean) { flow.value = flow.value.copy(enabled = enabled) }
    override suspend fun signedIn(serverUrl: String, username: String, token: String, group: ActiveGroup?) {
        flow.value = flow.value.copy(serverUrl = serverUrl, username = username, token = token, cursor = 0, lastPushAt = 0, lastError = null, activeGroup = group)
    }
    override suspend fun signedOut(error: String?) {
        flow.value = flow.value.copy(token = null, cursor = 0, lastPushAt = 0, lastError = error, activeGroup = null)
    }
    override suspend fun setActiveGroup(group: ActiveGroup?) { flow.value = flow.value.copy(activeGroup = group) }
    override suspend fun switchedGroup(group: ActiveGroup?) {
        flow.value = flow.value.copy(activeGroup = group, cursor = 0, lastPushAt = 0, lastError = null)
    }
    override suspend fun synced(cursor: Long, lastPushAt: Long, at: Long) {
        flow.value = flow.value.copy(cursor = cursor, lastPushAt = lastPushAt, lastSyncAt = at, lastError = null)
    }
    override suspend fun failed(error: String) { flow.value = flow.value.copy(lastError = error) }
    override suspend fun resetCursors() { flow.value = flow.value.copy(cursor = 0, lastPushAt = 0) }
}

/**
 * Records every sync request and answers with whatever [next] says. Groups
 * live in [groups] (all of which the caller is in); [defaultGroupId] is what
 * GET /api/groups reports.
 */
class FakeCloudApi(var next: (SyncRequest) -> ApiResult<SyncResponse> = { ApiResult.Success(SyncResponse(serverTime = 1000)) }) : CloudApi {
    val requests = mutableListOf<SyncRequest>()
    val groups = mutableListOf(RemoteGroup(1, "tiago", RemoteGroup.OWNER, invite = GroupInvite("https://example.test/join/tok1tok1tok1", "tok1tok1tok1", "K7F39QX2")))
    var defaultGroupId = 1L
    /** Groups a join request can find (by token or name + key) and add to [groups]. */
    val joinable = mutableListOf<RemoteGroup>()
    val calls = mutableListOf<String>()

    override suspend fun login(baseUrl: String, username: String, password: String, deviceName: String): ApiResult<LoginResponse> =
        if (password == "right") ApiResult.Success(LoginResponse("tok", RemoteUser(1, username))) else ApiResult.Unauthorized("Wrong username or password")
    override suspend fun register(baseUrl: String, username: String, password: String, deviceName: String): ApiResult<RegisterResponse> =
        if (username == "taken") ApiResult.HttpError(409, "Username taken") else ApiResult.Success(RegisterResponse("tok", RemoteUser(2, username), groups.first()))
    override suspend fun logout(baseUrl: String, token: String): ApiResult<Unit> = ApiResult.Success(Unit)
    override suspend fun sync(baseUrl: String, token: String, request: SyncRequest): ApiResult<SyncResponse> {
        requests += request
        return next(request)
    }

    override suspend fun groups(baseUrl: String, token: String): ApiResult<GroupsResponse> =
        ApiResult.Success(GroupsResponse(groups.toList(), defaultGroupId))
    override suspend fun group(baseUrl: String, token: String, groupId: Long): ApiResult<GroupDetailResponse> =
        groups.firstOrNull { it.id == groupId }?.let { ApiResult.Success(GroupDetailResponse(it, listOf(GroupMember(1, "tiago", it.role)))) }
            ?: ApiResult.HttpError(404, "Not found")
    override suspend fun createGroup(baseUrl: String, token: String, name: String): ApiResult<RemoteGroup> {
        val g = RemoteGroup((groups.maxOfOrNull { it.id } ?: 0) + 1, name, RemoteGroup.OWNER)
        groups += g
        return ApiResult.Success(g)
    }
    override suspend fun renameGroup(baseUrl: String, token: String, groupId: Long, name: String): ApiResult<RemoteGroup> {
        val i = groups.indexOfFirst { it.id == groupId }.takeIf { it >= 0 } ?: return ApiResult.HttpError(404, "Not found")
        groups[i] = groups[i].copy(name = name)
        return ApiResult.Success(groups[i])
    }
    override suspend fun deleteGroup(baseUrl: String, token: String, groupId: Long): ApiResult<Unit> {
        calls += "delete $groupId"
        return if (groups.removeAll { it.id == groupId }) ApiResult.Success(Unit) else ApiResult.HttpError(404, "Not found")
    }
    override suspend fun resetInvite(baseUrl: String, token: String, groupId: Long): ApiResult<RemoteGroup> =
        groups.firstOrNull { it.id == groupId }?.let { ApiResult.Success(it) } ?: ApiResult.HttpError(404, "Not found")
    override suspend fun leaveGroup(baseUrl: String, token: String, groupId: Long): ApiResult<Unit> {
        calls += "leave $groupId"
        return if (groups.removeAll { it.id == groupId }) ApiResult.Success(Unit) else ApiResult.HttpError(404, "Not found")
    }
    override suspend fun removeMember(baseUrl: String, token: String, groupId: Long, userId: Long): ApiResult<Unit> = ApiResult.Success(Unit)
    override suspend fun joinGroup(baseUrl: String, token: String, request: JoinGroupRequest): ApiResult<RemoteGroup> {
        val g = joinable.firstOrNull { (request.token != null && it.invite?.token == request.token) || (it.name == request.name && it.invite?.key == request.key) }
            ?: return ApiResult.HttpError(404, "Invite not found")
        if (groups.none { it.id == g.id }) groups += g
        return ApiResult.Success(g)
    }
}
