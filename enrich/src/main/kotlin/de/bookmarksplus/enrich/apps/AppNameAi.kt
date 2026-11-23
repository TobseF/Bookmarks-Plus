package de.bookmarksplus.enrich.apps

import de.bookmarksplus.data.AppPlatform
import de.bookmarksplus.data.AppSystem
import de.bookmarksplus.enrich.ai.AiClient
import de.bookmarksplus.enrich.ai.AiConfig
import de.bookmarksplus.enrich.ai.ChatResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Input for name finding. */
data class AppNameInput(
    val id: Long,
    val url: String,
    val title: String,
    val metaTitle: String?,
    val description: String?,
    val repo: String?,
    val website: String?,
)

/** Response: name (null = no program/game), platforms and systems, as far as certain. */
data class AppNameResult(val id: Long, val name: String?, val platforms: List<String>, val systems: List<String>)

/**
 * Bundled AI name finding (up to 25 bookmarks per call, few tokens per entry): is it a program,
 * an app, a game, a browser extension, a command-line tool or a self-hosted service –
 * and what is its official name?
 */
class AppNameAi(private val client: AiClient = AiClient()) {

    suspend fun resolve(cfg: AiConfig, items: List<AppNameInput>, effort: String? = null): Pair<List<AppNameResult>, ChatResult> {
        val user = buildString {
            items.forEachIndexed { i, it ->
                append(i + 1).append(". ").append(it.url).append('\n')
                append("   Titel: ").append(it.title.take(160)).append('\n')
                it.metaTitle?.takeIf { m -> m.isNotBlank() && m != it.title }?.let { m -> append("   Seitentitel: ").append(m.take(160)).append('\n') }
                it.description?.takeIf { d -> d.isNotBlank() }?.let { d -> append("   Beschreibung: ").append(d.take(200).replace('\n', ' ')).append('\n') }
                it.repo?.let { r -> append("   Repo: ").append(r).append('\n') }
                it.website?.let { w -> append("   Webseite: ").append(w).append('\n') }
            }
        }
        val r = client.chat(cfg, SYSTEM, user, maxTokens = 60 + items.size * 45, reasoningEffort = effort)
        return parse(r.text, items) to r
    }

    companion object {
        val SYSTEM = """
            Du bekommst nummerierte Lesezeichen. Bestimme für jedes, ob es um eine bestimmte Anwendung geht: Programm, App, Spiel,
            Browser-Erweiterung, Kommandozeilenwerkzeug, Web-App oder selbst gehosteter Dienst. Bibliotheken, Frameworks, Artikel,
            Anleitungen, Listen („awesome-…“), Shops, Firmen- und Nachrichtenseiten sind KEINE Anwendung.
            Antworte ausschließlich mit JSON: {"items":[{"i":1,"name":"FileZilla","platforms":["pc"],"systems":["windows","linux","mac"]},{"i":2,"name":null}]}
            - name: offizieller Produktname in üblicher Schreibweise, ohne Version, Slogan, Firmen- oder Seitennamen („FileZilla“, nicht „FileZilla – The free FTP solution“; „7-Zip“, „Visual Studio Code“). Keine Anwendung → null.
            - platforms (nur wenn bekannt): ${AppPlatform.entries.joinToString(", ") { it.key }} (browser = Web-App oder Erweiterung, docker = selbst gehostet/Container).
            - systems (nur wenn bekannt): ${AppSystem.entries.joinToString(", ") { it.key }}. Im Zweifel weglassen statt raten.
        """.trimIndent()

        private val json = Json { isLenient = true; ignoreUnknownKeys = true }

        fun parse(text: String, items: List<AppNameInput>): List<AppNameResult> {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyList()
            val root = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return emptyList()
            val arr = root["items"] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val i = (o["i"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val item = items.getOrNull(i - 1) ?: return@mapNotNull null
                val name = when (val n = o["name"]) {
                    null, is JsonNull -> null
                    is JsonPrimitive -> n.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "null" && it.length <= 80 }
                    else -> null
                }
                fun list(k: String, allowed: Set<String>) = (o[k] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.let(::alias) }.filter { it in allowed }.distinct()
                AppNameResult(
                    item.id, name,
                    if (name == null) emptyList() else list("platforms", AppPlatform.entries.map { it.key }.toSet()),
                    if (name == null) emptyList() else list("systems", AppSystem.entries.map { it.key }.toSet()),
                )
            }
        }

        private fun alias(s: String) = when (s) {
            "macos", "osx", "mac os" -> "mac"
            "win", "win32", "win64" -> "windows"
            "mobile", "phone" -> "smartphone"
            "web", "webapp", "extension" -> "browser"
            "desktop" -> "pc"
            else -> s
        }
    }
}
