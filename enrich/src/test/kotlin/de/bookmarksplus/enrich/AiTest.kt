package de.bookmarksplus.enrich

import com.sun.net.httpserver.HttpServer
import de.bookmarksplus.data.BookmarkRepository
import de.bookmarksplus.data.ContentLang
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.Library
import de.bookmarksplus.enrich.ai.AiConfig
import de.bookmarksplus.enrich.ai.AiPrompt
import de.bookmarksplus.enrich.ai.AiProvider
import de.bookmarksplus.enrich.ai.AiService
import de.bookmarksplus.enrich.ai.AiSettings
import de.bookmarksplus.enrich.ai.AiTag
import de.bookmarksplus.netscape.NetscapeParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiTest {

    @Test
    fun parsesTextsAndClassificationTolerantly() {
        val texts = assertNotNull(
            AiPrompt.parseTexts(
                """Hier: ```json {"title_de":"Ktor – Server-Framework","title_en":"Ktor – server framework","description_de":"Ein Framework.",
                "description_en":"A framework.","summary_de":"","summary_en":"","type":"Tool"} ```""",
            ),
        )
        assertEquals("Ktor – server framework", texts.titleEn)
        assertNull(texts.summaryDe, "leere Zusammenfassung bleibt leer")
        assertEquals("tool", texts.type)
        assertNull(AiPrompt.parseTexts("""keine Antwort"""), "ohne JSON unbrauchbar")
        assertNotNull(AiPrompt.parseTexts("""{"title_de":"","title_en":"","description_de":""}"""), "leere Titel ergänzt der Dienst")

        val cls = assertNotNull(
            AiPrompt.parseClassification(
                """{"tags":[{"en":"#Web Server","de":"Webserver"},{"en":"kotlin","de":"kotlin"},"Docs",{"en":"kotlin","de":"x"}],"folder":"entwicklung / kotlin"}""",
                listOf("Entwicklung / Kotlin"),
            ),
        )
        assertEquals(listOf(AiTag("web server", "webserver"), AiTag("kotlin", "kotlin"), AiTag("docs", "docs")), cls.tags)
        assertEquals("Entwicklung / Kotlin", cls.folder)
    }

    @Test
    fun cleansReadmeBadges() {
        val md = "[![CI](https://x/badge.svg)](https://x)\n![logo](logo.png)\n<p align=center>Hallo</p>\n\n\n\nText"
        assertEquals("Hallo\n\nText", AiPrompt.cleanReadme(md))
    }

    /** Two stages against a local fake provider: texts DE/EN with model A, tags/folders with model B. */
    @Test
    fun runsTwoStagesAgainstFakeProvider() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            requests += body
            val answer = if ("title_de" in body) {
                """{"title_de":"Ktor – Server-Framework für Kotlin","title_en":"Ktor – server framework for Kotlin",
                   "description_de":"Framework für asynchrone Server.","description_en":"Framework for asynchronous servers.",
                   "summary_de":"Ktor baut asynchrone Server.","summary_en":"Ktor builds asynchronous servers.","type":"tool"}"""
            } else {
                """{"tags":[{"en":"web server","de":"webserver"},{"en":"kotlin","de":"kotlin"}],"folder":"Entwicklung / Kotlin"}"""
            }
            val resp = """{"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.JsonPrimitive(answer)}}}],"usage":{"prompt_tokens":1000,"completion_tokens":50}}"""
            val bytes = resp.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val (driver, db) = DatabaseFactory.open(null)
            BookmarkRepository(db).import(
                NetscapeParser.parse(
                    """<DL><p><DT><H3>Entwicklung</H3><DL><p><DT><H3>Kotlin</H3><DL><p>
                       <DT><A HREF="https://kotlinlang.org/">Kotlin</A></DL><p></DL><p>
                       <DT><A HREF="mailto:x@example.org">Ktor</A></DL><p>""",
                ),
            )
            val id = Library.load(db).items.first { it.title == "Ktor" }.id
            val cfg = AiConfig(AiProvider.Custom, "", "text-model", "http://127.0.0.1:${server.address.port}/v1")
            val ai = AiService(driver, db, { AiSettings(cfg, tagModel = "tag-model", autoApplyTags = true, parallel = 2) })
            ai.start(listOf(id))
            withTimeout(15_000) { while (ai.progress.value.done < 1) delay(20) }
            withTimeout(5_000) { while (ai.progress.value.running) delay(20) }
            assertEquals(0, ai.progress.value.errors, ai.progress.value.lastError)
            assertEquals(2100, ai.progress.value.promptTokens + ai.progress.value.completionTokens, "zwei Aufrufe")

            // models per stage
            assertTrue("\"model\":\"text-model\"" in requests[0] && "Seitentext" !in requests[0] || "title_de" in requests[0])
            val tagReq = requests.single { "\"model\":\"tag-model\"" in it }
            assertTrue("Ktor builds asynchronous servers." in tagReq, "Stufe 2 bekommt die englische Zusammenfassung")
            assertTrue("Entwicklung / Kotlin" in tagReq)

            val de = Library.load(db, ContentLang.DE)
            val en = Library.load(db, ContentLang.EN)
            assertEquals("Ktor – Server-Framework für Kotlin", de.itemsById[id]!!.displayTitle)
            assertEquals("Ktor – server framework for Kotlin", en.itemsById[id]!!.displayTitle)
            assertEquals("Ktor builds asynchronous servers.", en.itemsById[id]!!.summary)
            assertEquals("Framework für asynchrone Server.", de.itemsById[id]!!.anyDescription)
            assertEquals("tool", de.itemsById[id]!!.contentType)
            assertEquals("Entwicklung / Kotlin", de.openFolderSuggestion(de.itemsById[id]!!))
            // tags: canonical English, display per language
            assertEquals(setOf("web server", "kotlin"), de.tagsOf[id].orEmpty().map { de.tagsById[it]!!.name }.toSet())
            assertEquals(setOf("webserver", "kotlin"), de.tagsOf[id].orEmpty().map { de.tagsById[it]!!.label }.toSet())
            assertEquals("web server", en.tagLabel("Web Server"))
            assertEquals(0L, db.aiQueries.countPendingAi().executeAsOne() - (de.items.size - 1))
            ai.close()
        } finally {
            server.stop(0)
        }
    }
}
