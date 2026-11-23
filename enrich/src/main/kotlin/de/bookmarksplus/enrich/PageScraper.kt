package de.bookmarksplus.enrich

import de.bookmarksplus.util.Urls
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class PageMeta(
    val title: String?,
    val description: String?,
    val siteName: String?,
    /** og:image / twitter:image – the "share image" of the page. */
    val imageUrl: String?,
    /** Favicon candidates, best first. */
    val iconUrls: List<String>,
    /** GitHub repo that the page clearly points to (e.g. in the header of a project page). */
    val repoUrl: String?,
    val contentType: String?,
    /** Readable main text of the page (shortened), for AI summary and full-text search. */
    val text: String? = null,
)

object PageScraper {

    fun parse(html: String, baseUrl: String): PageMeta {
        val doc = Jsoup.parse(html, baseUrl)
        fun meta(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            doc.selectFirst("meta[property=$k], meta[name=$k]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        }

        val title = meta("og:title", "twitter:title") ?: doc.title().trim().takeIf { it.isNotEmpty() }
        val description = meta("og:description", "description", "twitter:description")
        val image = (meta("og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src")
            ?: doc.selectFirst("link[rel=image_src]")?.attr("href"))
            ?.let { absolute(doc, it) }
            ?.takeIf { it.startsWith("http") }

        return PageMeta(
            title = title,
            description = description,
            siteName = meta("og:site_name"),
            imageUrl = image,
            iconUrls = icons(doc, baseUrl),
            repoUrl = if (Urls.domain(baseUrl) == "github.com") null else repoLink(doc, baseUrl, title),
            contentType = contentType(doc, baseUrl, meta("og:type")),
            text = mainText(doc),
        )
    }

    /** Main text without navigation, headers/footers, scripts and cookie notices. */
    fun mainText(doc: Document, maxChars: Int = 8000): String? {
        val d = doc.clone()
        d.select("script, style, noscript, svg, nav, header, footer, aside, form, iframe, button, template, " +
            "[role=navigation], [role=banner], [role=contentinfo], [aria-hidden=true], " +
            "[id*=cookie], [class*=cookie], [id*=consent], [class*=consent], [class*=newsletter], [class*=share]").remove()
        val root = d.selectFirst("article") ?: d.selectFirst("main") ?: d.selectFirst("[role=main]") ?: d.body() ?: return null
        val blocks = root.select("h1, h2, h3, h4, p, li, pre, blockquote, td, dd")
            .map { it.ownText().ifBlank { it.text() }.trim() }
            .filter { it.length >= 3 }
        val text = (if (blocks.size >= 3) blocks.distinct().joinToString("\n") else root.text())
            .replace(Regex("[ \\t\\u00A0]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        return text.take(maxChars).takeIf { it.length >= 40 }
    }

    private fun absolute(doc: Document, href: String): String =
        runCatching { java.net.URI(doc.location()).resolve(href.trim().replace(" ", "%20")).toString() }.getOrDefault(href)

    /** Favicons sorted: PNG/ICO in 32–192 px preferred, SVG last (cannot be decoded as a bitmap). */
    private fun icons(doc: Document, baseUrl: String): List<String> {
        data class Cand(val url: String, val score: Int)
        val cands = doc.select("link[rel~=(?i)icon]").mapNotNull { el ->
            val href = el.attr("href").takeIf { it.isNotBlank() && !it.startsWith("data:") } ?: return@mapNotNull null
            val url = absolute(doc, href)
            val rel = el.attr("rel").lowercase()
            val size = el.attr("sizes").lowercase().substringBefore('x').toIntOrNull()
            val type = el.attr("type").lowercase()
            val svg = type.contains("svg") || url.substringBefore('?').endsWith(".svg", true)
            var score = when {
                svg -> -100
                size == null -> 10
                size in 32..96 -> 50 - kotlin.math.abs(size - 64) / 4
                size in 97..256 -> 30
                size < 32 -> 20
                else -> 5
            }
            if ("apple-touch-icon" in rel) score += 5
            Cand(url, score)
        }.sortedByDescending { it.score }.map { it.url }
        val fallback = runCatching { java.net.URI(baseUrl).resolve("/favicon.ico").toString() }.getOrNull()
        return (cands + listOfNotNull(fallback)).distinct()
    }

    /**
     * Finds the GitHub link of a project page. Takes a repo only if it is unambiguous:
     * either the only linked repo, or its name matches domain/title.
     */
    fun repoLink(doc: Document, baseUrl: String, title: String?): String? {
        val repos = doc.select("a[href*=github.com/]").mapNotNull { Urls.githubRepo(it.absUrl("href").ifEmpty { it.attr("href") }) }
            .map { (o, r) -> o.lowercase() to r.lowercase() }
            .filter { (o, _) -> o !in setOf("sponsors", "login", "join") }
        if (repos.isEmpty()) return null
        val counts = repos.groupingBy { it }.eachCount()
        val host = (Urls.domain(baseUrl) ?: "").lowercase()
        val t = (title ?: "").lowercase()
        fun related(r: Pair<String, String>): Boolean {
            val name = r.second.replace("-", "").replace("_", "").replace(".", "")
            val h = host.replace("-", "").replace(".", "")
            return name.length >= 3 && (h.contains(name) || t.replace(" ", "").contains(name) || host.contains(r.first))
        }
        val best = counts.entries.sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { related(it.key) }.thenByDescending { it.value }).first().key
        if (counts.size == 1 || related(best)) {
            // take over original capitalization from the first matching link
            val original = doc.select("a[href*=github.com/]").firstNotNullOfOrNull { a ->
                Urls.githubRepo(a.absUrl("href").ifEmpty { a.attr("href") })?.takeIf { (o, r) -> o.lowercase() == best.first && r.lowercase() == best.second }
            } ?: best
            return "https://github.com/${original.first}/${original.second}"
        }
        return null
    }

    private fun contentType(doc: Document, url: String, ogType: String?): String? {
        val host = Urls.domain(url) ?: ""
        return when {
            host == "github.com" || host == "gitlab.com" || host == "codeberg.org" -> "repo"
            host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("vimeo.com") || ogType?.startsWith("video") == true -> "video"
            ogType?.startsWith("product") == true || ogType == "og:product" -> "shop"
            ogType == "article" || doc.selectFirst("article") != null && doc.select("p").size > 8 -> "article"
            host.startsWith("docs.") || "/docs" in url -> "docs"
            else -> null
        }
    }
}
