package app.thingsfinder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportParserTest {
    @Test fun `bare array with defaults`() {
        val r = ImportParser.parseText("""[{"name":"Hammer","quantity":1},{"name":"Zip ties"},{"name":"Duct tape","quantity":3}]""")
        assertEquals(listOf(ItemDraft("Hammer", 1), ItemDraft("Zip ties", 1), ItemDraft("Duct tape", 3)), r.items)
        assertTrue(r.errors.isEmpty())
    }

    @Test fun `object wrapper, code fences and BOM are accepted`() {
        val r = ImportParser.parseText("﻿```json\n{\"items\": [{\"name\": \"  Glue  \"}]}\n```")
        assertEquals(listOf(ItemDraft("Glue", 1)), r.items)
    }

    @Test fun `bad entries are skipped with a note`() {
        val r = ImportParser.parseText("""[{"name":"Ok"}, 5, {"name":""}, {}, {"quantity":2}, ["x"]]""")
        assertEquals(listOf(ItemDraft("Ok", 1)), r.items)
        assertEquals(
            listOf(
                "Entry 2: not an object with a \"name\".",
                "Entry 3: missing a non-empty \"name\".",
                "Entry 4: not an object with a \"name\".",
                "Entry 5: missing a non-empty \"name\".",
                "Entry 6: not an object with a \"name\".",
            ),
            r.errors,
        )
    }

    @Test fun `quantity coercion follows PHP`() {
        val r = ImportParser.parseText(
            """[{"name":"a","quantity":"7"},{"name":"b","quantity":2.9},{"name":"c","quantity":0},
               {"name":"d","quantity":-4},{"name":"e","quantity":999999999},{"name":"f","quantity":"7x"},
               {"name":"g","quantity":true},{"name":"h","quantity":null},{"name":"i","quantity":"99999999999999999999"}]""",
        )
        assertEquals(listOf(7, 2, 1, 1, 100_000, 1, 1, 1, 100_000), r.items.map { it.quantity })
    }

    @Test fun `long names are cut to 200 code points`() {
        val r = ImportParser.parseText("""[{"name":"${"é".repeat(250)}"}]""")
        assertEquals(200, r.items.single().name.codePointCount(0, r.items.single().name.length))
    }

    @Test fun `not json, wrong shape and too many`() {
        assertEquals(listOf("That file isn't valid JSON."), ImportParser.parseText("{nope").errors)
        assertEquals(
            listOf("Expected a JSON array of items, or an object with an \"items\" array."),
            ImportParser.parseText("""{"things": []}""").errors,
        )
        val many = (1..501).joinToString(",", "[", "]") { """{"name":"n$it"}""" }
        assertEquals(listOf("501 entries is more than the 500-item limit for a single import."), ImportParser.parseText(many).errors)
    }
}
