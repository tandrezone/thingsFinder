package app.thingsfinder.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RulesTest {
    @Test fun `quantity text parses like PHP (int) with a floor of 1`() {
        assertEquals(1, parseQuantity(""))
        assertEquals(1, parseQuantity("0"))
        assertEquals(12, parseQuantity("12"))
        assertEquals(12, parseQuantity("12abc"))
        assertEquals(Limits.MAX_QUANTITY, parseQuantity("999999999999999"))
    }

    @Test fun `names are trimmed and capped without splitting surrogate pairs`() {
        assertEquals("Glue", cleanName("  Glue \n"))
        val emoji = "📦".repeat(210)
        val cleaned = cleanName(emoji)
        assertEquals(200, cleaned.codePointCount(0, cleaned.length))
    }
}
