package de.bookmarksplus.enrich.apps

import de.bookmarksplus.util.Urls

/**
 * Local heuristic for clean app names: splits page titles at separators ("FileZilla – The free FTP solution",
 * "Download 7-Zip | SourceForge.net") and takes the piece that matches the domain or repo.
 * What the heuristic does not recognize reliably is resolved by the AI ([AppNameAi]).
 */
object AppNames {

    /** Comparison key: letters/digits only, lowercase. */
    fun key(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private val SEPARATORS = Regex("""\s+[-–—|·•:»/]\s+|\s*[|·•»]\s*|:\s+""")
    private val LEADING = Regex(
        """^(?:download(?:s)?|get|install|official\s+(?:site|website|page)|home(?:page)?|welcome\s+to|the)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING = Regex(
        """\s+(?:download(?:s)?|for\s+(?:windows|mac(?:os)?|linux|android|ios|pc)(?:\s*(?:,|and|&)\s*\w+)*|free|""" +
            """official\s+(?:site|website)|home(?:page)?|on\s+(?:flathub|steam|github|sourceforge(?:\.net)?|google\s+play|the\s+app\s+store|linux|windows|mac(?:os)?|android)|""" +
            """v?\d+(?:\.\d+)+(?:\s*(?:beta|alpha|rc\d*))?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val SITE_NAMES = setOf(
        "github", "gitlab", "sourceforge", "sourceforgenet", "flathub", "alternativeto", "googleplay", "appstore", "steam",
        "chromewebstore", "firefoxaddons", "npm", "dockerhub", "codeberg", "softpedia", "chip", "heise", "computerbild",
        "home", "download", "downloads",
    )

    /** Repo name as a last resort: "awesome-app_cli" → "awesome app cli". */
    fun fromRepo(repo: String): String = repo.replace(Regex("""[-_]+"""), " ").trim()

    /**
     * Best candidate from a page title. [hints] are domain label, repo name or similar; if a piece matches, it wins.
     * Without a match to the hints only short titles yield a name – long, descriptive titles are taken over by the AI.
     */
    fun fromTitle(title: String, hints: List<String> = emptyList()): String? {
        val parts = title.split(SEPARATORS).map(::clean).filter { it.isNotEmpty() && key(it) !in SITE_NAMES }
        if (parts.isEmpty()) return null
        val hintKeys = hints.map(::key).filter { it.length >= 2 }
        for (h in hintKeys) parts.firstOrNull { key(it) == h }?.let { return it }
        // "KeePassXC Password Manager": leading words that exactly match the hint
        for (h in hintKeys) for (p in parts) {
            val words = p.split(' ')
            for (n in 1 until words.size) if (key(words.take(n).joinToString(" ")) == h) return words.take(n).joinToString(" ")
        }
        for (h in hintKeys) {
            parts.filter { p -> key(p).let { k -> k.startsWith(h) || (h.startsWith(k) && k.length >= 3) } }
                .minByOrNull { it.length }?.takeIf { it.split(' ').size <= 4 }?.let { return it }
        }
        return parts.first().takeIf { parts.size == 1 && it.split(' ').size <= 3 && it.length <= 40 && hintKeys.isEmpty() }
    }

    fun clean(s: String): String {
        var t = s.trim().trim('"', '\'', '«', '»', '“', '”')
        repeat(3) { t = TRAILING.replace(LEADING.replace(t, ""), "").trim() }
        return t.trim(' ', '-', '–', ',', '.')
    }

    /** Domain label as a hint ("filezilla-project.org" → "filezilla"). */
    fun domainHint(url: String): String? {
        val d = Urls.domain(url) ?: return null
        val labels = d.removePrefix("www.").split('.')
        val label = if (labels.size >= 2) labels[labels.size - 2] else labels.first()
        return label.substringBefore('-').takeIf { it.length >= 3 && it !in GENERIC_DOMAINS }
    }

    private val GENERIC_DOMAINS = setOf(
        "github", "gitlab", "sourceforge", "google", "apple", "microsoft", "flathub", "alternativeto", "npmjs", "docker",
        "codeberg", "steampowered", "itch", "chocolatey", "brew", "mozilla", "snapcraft",
    )

    /** Two names denote the same application (case, whitespace and special characters ignored). */
    fun same(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val ka = key(a)
        return ka.isNotEmpty() && ka == key(b)
    }

    /** Same website: subdomain or same name under a different ending (zulip.org ~ zulip.com ~ www.zulip.com). */
    fun sameSite(a: String, b: String): Boolean {
        val x = a.lowercase().removePrefix("www.")
        val y = b.lowercase().removePrefix("www.")
        if (x == y || x.endsWith(".$y") || y.endsWith(".$x")) return true
        fun label(d: String) = d.split('.').let { if (it.size >= 2) it[it.size - 2] else it.first() }
        return label(x).length >= 4 && label(x) == label(y) && label(x) !in GENERIC_DOMAINS
    }

    /** Same GitHub repo (case, ".git", path suffixes ignored). */
    fun sameRepo(a: String, b: String): Boolean {
        val ra = Urls.githubRepo(a) ?: return false
        val rb = Urls.githubRepo(b) ?: return false
        return ra.first.equals(rb.first, true) && ra.second.removeSuffix(".git").equals(rb.second.removeSuffix(".git"), true)
    }

    /** URL slug as at AlternativeTo: "Visual Studio Code" → "visual-studio-code", "Notepad++" → "notepad-plus-plus". */
    fun slug(name: String): String = name.lowercase()
        .replace("+", "-plus").replace("&", " and ").replace("#", "-sharp")
        .replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
}
