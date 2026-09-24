package app.thingsfinder.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Port of the JSON item import in includes/helpers.php (import_parse_items)
 * and the pre-processing in index.php's import_json handler. Same contract:
 * a bare array of {name, quantity?} or {"items": [...]}, max 500 entries,
 * bad entries skipped with a note instead of failing the whole file.
 */
object ImportParser {

    data class Result(val items: List<ItemDraft>, val errors: List<String>)

    private val json = Json { isLenient = false }
    private val bom = "﻿"
    private val fences = Regex("^```(?:json)?\\s*|\\s*```$", RegexOption.IGNORE_CASE)

    /** Strips a BOM and ```json fences (LLMs add them even when told not to), then parses and validates. */
    fun parseText(raw: String): Result {
        var text = raw.trim().removePrefix(bom).trim()
        text = text.replace(fences, "")
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return Result(emptyList(), listOf("That file isn't valid JSON."))
        } catch (e: IllegalArgumentException) {
            return Result(emptyList(), listOf("That file isn't valid JSON."))
        }
        return parse(element)
    }

    /** PHP: import_parse_items(). */
    fun parse(decoded: JsonElement): Result {
        var root = decoded
        // PHP decodes {} to an empty array, which counts as an empty list of items.
        if (root is JsonObject && root.isEmpty()) root = JsonArray(emptyList())
        if (root is JsonObject) {
            val items = root["items"]
            if (items is JsonArray) root = items
        }
        if (root !is JsonArray) {
            return Result(emptyList(), listOf("Expected a JSON array of items, or an object with an \"items\" array."))
        }
        if (root.size > Limits.IMPORT_MAX_ITEMS) {
            return Result(
                emptyList(),
                listOf("${root.size} entries is more than the ${Limits.IMPORT_MAX_ITEMS}-item limit for a single import."),
            )
        }

        val items = ArrayList<ItemDraft>()
        val errors = ArrayList<String>()
        root.forEachIndexed { i, row ->
            val line = "Entry ${i + 1}"
            // PHP decodes {} to an empty list, which array_is_list() rejects — same outcome here.
            if (row !is JsonObject || row.isEmpty()) {
                errors += "$line: not an object with a \"name\"."
                return@forEachIndexed
            }
            val nameElement = row["name"]
            val name = if (nameElement is JsonPrimitive && nameElement.isString) nameElement.content.trim() else ""
            if (name.isEmpty()) {
                errors += "$line: missing a non-empty \"name\"."
                return@forEachIndexed
            }
            items += ItemDraft(name.truncateCodePoints(Limits.MAX_NAME_LENGTH), quantityOf(row["quantity"]))
        }
        return Result(items, errors)
    }

    /**
     * PHP: `$rawQty = $row['quantity'] ?? 1`, accepted only if int, digit-only
     * string, or float; then min(MAX, max(1, (int)$rawQty)).
     */
    private fun quantityOf(element: JsonElement?): Int {
        if (element == null || element is JsonNull || element !is JsonPrimitive) return 1
        val raw: Double = when {
            element.isString -> {
                val s = element.content
                if (s.isNotEmpty() && s.all { it in '0'..'9' }) s.toBigDecimal().toDouble() else return 1
            }
            element.booleanOrNull != null -> return 1
            element.longOrNull != null -> element.longOrNull!!.toDouble()
            element.doubleOrNull != null -> element.doubleOrNull!!
            else -> return 1
        }
        if (raw.isNaN()) return 1
        // (int) truncates toward zero; the clamp makes overflow behaviour irrelevant.
        val truncated = if (raw >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else if (raw <= Long.MIN_VALUE.toDouble()) Long.MIN_VALUE else raw.toLong()
        return clampQuantity(truncated)
    }
}
