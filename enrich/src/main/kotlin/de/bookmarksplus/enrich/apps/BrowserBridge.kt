package de.bookmarksplus.enrich.apps

/**
 * Loads pages via the user's real browser (companion extension). Its cookies and
 * passed bot checks apply there – helpful for pages like AlternativeTo that block program fetches.
 * The extension picks up jobs via the local interface ([de.bookmarksplus.desktop.api.LocalApi]).
 */
interface BrowserBridge {
    /** An extension has reported in recently. */
    val available: Boolean

    /** HTML of the page or null (timeout, error, bot check not passed). */
    suspend fun fetchHtml(url: String, timeoutMs: Long = 60_000): BridgeResult?
}

data class BridgeResult(val status: Int, val finalUrl: String, val html: String?)

/** Detects bot check pages (Cloudflare & co.) by title or typical elements. */
object Challenge {
    private val TITLES = listOf(
        "just a moment", "einen moment", "attention required", "verifying you are human", "are you a robot",
        "ddos protection", "security check", "un instant", "un momento", "access denied",
    )

    fun isChallenge(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val head = html.take(6000).lowercase()
        val title = Regex("""<title[^>]*>([^<]*)</title>""").find(head)?.groupValues?.get(1)?.trim().orEmpty()
        return TITLES.any { title.contains(it) } || "challenges.cloudflare.com" in head && "cf-chl" in head ||
            "id=\"challenge-form\"" in head || "id=\"cf-challenge-running\"" in head
    }
}
