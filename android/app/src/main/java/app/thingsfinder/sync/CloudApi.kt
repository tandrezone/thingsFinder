package app.thingsfinder.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Every call to the server ends in exactly one of these, so the UI can say something specific. */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    /** 401 — wrong password, or the token was revoked/expired. */
    data class Unauthorized(val message: String) : ApiResult<Nothing>
    data class HttpError(val code: Int, val message: String) : ApiResult<Nothing>
    /** No connection, DNS failure, timeout, TLS error… */
    data class NetworkError(val cause: IOException) : ApiResult<Nothing>
    /** The server answered 2xx but not with the JSON we expect (e.g. an HTML error page). */
    data class BadResponse(val message: String) : ApiResult<Nothing>
}

/** The thingsFinder server's app endpoints (see api.php / includes/sync.php). */
interface CloudApi {
    suspend fun login(baseUrl: String, username: String, password: String, deviceName: String): ApiResult<LoginResponse>
    suspend fun logout(baseUrl: String, token: String): ApiResult<Unit>
    suspend fun sync(baseUrl: String, token: String, request: SyncRequest): ApiResult<SyncResponse>
}

class OkHttpCloudApi(
    private val client: OkHttpClient = defaultClient(),
) : CloudApi {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false // absent optional fields (e.g. place_uuid on a boxed item) aren't sent as null
        encodeDefaults = true
    }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun login(baseUrl: String, username: String, password: String, deviceName: String) =
        post(baseUrl, "api/auth/login", null, LoginRequest(username, password, deviceName), LoginRequest.serializer(), LoginResponse.serializer())

    override suspend fun logout(baseUrl: String, token: String): ApiResult<Unit> =
        when (val r = post<Unit, Unit>(baseUrl, "api/auth/logout", token, Unit, null, null)) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Unauthorized -> ApiResult.Success(Unit) // already gone — that's what we wanted
            is ApiResult.HttpError -> r
            is ApiResult.NetworkError -> r
            is ApiResult.BadResponse -> ApiResult.Success(Unit)
        }

    override suspend fun sync(baseUrl: String, token: String, request: SyncRequest) =
        post(baseUrl, "api/sync", token, request, SyncRequest.serializer(), SyncResponse.serializer())

    private suspend fun <B, R> post(
        baseUrl: String,
        path: String,
        token: String?,
        body: B,
        bodySerializer: KSerializer<B>?,
        responseSerializer: KSerializer<R>?,
    ): ApiResult<R> = withContext(Dispatchers.IO) {
        val payload = if (bodySerializer != null) json.encodeToString(bodySerializer, body) else "{}"
        try {
            // Built inside the try: OkHttp throws IllegalArgumentException for URLs it can't parse.
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/" + path)
                .header("Accept", "application/json")
                .header("User-Agent", "thingsFinder-Android")
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .post(payload.toRequestBody(jsonType))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.code == 401 -> ApiResult.Unauthorized(errorText(text) ?: "Sign in again")
                    !response.isSuccessful -> ApiResult.HttpError(response.code, errorText(text) ?: "HTTP ${response.code}")
                    responseSerializer == null -> {
                        @Suppress("UNCHECKED_CAST")
                        ApiResult.Success(Unit as R)
                    }
                    else -> try {
                        ApiResult.Success(json.decodeFromString(responseSerializer, text))
                    } catch (e: Exception) {
                        ApiResult.BadResponse("The server's reply wasn't what thingsFinder expected — is the server up to date?")
                    }
                }
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        } catch (e: IllegalArgumentException) {
            ApiResult.BadResponse("That server address isn't valid.")
        }
    }

    private fun errorText(body: String): String? = try {
        json.decodeFromString(ErrorResponse.serializer(), body).error.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // a first sync of a big inventory can take a while
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
