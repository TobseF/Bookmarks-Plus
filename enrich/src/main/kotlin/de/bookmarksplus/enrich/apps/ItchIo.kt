package de.bookmarksplus.enrich.apps

import de.bookmarksplus.enrich.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import java.net.URI

/** Details of an itch.io page (`<author>.itch.io/<project>`). */
data class ItchApp(
    val url: String,
    val slug: String,
    /** Title of the page ("Flowframes - Fast Video Interpolation for any GPU"). */
    val title: String,
    /** Short program name (suffix after " - " removed, as far as it matches the slug). */
    val name: String,
    val description: String?,
    /** Operating systems according to the download list: windows, mac, linux, android (page order). */
    val systems: List<String>,
    /** Playable in the browser (HTML5 embed). */
    val web: Boolean,
    val iOs: Boolean,
    /** Category according to the info panel: Game, Tool, Assets, … */
    val category: String?,
    val author: String?,
    val authorUrl: String?,
    val tags: List<String>,
    val rating: Double?,
    val ratingCount: Long?,
    /** Download file names, e.g. "Flowframes 1.36.0 (Web Installer)". */
    val downloads: List<String>,
    /** Screenshots (largest offered preview, at most 3). */
    val screenshots: List<String>,
    /** Cover image (og:image). */
    val coverUrl: String?,
) {
    /** pc, smartphone, browser. */
    val platforms: List<String>
        get() = buildList {
            if (systems.any { it in setOf("windows", "mac", "linux") }) add("pc")
            if ("android" in systems || iOs) add("smartphone")
            if (web) add("browser")
        }
}

object ItchParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Download icons → system (`icon-windows8`, `icon-apple`, `icon-tux`, `icon-android`). */
    private val ICON_SYSTEMS = mapOf("icon-windows8" to "windows", "icon-apple" to "mac", "icon-tux" to "linux", "icon-android" to "android")

    /** Platform links of the info panel (`…/platform-windows`, `platform-osx`, …). */
    private val PLATFORM_SYSTEMS = mapOf("windows" to "windows", "osx" to "mac", "linux" to "linux", "android" to "android")

    /** `https://author.itch.io/project` → (author, project); profile pages, `www.itch.io`, `itch.io/games/…` etc. yield null. */
    fun pageOf(url: String): Pair<String, String>? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (!host.endsWith(".itch.io") || host == "www.itch.io") return null
        val seg = u.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        if (seg.isEmpty() || seg[0].lowercase() in NOT_PROJECTS) return null
        return host.removeSuffix(".itch.io") to seg[0].lowercase()
    }

    fun pageUrl(url: String): String? = pageOf(url)?.let { (user, slug) -> "https://$user.itch.io/$slug" }

    fun parse(html: String, pageUrl: String): ItchApp? {
        val doc = Jsoup.parse(html, pageUrl)
        val canonicalPage = doc.selectFirst("meta[name=twitter:url]")?.attr("content")?.takeIf { it.isNotBlank() } ?: pageUrl
        val (user, slug) = pageOf(canonicalPage) ?: pageOf(pageUrl) ?: return null

        // game/program pages carry `itch:path` = games/<id>; profiles and lists do not
        if (doc.selectFirst("meta[name=itch:path]")?.attr("content")?.startsWith("games/") != true) return null

        val ld = doc.select("script[type=application/ld+json]").asSequence()
            .mapNotNull { runCatching { json.parseToJsonElement(it.data()).jsonObject }.getOrNull() }
            .firstOrNull { (it["@type"] as? JsonPrimitive)?.contentOrNull == "Product" }
        fun s(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        val title = doc.selectFirst("h1.game_title")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: s(ld, "name") ?: return null
        val name = AppNames.fromTitle(title, listOf(slug.replace('-', ' '), slug)) ?: title

        // operating systems from the downloads; without download icons fall back to the platform row of the info panel
        val uploads = doc.select(".uploads .upload")
        val fromDownloads = uploads.flatMap { up -> up.select(".download_platforms .icon").flatMap { it.classNames() }.mapNotNull(ICON_SYSTEMS::get) }
        val fromPanel by lazy {
            panelRow(doc, "Platforms")?.select("a[href]").orEmpty()
                .mapNotNull { a -> PLATFORM_SYSTEMS[a.attr("href").substringAfterLast("platform-", "")] }
        }
        val systems = fromDownloads.ifEmpty { fromPanel }.distinct()
        val panelHrefs = panelRow(doc, "Platforms")?.select("a[href]").orEmpty().map { it.attr("href") }
        val web = doc.selectFirst(".html_embed_widget, .embed_wrapper, [data-embed_type=html]") != null ||
            panelHrefs.any { it.endsWith("platform-web") }
        val iOs = fromDownloads.isEmpty() && panelHrefs.any { it.endsWith("platform-ios") }

        val author = panelRow(doc, "Author")?.selectFirst("a")
        val tags = panelRow(doc, "Tags")?.select("a")?.map { it.text().trim() }.orEmpty().filter { it.isNotEmpty() }
        val rating = (ld?.get("aggregateRating") as? JsonObject)
        val ratingValue = (rating?.get("ratingValue") as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        val ratingCount = (rating?.get("ratingCount") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

        val shots = doc.select(".screenshot_list a[href]").mapNotNull { a ->
            val img = a.selectFirst("img")
            // largest version from srcset (originals are often huge); otherwise src or link
            img?.attr("srcset")?.split(',')?.mapNotNull { it.trim().substringBefore(' ').takeIf(String::isNotEmpty) }?.lastOrNull()
                ?: img?.attr("abs:src")?.takeIf { it.isNotEmpty() }
                ?: a.attr("abs:href").takeIf { it.isNotEmpty() }
        }.distinct().take(3)

        return ItchApp(
            url = "https://$user.itch.io/$slug", slug = slug, title = title, name = name,
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.ifEmpty { null } ?: s(ld, "description"),
            systems = systems, web = web, iOs = iOs,
            category = panelRow(doc, "Category")?.text()?.trim()?.ifEmpty { null },
            author = author?.text()?.trim()?.ifEmpty { null },
            authorUrl = author?.attr("abs:href")?.ifEmpty { null },
            tags = tags, rating = ratingValue, ratingCount = ratingCount,
            downloads = uploads.mapNotNull { it.selectFirst(".upload_name .name")?.text()?.trim()?.ifEmpty { null } },
            screenshots = shots,
            coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifEmpty { null },
        )
    }

    /** Cell next to the row heading of the info panel ("Platforms", "Author", …). */
    private fun panelRow(doc: org.jsoup.nodes.Document, label: String) =
        doc.select(".game_info_panel_widget tr").firstOrNull { it.selectFirst("td")?.text()?.trim().equals(label, true) }?.select("td")?.getOrNull(1)

    private val NOT_PROJECTS = setOf("games", "game", "devlog", "community", "jam", "feed", "login", "register", "dashboard", "following")
}

/** Result of an itch.io fetch. */
sealed interface ItchResult {
    data class Found(val app: ItchApp) : ItchResult
    /** Page does not exist (404) or is not a project page. */
    data object NotFound : ItchResult
    /** 403/429 or error; try again later. */
    data class Blocked(val until: Long) : ItchResult
}

/** itch.io has no bot check but asks for restraint: one request at a time, interval approx. 5 s. */
class ItchClient(
    private val http: Http,
    val gate: RateGate = RateGate("itch.io", minIntervalMs = 5_000, jitterPercent = 50, dailyLimit = 1_000),
) {
    suspend fun fetch(url: String): ItchResult {
        val page = ItchParser.pageUrl(url) ?: return ItchResult.NotFound
        val r = gate.run { http.get(page, extraHeaders = mapOf("Accept-Language" to "en-US,en;q=0.9")) }
        if (r.status == 404 || r.status == 410) { gate.success(); return ItchResult.NotFound }
        val html = r.text()
        if (!r.ok || html == null) return ItchResult.Blocked(gate.penalize())
        gate.success()
        return ItchParser.parse(html, r.finalUrl.ifBlank { page })?.let { ItchResult.Found(it) } ?: ItchResult.NotFound
    }

    /** Load image (screenshots live on `img.itch.zone`). */
    fun image(url: String): ByteArray? =
        http.get(url, maxBytes = 6 shl 20, accept = "image/jpeg,image/png;q=0.9,*/*;q=0.1").takeIf { it.ok }?.body
}
