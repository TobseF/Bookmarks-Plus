package de.bookmarksplus.enrich.ai

import de.bookmarksplus.data.Library
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Stage 1: uniform texts in both languages. */
data class AiTexts(
    val titleDe: String?, val titleEn: String?,
    val descDe: String?, val descEn: String?,
    val summaryDe: String?, val summaryEn: String?,
    val type: String?,
    /** Page is only a domain sales/parking page or shows foreign content (domain taken over). */
    val parked: Boolean = false,
    /** The page is about a specific application: official name, platforms, systems. */
    val app: AiApp? = null,
)

data class AiApp(val name: String, val platforms: List<String>, val systems: List<String>)

/** A tag with canonical English name and German display. */
data class AiTag(val en: String, val de: String)

/** Stage 2: classification. */
data class AiClassification(val tags: List<AiTag>, val folder: String?)

/** Input: everything that is known about a bookmark. */
data class AiInput(
    val url: String,
    val title: String,
    val description: String?,
    val metaTitle: String?,
    val metaDescription: String?,
    val text: String?,
    val readme: String?,
    val stars: Long?,
    val currentFolder: String?,
    /** Page no longer exists (404/410, domain gone). */
    val dead: Boolean,
    /** Content not retrievable (bot protection, login, only reachable locally) – the page probably still exists. */
    val blocked: Boolean = false,
)

/**
 * Prompts in two stages. Stage 1 (text model) reads page text or README and writes title, description and
 * summary in German and English – independent of the page language. Stage 2 (tag model) only gets
 * the short English texts, folder candidates and the tag vocabulary; this keeps it cheap and fast.
 */
object AiPrompt {

    val TYPES = listOf("article", "video", "repo", "docs", "tool", "shop", "news", "reference", "forum", "course", "other")

    val SYSTEM_TEXTS = """
        Du erstellst für eine Lesezeichensammlung einheitliche, sachliche Texte auf Deutsch UND Englisch – unabhängig von der Sprache der Seite.
        Antworte ausschließlich mit einem JSON-Objekt:
        {"title_de":"…","title_en":"…","description_de":"…","description_en":"…","summary_de":"…","summary_en":"…","type":"…","parked":false,"app":null}
        - title: prägnant, höchstens 70 Zeichen, ohne Seitennamen-Anhängsel wie „| Heise online“. Eigennamen und Produktnamen nicht übersetzen. GitHub-Repos: „Projektname – kurze Funktion“.
        - description: genau ein Satz (höchstens 160 Zeichen): was die Seite bzw. das Projekt ist.
        - summary: 2–3 Sätze: Inhalt und wofür es nützlich ist. Nur aus den gegebenen Informationen, nichts erfinden. Gibt es keinen Inhalt (Seite nicht erreichbar, nur Titel bekannt), summary_de und summary_en leer lassen.
        - type: einer von ${TYPES.joinToString(", ")}.
        - app: nur wenn die Seite eine bestimmte Anwendung zum Gegenstand hat (Programm, App, Spiel, Browser-Erweiterung, Kommandozeilenwerkzeug, Web-App, selbst gehosteter Dienst – z. B. Projektseite, Repo, Store-Eintrag), sonst null. Bibliotheken, Frameworks, Artikel und Listen: null. Form: {"name":"…","platforms":[…],"systems":[…]} – name = offizieller Produktname ohne Slogan, Version oder Firmenzusatz; platforms aus pc, smartphone, browser, docker; systems aus windows, linux, mac, android; jeweils nur, was aus den Angaben hervorgeht.
        - parked: nur true, wenn der gegebene Seitentext bzw. die Seitenbeschreibung tatsächlich eine Domain-Verkaufs-, Park- oder Platzhalterseite zeigt oder eindeutig fremden Inhalt, der nicht zum Titel passt (Domain übernommen). Ohne Seitentext immer false; Anmelde-, Captcha- oder Fehlerseiten sind nicht parked. Dann title_* aus dem ursprünglichen Titel bilden, description_* nennt das ursprüngliche Thema laut Titel und endet mit „(Domain nicht mehr aktiv)“ bzw. „(domain no longer active)“, summary_* leer lassen.
    """.trimIndent()

    val SYSTEM_CLASSIFY = """
        Du ordnest ein Lesezeichen in eine bestehende Sammlung ein. Antworte ausschließlich mit JSON:
        {"tags":[{"en":"…","de":"…"}],"folder":"…"}
        - tags: 2–5 Schlagwörter. "en": englisch, kleingeschrieben, Einzahl, 1–2 Wörter (Technologie, Thema, Zweck). "de": die deutsche Entsprechung, kleingeschrieben (bei Eigennamen/Fachbegriffen wie „kotlin“ identisch). Bevorzugt vorhandene Tags verwenden. Keine Domainnamen.
        - folder: der passendste Ordnerpfad exakt wie in „Ordner-Kandidaten“. Passt keiner, neuer Pfad: vorhandener Pfad + " / " + kurzer neuer Name.
    """.trimIndent()

