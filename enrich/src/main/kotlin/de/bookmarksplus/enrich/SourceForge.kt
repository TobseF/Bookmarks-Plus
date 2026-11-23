package de.bookmarksplus.enrich

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Details of a SourceForge project page (`sourceforge.net/projects/<name>/`). */
data class SourceForgeInfo(
    /** Short name, the current one after moves (e.g. 7zip → sevenzip). */
    val project: String,
    val name: String?,
    /** One-liner under the project name. */
    val tagline: String?,
    /** Description in the "Summary" section. */
    val description: String?,
    val iconUrl: String?,
    /** "Last Update" (epoch seconds, UTC midnight). */
    val lastUpdate: Long?,
    /** Directory categories from the path, e.g. "Games", "Side-Scrolling". */
    val categories: List<String>,
    val reviews: Long?,
    val rating: Double?,
    /** "Brought to you by". */
    val authors: List<String>,
    /** Large images from "Project Samples" (at most three). */
    val screenshots: List<String>,
    val homepage: String?,
    val downloadUrl: String?,
    /** File name according to the download button. */
    val downloadFile: String?,
    val features: List<String> = emptyList(),
) {
    /** Text for description and search: one-liner and detailed description. */
    val summaryText: String?
        get() = listOfNotNull(tagline, description).distinct().joinToString("\n\n").ifBlank { null }
}

/** Current file according to `best_release.json`, preferably for Windows. */
data class SourceForgeRelease(val file: String, val date: Long?, val downloadUrl: String, val path: String = file) {
    /** Version number from the file name or folder (e.g. "/7-Zip/26.06/7z2606-x64.exe"), otherwise the file name. */
    val version: String get() = VERSION.find(file)?.value ?: VERSION.findAll(path).lastOrNull()?.value ?: file

    private companion object {
        val VERSION = Regex("""\d+(?:\.\d+)+[a-z]?""", RegexOption.IGNORE_CASE)
    }
}

object SourceForgeParser {
    /** Top-level folders that are not categories. */
    private val ROOTS = setOf("home", "open source software", "business software", "directory")

    fun parse(html: String, pageUrl: String): SourceForgeInfo? {
        val doc = Jsoup.parse(html, pageUrl)
        val project = Regex("""/projects/([^/?#]+)""").find(pageUrl)?.groupValues?.get(1)
            ?: doc.selectFirst("link[rel=canonical]")?.attr("href")?.let { Regex("""/projects/([^/?#]+)""").find(it)?.groupValues?.get(1) }
            ?: return null
        // Cloudflare or error page instead of project page
        if (doc.selectFirst("nav#breadcrumbs") == null && doc.selectFirst("h1[itemprop=name]") == null) return null

        fun abs(u: String?): String? = u?.trim()?.takeIf { it.isNotEmpty() }?.let {
            when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("/") -> "https://sourceforge.net$it"
                else -> it
            }
        }

        val name = doc.selectFirst("h1[itemprop=name]")?.ownText()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
        val tagline = doc.selectFirst("h2.summary")?.text()?.trim()?.ifEmpty { null }
        val description = doc.selectFirst("p[itemprop=description]")?.text()?.trim()?.ifEmpty { null }

        // project icon (not the default icon of SourceForge)
        val icon = doc.select(".overview .project-icon").firstOrNull { !it.hasClass("default-project-icon") }
            ?.selectFirst("img")?.let { it.attr("src").ifEmpty { it.attr("data-src") } }
            ?.let(::abs)

        val lastUpdate = doc.selectFirst("time.dateUpdated")?.attr("datetime")?.let { d ->
            runCatching { LocalDate.parse(d.take(10)).atStartOfDay().toEpochSecond(ZoneOffset.UTC) }.getOrNull()
        }

        // path "Home › Open Source Software › Games › Side-Scrolling › Project" → Games, Side-Scrolling
        val categories = doc.select("nav#breadcrumbs li").asSequence()
            .takeWhile { !it.hasClass("project") }
            .mapNotNull { li ->
                val href = li.selectFirst("a")?.attr("href").orEmpty()
                li.selectFirst("[itemprop=name]")?.text()?.trim()?.takeIf { href.startsWith("/directory/") && href.trimEnd('/') != "/directory" }
            }
            .filter { it.isNotEmpty() && it.lowercase() !in ROOTS }
            .distinct().toList()

