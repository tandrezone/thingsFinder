package app.thingsfinder.domain

/** Limits shared by the importer, the OCR review and the item forms (PHP: IMPORT_MAX_* in includes/helpers.php). */
object Limits {
    const val IMPORT_MAX_ITEMS = 500
    const val MAX_NAME_LENGTH = 200
    const val MAX_QUANTITY = 100_000
}

/** A box/place-level item candidate, before it's written to the database. */
data class ItemDraft(val name: String, val quantity: Int = 1)

/** Where an item lives. An item is always in exactly one of these (PHP: the items CHECK constraint). */
sealed interface ItemLocation {
    data class InBox(val boxId: Long) : ItemLocation
    data class InPlace(val placeId: Long) : ItemLocation
}

/** Clamp a quantity typed into a form the same way the PHP handlers do: max(1, (int)$qty), plus the import ceiling. */
fun clampQuantity(value: Long): Int = value.coerceIn(1L, Limits.MAX_QUANTITY.toLong()).toInt()

/** Parse a quantity text field; anything unparseable counts as 1 (PHP: (int)"" === 0 → max(1, 0) === 1). */
fun parseQuantity(text: String): Int {
    val digits = text.trim().takeWhile { it.isDigit() }
    return if (digits.isEmpty()) 1 else clampQuantity(digits.take(12).toLong())
}

/** Truncate to at most [max] Unicode code points (PHP: mb_substr), never splitting a surrogate pair. */
fun String.truncateCodePoints(max: Int): String {
    if (codePointCount(0, length) <= max) return this
    return substring(0, offsetByCodePoints(0, max))
}

/** Normalise a user-entered name: trimmed and capped at [Limits.MAX_NAME_LENGTH]. */
fun cleanName(raw: String): String = raw.trim().truncateCodePoints(Limits.MAX_NAME_LENGTH)

/** New-account rules, the same the server checks on POST /api/auth/register (so the form can say what's wrong first). */
object AccountRules {
    const val MIN_PASSWORD = 8
    private val usernamePattern = Regex("^[A-Za-z0-9._-]{3,40}$")

    fun validUsername(username: String): Boolean = usernamePattern.matches(username.trim())
    fun validPassword(password: String): Boolean = password.length >= MIN_PASSWORD
}
