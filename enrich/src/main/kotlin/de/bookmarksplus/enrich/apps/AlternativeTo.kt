package de.bookmarksplus.enrich.apps

import de.bookmarksplus.i18n.tr
import de.bookmarksplus.enrich.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup

/** Details of an AlternativeTo page (`alternativeto.net/software/<slug>/about/`). */
data class AlternativeToApp(
    val slug: String,
    val name: String,
    val description: String?,
    /** Platforms according to the page: Windows, Mac, Linux, Android, iPhone, Online, Self-Hosted, Chrome … */
    val operatingSystems: List<String>,
    val category: String?,
    /** "GPL-2.0", "Proprietary" … */
    val license: String?,
    val openSource: Boolean?,
    val developer: String?,
    val website: String?,
    val repoUrl: String?,
    val flathubUrl: String?,
    val likes: Long?,
    val rating: Double?,
    val packages: PackageIds.Found,
    val screenshots: List<String>,
    /** Raster version (PNG, 280 px) of the app icon; AlternativeTo otherwise only delivers it as SVG. */
    val iconUrl: String? = null,
) {
    val url: String get() = "https://alternativeto.net/software/$slug/about/"

    /** Platforms (pc, smartphone, browser, docker) according to the operating system list. */
    val platforms: List<String>
        get() = operatingSystems.mapNotNull { os ->
            when (os.lowercase()) {
                "windows", "mac", "linux", "bsd", "chrome os", "haiku", "reactos", "portableapps.com", "steam", "windows s" -> "pc"
                "android", "iphone", "ipad", "android tablet", "windows phone", "ios" -> "smartphone"
                "online", "web", "chrome", "firefox", "edge", "safari", "opera", "vivaldi", "brave", "google chrome", "mozilla firefox", "microsoft edge" -> "browser"
                "self-hosted", "docker" -> "docker"
                else -> null
            }
        }.distinct()

    val systems: List<String>
        get() = operatingSystems.mapNotNull { os ->
            when (os.lowercase()) {
                "windows", "windows s", "portableapps.com" -> "windows"
                "mac" -> "mac"
                "linux" -> "linux"
                "android", "android tablet" -> "android"
                else -> null
            }
        }.distinct()
}

object AlternativeToParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(html: String, pageUrl: String): AlternativeToApp? {
        if (Challenge.isChallenge(html)) return null
        val doc = Jsoup.parse(html, pageUrl)
        val ld = doc.select("script[type=application/ld+json]").asSequence()
            .mapNotNull { runCatching { json.parseToJsonElement(it.data()).jsonObject }.getOrNull() }
            .firstOrNull { (it["@type"] as? JsonPrimitive)?.contentOrNull == "SoftwareApplication" }
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("abs:href")
        val slug = AppInfoSlug.of(listOfNotNull(canonical, (ld?.get("url") as? JsonPrimitive)?.contentOrNull, pageUrl)) ?: return null
        fun s(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val name = s(ld, "name") ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val os = (ld?.get("operatingSystem") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: doc.select("[data-testid=platform-row] li").map { it.text().trim() }
        val publisher = ld?.get("publisher") as? JsonObject
        val rating = ld?.get("aggregateRating") as? JsonObject

        // "Licensing: Open Source (GPL-2.0) and Freemium product." or "Proprietary"
        val licensing = doc.select("h4").firstOrNull { it.text().trim().equals("Licensing", true) }?.parent()
        val licenseLink = licensing?.selectFirst("a[href*=choosealicense.com], a[href*=opensource.org], a[href*=spdx.org]")?.text()?.trim()
        val licText = licensing?.text()?.removePrefix("Licensing")?.trim().orEmpty()
        val openSource = when {
            licText.contains("Open Source", true) -> true
            licText.contains("Proprietary", true) -> false
            else -> null
        }
        val license = licenseLink?.takeIf { it.isNotEmpty() } ?: if (openSource == false) "Proprietary" else null

        // install commands ("flatpak install flathub …", "winget install …") and package pages
        val commands = doc.select("code").map { it.text() }.filter { c -> CMD.any { c.trimStart().startsWith(it) } }
        val external = doc.select("a[href]").map { it.attr("abs:href") }.filter { it.startsWith("http") && "alternativeto.net" !in it }
        val packages = PackageIds.find(commands.joinToString("\n"), name, slug)
        val flathub = external.firstOrNull { "flathub.org/apps/" in it }
            ?: packages.flatpak?.let { "https://flathub.org/apps/$it" }
        val repo = external.firstOrNull { REPO.containsMatchIn(it) }

        val likes = doc.selectFirst("[aria-label*= likes]")?.attr("aria-label")
            ?.let { Regex("""([\d,.]+)\s+likes""").find(it)?.groupValues?.get(1)?.filter(Char::isDigit)?.toLongOrNull() }

        // screenshots: largest JPEG version per image
        val shots = Regex("""https://img\.alternativeto\.net/s/(\d+)x\d+/jpeg/([A-Za-z0-9-]+_\d+_full\.(?:png|jpe?g))""")
            .findAll(html).map { it.groupValues[2] to (it.groupValues[1].toInt() to it.value) }
            .groupBy({ it.first }, { it.second }).values.mapNotNull { v -> v.maxByOrNull { it.first }?.second }
            .take(3)

        // icon of the app itself (not of the alternatives): <img data-testid="icon-<name>"> or alt "<name> icon"
        val icon = doc.select("img[src*=img.alternativeto.net/icons/]")
            .firstOrNull { it.attr("data-testid") == "icon-$name" || it.attr("alt").equals("$name icon", true) }
            ?.attr("abs:src")?.let(::rasterIcon)

        return AlternativeToApp(
            slug = slug, name = name,
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.ifEmpty { null }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifEmpty { null },
            operatingSystems = os.filter { it.isNotBlank() }.distinct(),
            category = s(ld, "applicationCategory"),
            license = license, openSource = openSource,
            developer = s(publisher, "name"),
            website = s(publisher, "url")?.takeIf { it.startsWith("http") && "alternativeto.net" !in it },
            repoUrl = repo, flathubUrl = flathub,
            likes = likes,
            rating = (rating?.get("ratingValue") as? JsonPrimitive)?.doubleOrNull,
            packages = packages, screenshots = shots, iconUrl = icon,
        )
    }

    /**
     * Search results page (`/browse/search/?q=…`): (slug, name) per hit. Hits are in `[data-testid=main-app-info]`
     * with the name as heading; partner/ad entries are outside and are not considered.
     */
    fun parseSearch(html: String): List<Pair<String, String>> {
        if (Challenge.isChallenge(html)) return emptyList()
        val doc = Jsoup.parse(html, "https://alternativeto.net/")
        return doc.select("[data-testid=main-app-info]").mapNotNull { box ->
            val a = box.select("a[href]").firstOrNull { (it.selectFirst("h2") != null) && de.bookmarksplus.data.AppInfo.alternativetoSlugOf(it.attr("abs:href")) != null }
                ?: return@mapNotNull null
            val slug = de.bookmarksplus.data.AppInfo.alternativetoSlugOf(a.attr("abs:href")) ?: return@mapNotNull null
            val name = a.selectFirst("h2")!!.text().trim().ifEmpty { return@mapNotNull null }
            slug to name
        }.distinctBy { it.first }
    }

    /** `…/icons/140/name.svg?theme=light` or `…/icons/140/jpeg/name.png` → `…/icons/280/png/name.<ext>` (PNG, also from SVG). */
    fun rasterIcon(src: String): String? =
        Regex("""^https://img\.alternativeto\.net/icons/\d+/(?:[a-z]+/)?([^/?#]+)""").find(src)?.let { "https://img.alternativeto.net/icons/280/png/${it.groupValues[1]}" }

    private val CMD = listOf("winget ", "choco ", "cinst ", "scoop ", "brew ", "npm ", "flatpak ", "snap ", "pnpm ", "yarn ")
    private val REPO = Regex("""^https?://(?:www\.)?(?:github\.com|gitlab\.com|codeberg\.org)/[^/]+/[^/#?]+""")
}

private object AppInfoSlug {
    fun of(urls: List<String>): String? = urls.firstNotNullOfOrNull { de.bookmarksplus.data.AppInfo.alternativetoSlugOf(it) }
}

/** Result of an AlternativeTo search. */
sealed interface AtSearch {
    data class Hits(val hits: List<Pair<String, String>>) : AtSearch
    data class Blocked(val until: Long) : AtSearch
}

/** Result of an AlternativeTo fetch. */
sealed interface AtResult {
    data class Found(val app: AlternativeToApp, val via: String) : AtResult
    /** Page does not exist (404) – slug does not match. */
    data object NotFound : AtResult
    /** Blocked or bot check on all paths; try again later. */
    data class Blocked(val until: Long) : AtResult
}

/**
 * AlternativeTo blocks program fetches quickly (Cloudflare; even the second request can return 403). Therefore:
 * - one request at a time, minimum interval (default 20 s) plus random share, daily limit, pause after blocks;
 * - fetch path by availability: direct → browser extension (real browser with cookies) → own browser (Playwright profile).
 *   If the direct path is blocked, it stays off for a few hours.
 */
class AlternativeToClient(
    private val http: Http,
    val gate: RateGate = RateGate("AlternativeTo", minIntervalMs = 20_000, jitterPercent = 50, dailyLimit = 400),
    private val bridge: () -> BrowserBridge? = { null },
    /** Own browser (Playwright, persistent profile): (status, HTML) or null. */
    private val browser: (suspend (String) -> Pair<Int, String>?)? = null,
    private val useBridge: () -> Boolean = { true },
    private val useBrowser: () -> Boolean = { true },
    /** Try direct fetch (tests: off). */
    private val useDirect: () -> Boolean = { true },
) {
    @Volatile private var directBlockedUntil = 0L
    @Volatile private var directStrikes = 0
    @Volatile var lastVia: String? = null
        private set

    /** Load and evaluate program page. */
    suspend fun fetch(slug: String): AtResult = when (val r = load("https://alternativeto.net/software/$slug/about/")) {
        is Loaded.Page -> AlternativeToParser.parse(r.html, r.url)?.let { AtResult.Found(it, r.via) } ?: AtResult.NotFound
        Loaded.NotFound -> AtResult.NotFound
        is Loaded.Blocked -> AtResult.Blocked(r.until)
    }

    /**
     * Search for [name] (e.g. when the slug does not match: "Hoppscotch" lives under `/software/postwoman/`).
     * Returns the hits (slug, name) in page order, ad entries excluded.
     */
    suspend fun search(name: String): AtSearch {
        val q = java.net.URLEncoder.encode(name, Charsets.UTF_8)
        return when (val r = load("https://alternativeto.net/browse/search/?q=$q")) {
            is Loaded.Page -> AtSearch.Hits(AlternativeToParser.parseSearch(r.html))
            Loaded.NotFound -> AtSearch.Hits(emptyList())
            is Loaded.Blocked -> AtSearch.Blocked(r.until)
        }
    }

    private sealed interface Loaded {
        class Page(val html: String, val url: String, val via: String) : Loaded
        data object NotFound : Loaded
        class Blocked(val until: Long) : Loaded
    }

    /** Load a page via the first available path: direct → extension → own browser. */
    private suspend fun load(url: String): Loaded {
        val now = System.currentTimeMillis()

        // 1) direct
        if (useDirect() && now >= directBlockedUntil) {
            val r = gate.run { http.get(url, extraHeaders = mapOf("Accept-Language" to "en-US,en;q=0.9")) }
            if (r.status == 404 || r.status == 410) { gate.success(); return Loaded.NotFound }
            val html = r.text()
            if (r.ok && html != null && !Challenge.isChallenge(html)) {
                directStrikes = 0
                gate.success()
                lastVia = "direkt"
                return Loaded.Page(html, r.finalUrl, "direkt")
            }
            // blocked: avoid the direct path for 2 h, 4 h, … 24 h
            directStrikes++
            directBlockedUntil = now + (2 * 3_600_000L * directStrikes).coerceAtMost(24 * 3_600_000L)
        }

        // 2) browser extension
        val b = bridge()
        if (useBridge() && b != null && b.available) {
            gate.run { } // only interval and daily limit; the fetch itself runs in the browser
            val r = b.fetchHtml(url)
            if (r != null) {
                if (r.status == 404) { gate.success(); return Loaded.NotFound }
                if (r.html != null && !Challenge.isChallenge(r.html)) {
                    gate.success()
                    lastVia = tr("enrich.via.extension")
                    return Loaded.Page(r.html, r.finalUrl, tr("enrich.via.extension"))
                }
            }
        }

        // 3) own browser with persistent profile
        if (useBrowser() && browser != null) {
            gate.run { }
            val r = browser.invoke(url)
            if (r != null) {
                if (r.first == 404) { gate.success(); return Loaded.NotFound }
                if (r.first in 200..299 && !Challenge.isChallenge(r.second)) {
                    gate.success()
                    lastVia = "Browser"
                    return Loaded.Page(r.second, url, "Browser")
                }
            }
        }
        return Loaded.Blocked(gate.penalize())
    }

    /** Load image (screenshots live on their own CDN without bot check). */
    fun image(url: String): ByteArray? =
        http.get(url, maxBytes = 6 shl 20, accept = "image/jpeg,image/png;q=0.9,*/*;q=0.1").takeIf { it.ok }?.body
}
