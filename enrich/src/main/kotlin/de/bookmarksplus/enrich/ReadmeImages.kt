package de.bookmarksplus.enrich

import java.net.URI

/**
 * Searches the first lines of a README for embedded images (Markdown, reference links, HTML `<img>`,
 * reStructuredText), in order of appearance. Badges, icons, SVGs and images with a small stated
 * height are skipped. Relative paths are resolved to raw.githubusercontent.com.
 */
object ReadmeImages {

    data class Candidate(val url: String, val declaredHeight: Int? = null, val declaredWidth: Int? = null)

    private val MD_INLINE = Regex("""!\[[^\]]*]\(\s*<?([^)\s>]+)>?(?:\s+["'(][^)]*)?\s*\)""")
    private val MD_REF_USE = Regex("""!\[([^\]]*)]\[([^\]]*)]""")
    private val MD_REF_DEF = Regex("""(?m)^\s{0,3}\[([^\]]+)]:\s*<?(\S+?)>?(?:\s+.*)?$""")
    private val HTML_IMG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HTML_SOURCE = Regex("""<source\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val RST_IMAGE = Regex("""(?m)^\s*\.\.\s+(?:image|figure)::\s*(\S+)((?:\n[ \t]+:[a-z-]+:.*)*)""")

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }

    private fun px(v: String?): Int? = v?.trim()?.takeIf { !it.endsWith("%") }?.removeSuffix("px")?.toIntOrNull()

    /** Hosts and paths that practically only deliver badges or status images. */
    private val BADGE = Regex(
        """shields\.io|badgen\.net|badge|/workflows/|travis-ci|circleci|codecov|coveralls|appveyor|gitter\.im|""" +
            """readthedocs\.org/projects/.+/badge|snyk\.io|sonarcloud|codacy|deepsource|ko-fi|buymeacoffee|liberapay|""" +
            """opencollective\.com/.+/(?:backers|sponsors)|star-history|api\.star-history|contrib\.rocks|""" +
            """github-readme-stats|komarev\.com|visitor-badge|hits\.seeyoufarm|forthebadge|img\.youtube|""" +
            """/sponsors/|repobeats|stars\.svg|/icons?/|favicon""",
        RegexOption.IGNORE_CASE,
    )

    fun candidates(readme: String, owner: String, repo: String, maxLines: Int): List<Candidate> {
        val head = readme.lineSequence().take(maxLines).joinToString("\n")
        val refs = MD_REF_DEF.findAll(readme).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val found = mutableListOf<Pair<Int, Candidate>>()

        MD_INLINE.findAll(head).forEach { found += it.range.first to Candidate(it.groupValues[1]) }
        MD_REF_USE.findAll(head).forEach { m ->
            val key = m.groupValues[2].ifEmpty { m.groupValues[1] }.lowercase()
            refs[key]?.let { found += m.range.first to Candidate(it) }
        }
        HTML_IMG.findAll(head).forEach { m ->
            val tag = m.value
            val src = attr(tag, "src") ?: attr(tag, "data-canonical-src") ?: return@forEach
            found += m.range.first to Candidate(src, px(attr(tag, "height")), px(attr(tag, "width")))
        }
        // <picture><source srcset="dark.png" media="(prefers-color-scheme: dark)"> – light or dark image
        HTML_SOURCE.findAll(head).forEach { m ->
            val set = attr(m.value, "srcset") ?: return@forEach
            found += m.range.first to Candidate(set.trim().substringBefore(' ').substringBefore(','))
        }
        RST_IMAGE.findAll(head).forEach { m ->
            val opts = m.groupValues[2]
            val h = Regex(""":height:\s*(\d+)""").find(opts)?.groupValues?.get(1)?.toIntOrNull()
            val w = Regex(""":width:\s*(\d+)""").find(opts)?.groupValues?.get(1)?.toIntOrNull()
            found += m.range.first to Candidate(m.groupValues[1], h, w)
        }

        return found.sortedBy { it.first }.map { it.second }
            .mapNotNull { c -> resolve(c.url, owner, repo)?.let { c.copy(url = it) } }
            .filter { c -> !BADGE.containsMatchIn(c.url) && !isSvg(c.url) }
            .distinctBy { it.url }
    }

    private fun isSvg(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".svg") || path.endsWith(".svgz") || "sanitize=true" in url.lowercase()
    }

    /** Absolute image URL; GitHub view links (`/blob/`) and relative paths become raw links. */
    fun resolve(raw: String, owner: String, repo: String): String? {
        val u = raw.trim().replace("&amp;", "&")
        if (u.isEmpty() || u.startsWith("data:", true) || u.startsWith("#")) return null
        val abs = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://", true) || u.startsWith("https://", true) -> u
            else -> {
                val path = u.removePrefix("./").removePrefix("/")
                if (path.isEmpty() || path.startsWith("..")) return null
                "https://raw.githubusercontent.com/$owner/$repo/HEAD/$path"
            }
        }
        // github.com/o/r/blob/branch/path → raw.githubusercontent.com/o/r/branch/path
        Regex("""^https?://github\.com/([^/]+)/([^/]+)/blob/(.+)$""", RegexOption.IGNORE_CASE).find(abs)?.let { m ->
            val (o, r, rest) = m.destructured
            return "https://raw.githubusercontent.com/$o/$r/${rest.substringBefore('?')}"
        }
        return runCatching { URI(abs.replace(" ", "%20")).toString() }.getOrNull()
    }
}
