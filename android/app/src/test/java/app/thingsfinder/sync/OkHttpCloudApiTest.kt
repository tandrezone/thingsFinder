package app.thingsfinder.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The wire format here mirrors includes/sync.php; tests/sync_smoke.sh checks the PHP side. */
class OkHttpCloudApiTest {
    private lateinit var server: MockWebServer
    private val api = OkHttpCloudApi()
    private val base get() = server.url("/").toString().trimEnd('/')

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun `login posts credentials and parses the token`() = runBlocking {
        server.enqueue(json(201, """{"token":"abc","user":{"id":1,"username":"tiago"}}"""))
        val r = api.login(base, "tiago", "pw", "Pixel 8")
        assertEquals(ApiResult.Success(LoginResponse("abc", RemoteUser(1, "tiago"))), r)
        val req = server.takeRequest()
        assertEquals("/api/auth/login", req.path)
        assertTrue(req.body.readUtf8().contains("\"device_name\":\"Pixel 8\""))
    }

    @Test fun `wrong password is Unauthorized with the server's message`() = runBlocking {
        server.enqueue(json(401, """{"error":"Wrong username or password"}"""))
        assertEquals(ApiResult.Unauthorized("Wrong username or password"), api.login(base, "t", "x", "d"))
    }

    @Test fun `sync sends the bearer token and snake_case fields`() = runBlocking {
        server.enqueue(json(200, """{"server_time":42,"changes":{"places":[{"uuid":"u1","name":"Garage","updated_at":7}]},"skipped":[],"extra":"ignored"}"""))
        val r = api.sync(base, "tok", SyncRequest(since = 5, items = listOf(SyncItem("i1", boxUuid = "b1", name = "Hammer", updatedAt = 9))))
        assertEquals(42L, (r as ApiResult.Success).value.serverTime)
        assertEquals("Garage", r.value.changes.places.single().name)
        val req = server.takeRequest()
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"box_uuid\":\"b1\"") && body.contains("\"updated_at\":9") && !body.contains("place_uuid"))
    }

    @Test fun `html error page is a BadResponse, 404 an HttpError, no connection a NetworkError`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>oops</html>"))
        assertTrue(api.sync(base, "t", SyncRequest(0)) is ApiResult.BadResponse)
        server.enqueue(json(404, """{"error":"Not found"}"""))
        assertEquals(ApiResult.HttpError(404, "Not found"), api.sync(base, "t", SyncRequest(0)))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(api.sync(base, "t", SyncRequest(0)) is ApiResult.NetworkError)
    }

    @Test fun `server urls are normalised to https`() {
        assertEquals("https://thingsfinder.xyz", ServerUrl.normalize(""))
        assertEquals("https://thingsfinder.xyz", ServerUrl.normalize(" thingsfinder.xyz/ "))
        assertEquals("https://my.host:8443/tf", ServerUrl.normalize("https://my.host:8443/tf/"))
        assertEquals(null, ServerUrl.normalize("http://192.168.1.5"))
        assertEquals(null, ServerUrl.normalize("https://"))
    }
}
