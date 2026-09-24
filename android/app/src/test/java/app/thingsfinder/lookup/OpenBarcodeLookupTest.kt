package app.thingsfinder.lookup

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenBarcodeLookupTest {
    private lateinit var server: MockWebServer
    private var off: MockResponse = MockResponse().setResponseCode(404)
    private var upc: MockResponse = MockResponse().setResponseCode(404)

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().startsWith("/off/")) off else upc
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun lookup(client: OkHttpClient = OpenBarcodeLookup.defaultClient()) =
        OpenBarcodeLookup(client, server.url("/off/"), server.url("/upc/"))

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun `open food facts hit, brand is prefixed when missing from the name`() = runBlocking {
        off = json("""{"status":1,"product":{"product_name":"Tomato ketchup","brands":"Heinz, Kraft"}}""")
        val s = lookup().lookup("5000157024671")
        assertEquals(LookupSuggestion("Heinz Tomato ketchup", "Open Food Facts"), s)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/off/api/v2/product/5000157024671.json"))
    }

    @Test fun `falls back to UPCitemdb when open food facts has nothing`() = runBlocking {
        off = json("""{"status":0}""")
        upc = json("""{"code":"OK","items":[{"title":"  Bosch drill  "}]}""")
        assertEquals(LookupSuggestion("Bosch drill", "UPCitemdb"), lookup().lookup("123"))
    }

    @Test fun `server errors mean no suggestion`() = runBlocking {
        off = MockResponse().setResponseCode(500)
        upc = MockResponse().setResponseCode(429)
        assertNull(lookup().lookup("123"))
    }

    @Test fun `malformed json means no suggestion`() = runBlocking {
        off = json("{not json")
        upc = json("<html>")
        assertNull(lookup().lookup("123"))
    }

    @Test fun `timeouts mean no suggestion, never an exception`() = runBlocking {
        off = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        upc = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        val fast = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).callTimeout(500, TimeUnit.MILLISECONDS).build()
        assertNull(lookup(fast).lookup("123"))
    }

    @Test fun `blank barcode makes no request`() = runBlocking {
        assertNull(lookup().lookup("   "))
        assertEquals(0, server.requestCount)
    }
}