    fun userTexts(input: AiInput): String = buildString {
        appendLine("URL: ${input.url}")
        appendLine("Titel: ${input.title}")
        input.metaTitle?.takeIf { it.isNotBlank() && it != input.title }?.let { appendLine("Titel laut Seite: $it") }
        input.description?.takeIf { it.isNotBlank() }?.let { appendLine("Beschreibung (Nutzer): ${it.take(500)}") }
        input.metaDescription?.takeIf { it.isNotBlank() && it != input.description }?.let { appendLine("Beschreibung (Seite): ${it.take(500)}") }
        input.stars?.let { appendLine("GitHub-Stars: $it") }
        if (input.dead) appendLine("Hinweis: Die Seite existiert nicht mehr (HTTP 404 bzw. Domain nicht mehr vorhanden).")
        if (input.blocked) appendLine(
            "Hinweis: Der Inhalt konnte nicht abgerufen werden (Zugriff blockiert, Anmeldung nötig oder nur lokal erreichbar). " +
                "Die Seite existiert vermutlich noch: nur aus Titel und URL beschreiben, summary leer lassen, nicht als „nicht mehr aktiv“ bezeichnen, parked=false.",
        )
        input.readme?.let { appendLine().appendLine("README (Auszug):").appendLine(cleanReadme(it).take(3500)) }
        input.text?.takeIf { input.readme == null }?.let { appendLine().appendLine("Seitentext (Auszug):").appendLine(it.take(3500)) }
    }

    fun userClassify(input: AiInput, texts: AiTexts, candidates: List<String>, topLevel: List<String>, vocabulary: List<String>): String = buildString {
        appendLine("URL: ${input.url}")
        appendLine("Titel: ${texts.titleEn ?: texts.titleDe ?: input.title}")
        (texts.descEn ?: texts.descDe)?.let { appendLine("Beschreibung: $it") }
        (texts.summaryEn ?: texts.summaryDe)?.takeIf { it.isNotBlank() }?.let { appendLine("Zusammenfassung: $it") }
        texts.type?.let { appendLine("Typ: $it") }
        if (texts.parked || input.dead) appendLine("Hinweis: Die Seite existiert nicht mehr – Tags nach dem ursprünglichen Thema laut Titel „${input.title}“ vergeben.")
        input.currentFolder?.takeIf { it.isNotBlank() }?.let { appendLine("Aktueller Ordner: $it") }
        appendLine()
        appendLine("Ordner-Kandidaten:")
        candidates.forEach { appendLine("- $it") }
        if (topLevel.isNotEmpty()) appendLine("Oberste Ordner: ${topLevel.joinToString(" | ")}")
        appendLine()
        appendLine("Vorhandene Tags: ${if (vocabulary.isEmpty()) "(noch keine)" else vocabulary.joinToString(", ")}")
    }

    /** Remove badges, images and HTML from a README – costs only tokens. */
    fun cleanReadme(md: String): String = md
        .replace(Regex("""\[!\[[^\]]*]\([^)]*\)]\([^)]*\)"""), "")
        .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
        .replace(Regex("""<[^>]{1,300}>"""), "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** First JSON object of the response (also in ```json blocks or with leading/trailing text). */
    private fun obj(text: String): JsonObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.str(k: String, max: Int): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.take(max)

    fun parseTexts(text: String): AiTexts? {
        val o = obj(text) ?: return null
        val t = AiTexts(
            titleDe = o.str("title_de", 140), titleEn = o.str("title_en", 140),
            descDe = o.str("description_de", 400), descEn = o.str("description_en", 400),
            summaryDe = o.str("summary_de", 1200), summaryEn = o.str("summary_en", 1200),
            type = o.str("type", 20)?.lowercase()?.takeIf { it in TYPES },
            parked = (o["parked"] as? JsonPrimitive)?.contentOrNull?.toBoolean() == true,
            app = (o["app"] as? JsonObject)?.let { a ->
                val name = a.str("name", 80) ?: return@let null
                fun list(k: String, allowed: Set<String>) = (a[k] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() }
                    .map { when (it) { "macos", "osx" -> "mac"; "web", "extension" -> "browser"; "desktop" -> "pc"; "mobile" -> "smartphone"; else -> it } }
                    .filter { it in allowed }.distinct()
                AiApp(name, list("platforms", setOf("pc", "smartphone", "browser", "docker")), list("systems", setOf("windows", "linux", "mac", "android")))
            },
        )
        // the caller fills empty titles with the original title; with no content at all the response is unusable
        return t.takeIf { listOfNotNull(it.titleDe, it.titleEn, it.descDe, it.descEn).isNotEmpty() || o.containsKey("title_de") }
    }

    /** Normalize tags; assign folders with the same spelling (apart from case) to the candidate. */
    fun parseClassification(text: String, candidates: List<String> = emptyList()): AiClassification? {
        val o = obj(text) ?: return null
        val tags = when (val t = o["tags"]) {
            is JsonArray -> t.mapNotNull { el ->
                when (el) {
                    is JsonObject -> {
                        val en = (el["en"] as? JsonPrimitive)?.contentOrNull?.let(::normalizeTag)
                        val de = (el["de"] as? JsonPrimitive)?.contentOrNull?.let(::normalizeTag)
                        (en ?: de)?.let { AiTag(it, de ?: it) }
                    }
                    is JsonPrimitive -> el.contentOrNull?.let(::normalizeTag)?.let { AiTag(it, it) }
                    else -> null
                }
            }
            is JsonPrimitive -> t.contentOrNull?.split(',').orEmpty().map(::normalizeTag).map { AiTag(it, it) }
            else -> emptyList()
        }.filter { it.en.length in 2..32 }.distinctBy { it.en }.take(6)
        val folder = o.str("folder", 300)?.let { f ->
            val norm = Library.normPath(f)
            candidates.firstOrNull { Library.normPath(it) == norm }
                ?: f.split('/').map { it.trim() }.filter { it.isNotEmpty() }.take(5).joinToString(" / ").takeIf { it.isNotEmpty() }
        }
        return AiClassification(tags, folder)
    }

    fun normalizeTag(t: String): String = t.trim().removePrefix("#").trim().lowercase().replace(Regex("\\s+"), " ")
}
