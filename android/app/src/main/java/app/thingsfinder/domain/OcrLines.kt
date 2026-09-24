package app.thingsfinder.domain

import java.util.Locale

/** Ports of the text-cleaning half of includes/ocr.php. The recognition itself runs on-device via ML Kit. */
object OcrLines {
    private val lineBreaks = Regex("\\r\\n|\\r|\\n")
    private val whitespace = Regex("\\s+")
    private val hasLetterOrDigit = Regex("[a-zA-Z0-9\\u00C0-\\u024F]")

    /** PHP: ocr_parse_lines() — one candidate per plausible line, de-duplicated case-insensitively, quantity 1. */
    fun parse(text: String): List<ItemDraft> {
        val seen = HashSet<String>()
        val out = ArrayList<ItemDraft>()
        for (raw in text.split(lineBreaks)) {
            val line = raw.replace(whitespace, " ").trim()
            if (line.isEmpty() || line.codePointCount(0, line.length) < 2) continue
            if (!hasLetterOrDigit.containsMatchIn(line)) continue
            val key = line.lowercase(Locale.ROOT)
            if (!seen.add(key)) continue
            out += ItemDraft(line.truncateCodePoints(Limits.MAX_NAME_LENGTH), 1)
        }
        return out
    }

    /**
     * PHP: the merge in handle_scan_action('scan_upload') — appends newly found
     * lines to an in-progress review list, skipping names already on it.
     * Returns the merged list and how many lines were actually added.
     */
    fun merge(existing: List<ItemDraft>, found: List<ItemDraft>): Pair<List<ItemDraft>, Int> {
        val seen = existing.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }
        val merged = existing.toMutableList()
        var added = 0
        for (item in found) {
            if (seen.add(item.name.lowercase(Locale.ROOT))) {
                merged += item
                added++
            }
        }
        return merged to added
    }
}