        val reviews = doc.selectFirst("meta[itemprop=ratingCount]")?.attr("content")?.toLongOrNull()
            ?: doc.selectFirst("a.count[href*=/reviews]")?.text()?.let { Regex("""([\d,.]+)\s+Reviews?""").find(it)?.groupValues?.get(1) }
                ?.filter(Char::isDigit)?.toLongOrNull()
        val rating = doc.selectFirst("[itemprop=ratingValue]")?.let { it.attr("content").ifEmpty { it.text() } }?.toDoubleOrNull()

        val authors = doc.select(".brought-by a").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        // Project Samples: links to the large images (skip videos)
        val screenshots = doc.select(".m-screenshots a[href]")
            .filterNot { it.hasClass("video") || it.selectFirst(".video-screenshot, .video-screenshot-new") != null }
            .mapNotNull { abs(it.attr("href")) }
            .filter { "/screenshots/" in it }
            .distinct().take(3)

        val homepage = doc.selectFirst("a#homepage[href]")?.attr("href")?.trim()
            ?.takeIf { it.startsWith("http", true) && "sourceforge.net" !in it }

        val dl = doc.selectFirst(".download-container a.download[href]")
        val downloadUrl = dl?.attr("href")?.let(::abs)
        val downloadFile = dl?.attr("title")?.let { Regex("""Download\s+(.+?)\s+from SourceForge""").find(it)?.groupValues?.get(1) }

        val features = doc.select("ul.features li.feature").map { it.text().trim() }.filter { it.isNotEmpty() }

        return SourceForgeInfo(
            project = project, name = name, tagline = tagline, description = description, iconUrl = icon,
            lastUpdate = lastUpdate, categories = categories, reviews = reviews, rating = rating, authors = authors,
            screenshots = screenshots, homepage = homepage, downloadUrl = downloadUrl, downloadFile = downloadFile,
            features = features,
        )
    }

    /** `best_release.json`: Windows file, otherwise the default file. */
    fun parseRelease(json: String, project: String): SourceForgeRelease? {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val rel = (root["platform_releases"] as? JsonObject)?.get("windows") as? JsonObject
            ?: root["release"] as? JsonObject ?: return null
        val path = (rel["filename"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        val date = (rel["date"] as? JsonPrimitive)?.content?.let { d ->
            runCatching { LocalDateTime.parse(d, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
        }
        val encoded = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
        return SourceForgeRelease(
            file = path.substringAfterLast('/'),
            path = path,
            date = date,
            downloadUrl = "https://sourceforge.net/projects/$project/files$encoded/download",
        )
    }
}

/**
 * Reads SourceForge project pages. SourceForge sits behind Cloudflare, which checks requests with a browser identifier
 * ("Just a moment …"); with an honest program identifier, page, images and `best_release.json` are delivered.
 */
class SourceForgeClient(private val http: Http) {

    /** Project page (follows moves, e.g. 7zip → sevenzip). */
    fun page(project: String): FetchResult = http.get("https://sourceforge.net/projects/$project/", extraHeaders = HEADERS)

    fun fetch(project: String): SourceForgeInfo? {
        val page = page(project)
        if (!page.ok) return null
        return page.text()?.let { SourceForgeParser.parse(it, page.finalUrl) }
    }

    fun release(project: String): SourceForgeRelease? {
        val res = http.get("https://sourceforge.net/projects/$project/best_release.json", accept = "application/json", extraHeaders = HEADERS)
        return if (res.ok) res.text()?.let { SourceForgeParser.parseRelease(it, project) } else null
    }

    /** Image as PNG/JPEG – the CDN otherwise delivers WebP, which ImageIO (renderer, downscaling) cannot read. */
    fun image(url: String, maxBytes: Int = 8 shl 20): ByteArray? =
        http.get(url, maxBytes = maxBytes, accept = ACCEPT_IMAGE, extraHeaders = HEADERS).takeIf { it.ok }?.body

    companion object {
        const val USER_AGENT = "BookmarksPlus/1.1 (local bookmark manager)"
        private const val ACCEPT_IMAGE = "image/png,image/jpeg;q=0.9,image/gif;q=0.8,*/*;q=0.1"
        val HEADERS = mapOf("User-Agent" to USER_AGENT)
    }
}
