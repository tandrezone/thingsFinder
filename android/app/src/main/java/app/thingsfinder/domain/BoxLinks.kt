package app.thingsfinder.domain

import java.security.SecureRandom

/** What a scanned code or opened link asks the app to do with a box or place. */
enum class LinkAction(val segment: String?) {
    Open(null), Add("add"), Remove("remove");

    companion object {
        /** Navigation routes carry the action as its path segment ("add" / "remove"); anything else opens. */
        fun fromSegment(segment: String?): LinkAction = entries.firstOrNull { it.segment != null && it.segment == segment } ?: Open
    }
}

/** Whose share token a link carries. Web add/remove links don't say, so [Either] is looked up as a box, then a place. */
sealed interface LinkTarget {
    val token: String

    data class Box(override val token: String) : LinkTarget
    data class Place(override val token: String) : LinkTarget
    data class Either(override val token: String) : LinkTarget
}

/** A parsed thingsFinder link (see [BoxLinks.parse]). */
sealed interface AppLink {
    data class Container(val target: LinkTarget, val action: LinkAction = LinkAction.Open) : AppLink
    data class JoinGroup(val token: String) : AppLink
}

/**
 * Boxes and places are identified in QR codes and links by their random
 * share token (PHP: boxes.share_token / places.share_token,
 * new_share_token()). The phone's own codes use the app scheme:
 *
 *   thingsfinder://box/{token}            open the box
 *   thingsfinder://box/{token}/add        open it with the add-item sheet up
 *   thingsfinder://box/{token}/remove     open it in remove mode
 *   thingsfinder://place/{token}[/add|/remove]
 *   thingsfinder://join/{token}           group invite
 *
 * Codes printed by the web app use http(s)://host/view/{token} (a box),
 * /add/{token} and /remove/{token} (a box or a place) and /join/{token}
 * (an invite) — all understood here.
 */
object BoxLinks {
    const val SCHEME = "thingsfinder"
    const val HOST = "box"
    const val PLACE_HOST = "place"
    const val JOIN_HOST = "join"

    private val tokenPattern = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val webPattern = Regex("^https?://[^/]+(/.*)?$", RegexOption.IGNORE_CASE)
    private val random = SecureRandom()

    /** PHP: bin2hex(random_bytes(16)) — 128 random bits as 32 lowercase hex chars. */
    fun newShareToken(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isValidToken(token: String): Boolean = tokenPattern.matches(token)

    fun deepLink(token: String): String = "$SCHEME://$HOST/$token"

    fun boxLink(token: String, action: LinkAction): String = deepLink(token) + action.segment.orEmpty().let { if (it.isEmpty()) "" else "/$it" }

    fun placeLink(token: String, action: LinkAction): String =
        "$SCHEME://$PLACE_HOST/$token" + action.segment.orEmpty().let { if (it.isEmpty()) "" else "/$it" }

    fun joinLink(token: String): String = "$SCHEME://$JOIN_HOST/$token"

    /** Parses scanned QR content or an opened link; null if it isn't a thingsFinder link. */
    fun parse(scanned: String): AppLink? {
        val text = scanned.trim().substringBefore('#').substringBefore('?').trimEnd('/')
        val appPrefix = "$SCHEME://"
        return when {
            text.startsWith(appPrefix, ignoreCase = true) -> parseApp(text.substring(appPrefix.length).split('/'))
            webPattern.matches(text) -> parseWeb(text.substringAfter("://").split('/').drop(1))
            else -> null
        }
    }

    /** host/token[/action] */
    private fun parseApp(parts: List<String>): AppLink? {
        val host = parts.firstOrNull()?.lowercase() ?: return null
        val token = parts.getOrNull(1)?.takeIf(::isValidToken) ?: return null
        if (parts.size > 3) return null
        val action = when (parts.getOrNull(2)?.lowercase()) {
            null -> LinkAction.Open
            "add" -> LinkAction.Add
            "remove" -> LinkAction.Remove
            else -> return null
        }
        return when (host) {
            HOST -> AppLink.Container(LinkTarget.Box(token), action)
            PLACE_HOST -> AppLink.Container(LinkTarget.Place(token), action)
            JOIN_HOST -> if (action == LinkAction.Open) AppLink.JoinGroup(token) else null
            else -> null
        }
    }

    /** The last two path segments are {verb}/{token}; anything before them is the server's base path. */
    private fun parseWeb(path: List<String>): AppLink? {
        if (path.size < 2) return null
        val token = path.last().takeIf(::isValidToken) ?: return null
        return when (path[path.size - 2].lowercase()) {
            "view" -> AppLink.Container(LinkTarget.Box(token), LinkAction.Open)
            "add" -> AppLink.Container(LinkTarget.Either(token), LinkAction.Add)
            "remove" -> AppLink.Container(LinkTarget.Either(token), LinkAction.Remove)
            "join" -> AppLink.JoinGroup(token)
            else -> null
        }
    }

    /** A box token from a plain "open this box" link, or null for anything else. */
    fun tokenFrom(scanned: String): String? {
        val link = parse(scanned) as? AppLink.Container ?: return null
        return link.target.token.takeIf { link.target is LinkTarget.Box && link.action == LinkAction.Open }
    }
}

/**
 * A group's invite key: 8 uppercase letters/digits, shown as "K7F3-9QX2".
 * People may type it with or without the dash, in any case.
 */
object InviteKeys {
    private val keyPattern = Regex("^[A-Z0-9]{8}$")

    /** The key as the server wants it, or null if it can't be one. */
    fun normalize(raw: String): String? =
        raw.filter { !it.isWhitespace() && it != '-' }.uppercase().takeIf { keyPattern.matches(it) }

    fun format(key: String): String {
        val k = normalize(key) ?: return key
        return k.substring(0, 4) + "-" + k.substring(4)
    }
}
