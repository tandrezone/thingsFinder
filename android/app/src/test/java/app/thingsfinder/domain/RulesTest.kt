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

    @Test fun `account rules match the server's register checks`() {
        assertEquals(true, AccountRules.validUsername("tiago.a-b_1"))
        assertEquals(true, AccountRules.validUsername(" tia "))
        assertEquals(false, AccountRules.validUsername("ti"))
        assertEquals(false, AccountRules.validUsername("a".repeat(41)))
        assertEquals(false, AccountRules.validUsername("tiago andre"))
        assertEquals(false, AccountRules.validPassword("1234567"))
        assertEquals(true, AccountRules.validPassword("12345678"))
    }
}
