package app.thingsfinder.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SlugsTest {
    @Test fun `slugify matches the PHP rules`() {
        assertEquals("tools-bin-2", Slugs.slugify("  Tools Bin #2 "))
        assertEquals("garage", Slugs.slugify("Garage"))
        assertEquals("x", Slugs.slugify("!!!"))
        assertEquals("x", Slugs.slugify(""))
        // PHP only keeps [a-z0-9]: accented letters become separators, exactly like preg_replace('/[^a-z0-9]+/u').
        assertEquals("cozinha-s-o-t-o", Slugs.slugify("Cozinha São Tão"))
    }

    @Test fun `unique appends -2, -3 until free`() = runBlocking {
        val taken = setOf("bin", "bin-2")
        assertEquals("bin-3", Slugs.unique("Bin") { it in taken })
        assertEquals("shelf", Slugs.unique("Shelf") { it in taken })
    }
}
