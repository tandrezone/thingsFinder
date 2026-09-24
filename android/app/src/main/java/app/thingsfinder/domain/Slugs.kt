package app.thingsfinder.domain

import java.util.Locale

/**
 * Ports of the slug helpers in includes/db.php, so data created on the phone
 * keeps the exact same slugs the web app would have generated (and a backup
 * can round-trip to the server later).
 */
object Slugs {
    private val nonAlnum = Regex("[^a-z0-9]+")

    /** PHP: slugify() — lower-case, every run of non [a-z0-9] becomes "-", trimmed; "" becomes "x". */
    fun slugify(text: String): String {
        val lowered = text.trim().lowercase(Locale.ROOT)
        val slug = lowered.replace(nonAlnum, "-").trim('-')
        return slug.ifEmpty { "x" }
    }

    /**
     * PHP: unique_place_slug() / unique_box_slug() — "bin", then "bin-2",
     * "bin-3", … until [isTaken] says the candidate is free.
     */
    suspend fun unique(name: String, isTaken: suspend (String) -> Boolean): String {
        val base = slugify(name)
        var slug = base
        var n = 2
        while (isTaken(slug)) {
            slug = "$base-$n"
            n++
        }
        return slug
    }
}
