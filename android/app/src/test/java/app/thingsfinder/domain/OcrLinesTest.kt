package app.thingsfinder.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrLinesTest {
    @Test fun `cleans, filters and dedupes lines`() {
        val text = "Hammer\r\n  screw   drivers \n\n-\nx\n--- \nHAMMER\nÉclair box\r9V battery"
        assertEquals(
            listOf("Hammer", "screw drivers", "Éclair box", "9V battery"),
            OcrLines.parse(text).map { it.name },
        )
    }

    @Test fun `merge skips names already under review`() {
        val (merged, added) = OcrLines.merge(listOf(ItemDraft("Tape")), listOf(ItemDraft("tape"), ItemDraft("Glue")))
        assertEquals(listOf("Tape", "Glue"), merged.map { it.name })
        assertEquals(1, added)
    }
}
