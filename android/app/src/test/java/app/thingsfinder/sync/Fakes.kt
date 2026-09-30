package app.thingsfinder.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSession(initial: CloudState = CloudState(serverUrl = "https://example.test", username = "tiago", token = "tok")) : CloudSessionStore {
    val flow = MutableStateFlow(initial)
    override val state: Flow<CloudState> = flow
    override suspend fun setEnabled(enabled: Boolean) { flow.value = flow.value.copy(enabled = enabled) }
    override suspend fun signedIn(serverUrl: String, username: String, token: String) {
        flow.value = flow.value.copy(serverUrl = serverUrl, username = username, token = token, cursor = 0, lastPushAt = 0, lastError = null)
    }
    override suspend fun signedOut(error: String?) { flow.value = flow.value.copy(token = null, cursor = 0, lastPushAt = 0, lastError = error) }
    override suspend fun synced(cursor: Long, lastPushAt: Long, at: Long) {
        flow.value = flow.value.copy(cursor = cursor, lastPushAt = lastPushAt, lastSyncAt = at, lastError = null)
    }
    override suspend fun failed(error: String) { flow.value = flow.value.copy(lastError = error) }
    override suspend fun resetCursors() { flow.value = flow.value.copy(cursor = 0, lastPushAt = 0) }
}

/** Records every request and answers with whatever [next] says. */
class FakeCloudApi(var next: (SyncRequest) -> ApiResult<SyncResponse> = { ApiResult.Success(SyncResponse(serverTime = 1000)) }) : CloudApi {
    val requests = mutableListOf<SyncRequest>()
    override suspend fun login(baseUrl: String, username: String, password: String, deviceName: String): ApiResult<LoginResponse> =
        if (password == "right") ApiResult.Success(LoginResponse("tok", RemoteUser(1, username))) else ApiResult.Unauthorized("Wrong username or password")
    override suspend fun logout(baseUrl: String, token: String): ApiResult<Unit> = ApiResult.Success(Unit)
    override suspend fun sync(baseUrl: String, token: String, request: SyncRequest): ApiResult<SyncResponse> {
        requests += request
        return next(request)
    }
}
