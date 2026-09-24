package app.thingsfinder.domain

import java.security.SecureRandom

/**
 * A box's QR code / label identifies it by its random share token (PHP:
 * boxes.share_token, new_share_token()). On the phone the QR encodes
 * `thingsfinder://box/{token}`; labels printed by the web app encode
 * `http(s)://host/view/{token}` — both are understood here.
 */
object BoxLinks {
    const val SCHEME = "thingsfinder"
    const val HOST = "box"

    private val tokenPattern = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val random = SecureRandom()

    /** PHP: bin2hex(random_bytes(16)) — 128 random bits as 32 lowercase hex chars. */
    fun newShareToken(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun deepLink(token: String): String = "$SCHEME://$HOST/$token"

    /** Pulls a share token out of scanned QR content, or null if it isn't a thingsFinder box link. */
    fun tokenFrom(scanned: String): String? {
        val text = scanned.trim()
        val prefix = "$SCHEME://$HOST/"
        val candidate = when {
            text.startsWith(prefix, ignoreCase = true) -> text.substring(prefix.length)
            text.contains("/view/") -> text.substringAfter("/view/")
            else -> return null
        }.substringBefore('?').substringBefore('#').trimEnd('/')
        return candidate.takeIf { tokenPattern.matches(it) }
    }
}
