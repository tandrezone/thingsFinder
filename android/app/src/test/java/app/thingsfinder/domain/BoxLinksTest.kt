package app.thingsfinder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxLinksTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test fun `new tokens are 32 hex chars and differ`() {
        val a = BoxLinks.newShareToken()
        val b = BoxLinks.newShareToken()
        assertTrue(a.matches(Regex("^[0-9a-f]{32}$")))
        assertTrue(a != b)
    }

    @Test fun `understands app links and web-app view links`() {
        assertEquals(token, BoxLinks.tokenFrom(BoxLinks.deepLink(token)))
        assertEquals(token, BoxLinks.tokenFrom("http://192.168.1.50:8000/view/$token"))
        assertEquals(token, BoxLinks.tokenFrom("https://things.example/view/$token/?utm=x"))
    }

    @Test fun `rejects anything else`() {
        assertNull(BoxLinks.tokenFrom("https://example.com/place/garage"))
        assertNull(BoxLinks.tokenFrom("thingsfinder://box/"))
        assertNull(BoxLinks.tokenFrom("thingsfinder://box/../../etc"))
        assertNull(BoxLinks.tokenFrom("5601234567890"))
    }
}
