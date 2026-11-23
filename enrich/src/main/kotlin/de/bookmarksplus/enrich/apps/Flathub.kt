package de.bookmarksplus.enrich.apps

import de.bookmarksplus.enrich.Http
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Details of a Flathub app (from the public API, not from the HTML). */
data class FlathubApp(
    val id: String,
    val name: String,
    val summary: String?,
    /** Description as plain text (paragraphs, list items with "- "). */
    val description: String?,
    val developer: String?,
    val license: String?,
    val homepage: String?,
    /** Source code (`vcs_browser`), if specified. */
    val vcs: String?,
    val iconUrl: String?,
    /** Large versions of the screenshots (at most three). */
    val screenshots: List<String>,
    val categories: List<String>,
    val version: String?,
    /** Epoch seconds of the latest release. */
    val releaseDate: Long?,
    val installs: Long? = null,
    val downloadSize: Long? = null,
    val installedSize: Long? = null,
    val isEol: Boolean = false,
) {
    val url: String get() = "https://flathub.org/apps/$id"
}

/** Hit of the Flathub search. */
data class FlathubHit(val id: String, val name: String, val summary: String?, val developer: String?, val installsLastMonth: Long?)

object FlathubParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

    /** `GET /api/v2/appstream/<id>` */
    fun parseAppstream(text: String): FlathubApp? {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val id = o.s("id") ?: return null
        val name = o.s("name") ?: return null
        val urls = o["urls"].obj()
        val shots = o["screenshots"].arr().orEmpty().mapNotNull { sh ->
            // largest version that is not a huge original (width ≤ 1600)
            val sizes = sh.obj()?.get("sizes").arr().orEmpty().mapNotNull { it.obj() }
            sizes.mapNotNull { sz -> sz.s("src")?.let { src -> (sz.s("width")?.toIntOrNull() ?: 0) to src } }
                .filter { it.first in 1..1400 && "_orig." !in it.second }.maxByOrNull { it.first }?.second
                ?: sizes.firstNotNullOfOrNull { it.s("src") }
        }.distinct().take(3)
        val icon = o["icons"].arr().orEmpty().mapNotNull { it.obj() }
            .maxByOrNull { (it["width"] as? JsonPrimitive)?.longOrNull ?: 0L }?.s("url") ?: o.s("icon")
        val release = o["releases"].arr()?.firstOrNull()?.obj()
        return FlathubApp(
            id = id, name = name, summary = o.s("summary"),
            description = o.s("description")?.let(::htmlToText),
            developer = o.s("developer_name"),
            license = o.s("project_license")?.let(::cleanLicense),
            homepage = urls?.s("homepage"),
            vcs = urls?.s("vcs_browser"),
            iconUrl = icon, screenshots = shots,
            categories = o["categories"].arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .filterNot { it.equals("GTK", true) || it.equals("Qt", true) || it.equals("KDE", true) || it.equals("GNOME", true) },
            version = release?.s("version"),
            releaseDate = release?.s("timestamp")?.toLongOrNull(),
            isEol = (o["is_eol"] as? JsonPrimitive)?.contentOrNull == "true",
        )
    }

    /** `GET /api/v2/stats/<id>` → total installs. */
    fun parseInstalls(text: String): Long? =
        runCatching { (json.parseToJsonElement(text).jsonObject["installs_total"] as? JsonPrimitive)?.longOrNull }.getOrNull()

    /** `GET /api/v2/summary/<id>` → (download size, installed size) in bytes. */
    fun parseSizes(text: String): Pair<Long?, Long?> {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null to null
        val stable = o["branches"].obj()?.get("stable").obj()
        fun n(obj: JsonObject?, k: String) = (obj?.get(k) as? JsonPrimitive)?.longOrNull
        return (n(stable, "download_size") ?: n(o, "download_size")) to (n(stable, "installed_size") ?: n(o, "installed_size"))
    }

    /** `POST /api/v2/search` */
    fun parseSearch(text: String): List<FlathubHit> {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        return o["hits"].arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { h ->
            val id = h.s("app_id") ?: h.s("id")?.replace('_', '.') ?: return@mapNotNull null
            FlathubHit(id, h.s("name") ?: return@mapNotNull null, h.s("summary"), h.s("developer_name"),
                (h["installs_last_month"] as? JsonPrimitive)?.longOrNull)
        }
    }

    /** "LicenseRef-proprietary" → "Proprietary"; SPDX expressions remain. */
    fun cleanLicense(l: String): String = when {
        l.startsWith("LicenseRef-proprietary", true) -> "Proprietary"
        l.startsWith("LicenseRef-", true) -> l.removePrefix("LicenseRef-").substringBefore('=').replace('-', ' ').ifBlank { l }
        else -> l
    }

    private fun htmlToText(html: String): String =
        org.jsoup.Jsoup.parse(html).body().let { body ->
            body.children().joinToString("\n\n") { el ->
                if (el.tagName() == "ul" || el.tagName() == "ol") el.select("li").joinToString("\n") { "- " + it.text().trim() }
                else el.text().trim()
            }.ifBlank { body.text() }
        }.trim()
}

/**
 * Flathub API (`flathub.org/api/v2`). Public and without a key; nevertheless at most one request at a time
 * and a short pause in between.
 */
class FlathubClient(private val http: Http, private val gate: RateGate = RateGate("flathub", minIntervalMs = 400)) {

    private val headers = mapOf("User-Agent" to USER_AGENT)

    /** Complete details incl. installs and sizes; null if the app does not (or no longer) exist. */
    suspend fun app(id: String): FlathubApp? {
        val a = gate.run { http.get("$API/appstream/$id", accept = "application/json", extraHeaders = headers) }
        if (!a.ok) return null
        val app = a.text()?.let(FlathubParser::parseAppstream) ?: return null
        val installs = gate.run { http.get("$API/stats/$id", accept = "application/json", extraHeaders = headers) }
            .takeIf { it.ok }?.text()?.let(FlathubParser::parseInstalls)
        val (dl, inst) = gate.run { http.get("$API/summary/$id", accept = "application/json", extraHeaders = headers) }
            .takeIf { it.ok }?.text()?.let(FlathubParser::parseSizes) ?: (null to null)
        return app.copy(installs = installs, downloadSize = dl, installedSize = inst)
    }

    suspend fun search(query: String): List<FlathubHit> {
        val body = """{"query":${Json.encodeToString(String.serializer(), query)}}"""
        val r = gate.run { http.post("$API/search", body, emptyMap()) }
        return if (r.ok) r.text()?.let(FlathubParser::parseSearch).orEmpty() else emptyList()
    }

    /** Image (icon/screenshot) as bytes. */
    suspend fun image(url: String, maxBytes: Int = 8 shl 20): ByteArray? =
        gate.run { http.get(url, maxBytes = maxBytes, accept = "image/png,image/jpeg;q=0.9,*/*;q=0.1", extraHeaders = headers) }
            .takeIf { it.ok }?.body

    companion object {
        const val API = "https://flathub.org/api/v2"
        const val USER_AGENT = "BookmarksPlus/1.2 (local bookmark manager)"
    }
}

