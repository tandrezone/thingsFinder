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

    @Test fun `register posts like login and returns the new personal group`() = runBlocking {
        server.enqueue(json(201, """{"token":"t2","user":{"id":9,"username":"ana"},"group":{"id":4,"name":"ana","role":"owner","permission":"edit","member_count":1,"invite":{"url":"https://h/join/abc","token":"abc","key":"K7F39QX2"}}}"""))
        val r = api.register(base, "ana", "longpassword", "Pixel")
        val value = (r as ApiResult.Success).value
        assertEquals("t2", value.token)
        assertEquals(4L, value.group!!.id)
        assertTrue(value.group!!.isOwner)
        assertEquals("K7F39QX2", value.group!!.invite!!.key)
        val req = server.takeRequest()
        assertEquals("/api/auth/register", req.path)
        assertEquals("POST", req.method)
        server.enqueue(json(409, """{"error":"Username already taken"}"""))
        assertEquals(ApiResult.HttpError(409, "Username already taken"), api.register(base, "ana", "longpassword", "Pixel"))
    }

    @Test fun `group calls use the right verbs and paths`() = runBlocking {
        server.enqueue(json(200, """{"groups":[{"id":1,"name":"tiago","role":"owner","permission":"edit","member_count":1,"invite":null},{"id":2,"name":"Club","role":"member","permission":"view","member_count":5}],"default_group_id":1}"""))
        val list = (api.groups(base, "tok") as ApiResult.Success).value
        assertEquals(1L, list.defaultGroupId)
        assertTrue(list.groups[1].viewOnly && list.groups[1].invite == null)
        server.takeRequest().let { assertEquals("GET" to "/api/groups", it.method to it.path) }

        server.enqueue(json(200, """{"deleted":true}"""))
        assertEquals(ApiResult.Success(Unit), api.deleteGroup(base, "tok", 2))
        server.takeRequest().let { assertEquals("DELETE" to "/api/groups/2", it.method to it.path) }

        server.enqueue(json(200, """{"group":{"id":2,"name":"Club 2"}}"""))
        assertEquals("Club 2", (api.renameGroup(base, "tok", 2, "Club 2") as ApiResult.Success).value.name)
        server.takeRequest().let {
            assertEquals("PUT" to "/api/groups/2", it.method to it.path)
            assertEquals("""{"name":"Club 2"}""", it.body.readUtf8())
        }

        server.enqueue(json(200, """{"removed":true}"""))
        api.removeMember(base, "tok", 2, 7)
        server.takeRequest().let { assertEquals("DELETE" to "/api/groups/2/members/7", it.method to it.path) }

        server.enqueue(json(200, """{"left":true}"""))
        api.leaveGroup(base, "tok", 2)
        server.takeRequest().let { assertEquals("POST" to "/api/groups/2/leave", it.method to it.path) }
    }

    @Test fun `join sends either the token or name and key, and 404 means no such invite`() = runBlocking {
        server.enqueue(json(200, """{"group":{"id":3,"name":"Family"}}"""))
        api.joinGroup(base, "tok", JoinGroupRequest(token = "abc"))
        assertEquals("""{"token":"abc"}""", server.takeRequest().body.readUtf8())
        server.enqueue(json(404, """{"error":"Invite not found"}"""))
        assertEquals(ApiResult.HttpError(404, "Invite not found"), api.joinGroup(base, "tok", JoinGroupRequest(name = "Family", key = "K7F39QX2")))
        assertEquals("""{"name":"Family","key":"K7F39QX2"}""", server.takeRequest().body.readUtf8())
    }

    @Test fun `sync sends group_id only when set, and reads the group back`() = runBlocking {
        server.enqueue(json(200, """{"server_time":1,"group":{"id":5,"name":"Family","permission":"view","role":"member"}}"""))
        val r = api.sync(base, "tok", SyncRequest(since = 0, places = listOf(SyncPlace("p1", "Garage", "0123456789abcdef0123456789abcdef", 1))))
        assertEquals(SyncGroup(5, "Family", "view", "member"), (r as ApiResult.Success).value.group)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("group_id") && body.contains("\"share_token\":\"0123456789abcdef0123456789abcdef\""))
        server.enqueue(json(200, """{"server_time":2}"""))
        api.sync(base, "tok", SyncRequest(since = 1, groupId = 5))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"group_id\":5"))
    }
}
