package app.thingsfinder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxLinksTest {
    private val token = "0123456789abcdef0123456789abcdef"

    private fun box(action: LinkAction = LinkAction.Open) = AppLink.Container(LinkTarget.Box(token), action)
    private fun place(action: LinkAction) = AppLink.Container(LinkTarget.Place(token), action)
    private fun either(action: LinkAction) = AppLink.Container(LinkTarget.Either(token), action)

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
        // tokenFrom is only for plain "open this box" links.
        assertNull(BoxLinks.tokenFrom(BoxLinks.boxLink(token, LinkAction.Add)))
        assertNull(BoxLinks.tokenFrom("https://things.example/add/$token"))
    }

    @Test fun `app links for boxes and places, with add and remove`() {
        assertEquals(box(), BoxLinks.parse("thingsfinder://box/$token"))
        assertEquals(box(LinkAction.Add), BoxLinks.parse(BoxLinks.boxLink(token, LinkAction.Add)))
        assertEquals(box(LinkAction.Remove), BoxLinks.parse("THINGSFINDER://box/$token/remove/"))
        assertEquals(place(LinkAction.Add), BoxLinks.parse(BoxLinks.placeLink(token, LinkAction.Add)))
        assertEquals(place(LinkAction.Remove), BoxLinks.parse("thingsfinder://place/$token/remove"))
        assertEquals(place(LinkAction.Open), BoxLinks.parse("thingsfinder://place/$token"))
        assertEquals("thingsfinder://box/$token/add", BoxLinks.boxLink(token, LinkAction.Add))
        assertEquals("thingsfinder://place/$token/remove", BoxLinks.placeLink(token, LinkAction.Remove))
        assertEquals(BoxLinks.deepLink(token), BoxLinks.boxLink(token, LinkAction.Open))
    }

    @Test fun `web add and remove links may be a box or a place`() {
        assertEquals(either(LinkAction.Add), BoxLinks.parse("https://thingsfinder.xyz/add/$token"))
        assertEquals(either(LinkAction.Remove), BoxLinks.parse("https://my.host:8443/tf/remove/$token?x=1#top"))
        assertEquals(box(), BoxLinks.parse("https://my.host/tf/view/$token"))
    }

    @Test fun `group invites, app and web`() {
        assertEquals(AppLink.JoinGroup("inviteTok_123"), BoxLinks.parse("thingsfinder://join/inviteTok_123"))
        assertEquals(AppLink.JoinGroup("inviteTok_123"), BoxLinks.parse("https://thingsfinder.xyz/join/inviteTok_123"))
        assertEquals(AppLink.JoinGroup(token), BoxLinks.parse(BoxLinks.joinLink(token)))
        assertNull(BoxLinks.parse("thingsfinder://join/inviteTok_123/add"))
    }

    @Test fun `malformed links are rejected`() {
        assertNull(BoxLinks.parse("thingsfinder://box/$token/destroy"))
        assertNull(BoxLinks.parse("thingsfinder://box/$token/add/more"))
        assertNull(BoxLinks.parse("thingsfinder://shelf/$token"))
        assertNull(BoxLinks.parse("thingsfinder://place/short"))
        assertNull(BoxLinks.parse("https://example.com/$token"))
        assertNull(BoxLinks.parse("https://example.com/delete/$token"))
        assertNull(BoxLinks.parse("ftp://example.com/view/$token"))
        assertNull(BoxLinks.parse("view/$token"))
        assertNull(BoxLinks.parse(""))
    }

    @Test fun `route segments map back to actions`() {
        for (action in LinkAction.entries) assertEquals(action, LinkAction.fromSegment(action.segment))
        assertEquals(LinkAction.Open, LinkAction.fromSegment("nonsense"))
    }

    @Test fun `invite keys accept any case, with or without the dash`() {
        assertEquals("K7F39QX2", InviteKeys.normalize("k7f3-9qx2"))
        assertEquals("K7F39QX2", InviteKeys.normalize(" K7F3 9QX2 "))
        assertEquals("K7F3-9QX2", InviteKeys.format("k7f39qx2"))
        assertNull(InviteKeys.normalize("K7F3-9QX"))
        assertNull(InviteKeys.normalize("K7F3-9QX2!"))
    }
}
