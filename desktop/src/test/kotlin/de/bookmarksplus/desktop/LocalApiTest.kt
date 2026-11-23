package de.bookmarksplus.desktop

import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.desktop.api.LocalApi
import de.bookmarksplus.netscape.NetscapeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Local interface with real app logic (in-memory DB): pairing, saving, searching, import. */
class LocalApiTest {
    private val http = HttpClient.newHttpClient()

    private fun req(port: Int, method: String, path: String, body: String? = null, token: String? = null, origin: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api$path"))
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
        token?.let { b.header("Authorization", "Bearer $it") }
        origin?.let { b.setHeader("Origin", it) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun field(json: String, name: String): String? = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    @Test
    fun pairSaveSearchImport() = runBlocking(Dispatchers.Swing) {
        val (driver, db) = DatabaseFactory.open(null)
        val state = AppState(driver, db, Files.createTempDirectory("api").toFile())
        state.repo.import(NetscapeParser.parse("""<DL><p><DT><H3>Technik</H3><DL><p><DT><A HREF="https://kotlinlang.org/">Kotlin</A></DL><p></DL><p>"""))
        state.reload()
        state.startApi(port = 47900)
        val port = state.apiPort!!
        try {
            val io = Dispatchers.IO
            // without token: status only
            assertEquals("false", Regex("\"authorized\":(\\w+)").find(withContext(io) { req(port, "GET", "/status") }.body())!!.groupValues[1])
            assertEquals(401, withContext(io) { req(port, "GET", "/search?q=kotlin") }.statusCode())
            // pairing only from extensions
            assertEquals(403, withContext(io) { req(port, "POST", "/pair", "{}") }.statusCode())
            val start = withContext(io) { req(port, "POST", "/pair", """{"client":"Testbrowser"}""", origin = "chrome-extension://abc") }
            assertEquals(200, start.statusCode(), start.body())
            val id = field(start.body(), "requestId")!!
            val code = field(start.body(), "code")!!
            // app shows the same code → consent
            while (state.pairDialog == null) delay(10)
            assertEquals(code, state.pairDialog!!.code)
            assertEquals("pending", field(withContext(io) { req(port, "GET", "/pair/$id") }.body(), "status"))
            state.pairDialog!!.result.complete(true)
            var token: String? = null
            repeat(100) { if (token == null) { token = field(withContext(io) { req(port, "GET", "/pair/$id") }.body(), "token"); delay(20) } }
            assertTrue(token != null)
            assertEquals("unknown", field(withContext(io) { req(port, "GET", "/pair/$id") }.body(), "status"), "Token nur einmal")

            // save to the inbox with tags; second save detects the duplicate
            val save = withContext(io) {
                req(port, "POST", "/bookmarks", """{"url":"https://ktor.io/docs/","title":"Ktor Doku","tags":["kotlin","server"],"description":"Notiz"}""", token)
            }
            assertEquals(200, save.statusCode(), save.body())
            assertTrue("\"created\":true" in save.body() && "Inbox" in save.body(), save.body())
            val again = withContext(io) { req(port, "POST", "/bookmarks", """{"url":"http://www.ktor.io/docs","title":"x"}""", token) }
            assertTrue("\"created\":false" in again.body(), again.body())
            assertEquals(400, withContext(io) { req(port, "POST", "/bookmarks", """{"url":"javascript:alert(1)"}""", token) }.statusCode())

            // look up, change (folder, tags), search
            val look = withContext(io) { req(port, "GET", "/lookup?url=" + java.net.URLEncoder.encode("https://ktor.io/docs/", Charsets.UTF_8), token = token) }.body()
            assertTrue("\"exists\":true" in look && "server" in look, look)
            val itemId = Regex("\"id\":(\\d+)").find(look)!!.groupValues[1]
            val technik = state.library.folderByPath("Technik")!!
            val patched = withContext(io) { req(port, "PATCH", "/bookmarks/$itemId", """{"folderId":$technik,"tags":["kotlin"]}""", token) }.body()
            assertTrue("Technik" in patched && "server" !in patched, patched)
            delay(200)
            val found = withContext(io) { req(port, "GET", "/search?q=ktor", token = token) }.body()
            assertTrue("Ktor Doku" in found, found)
            assertTrue("Technik" in withContext(io) { req(port, "GET", "/folders", token = token) }.body())

            // browser import: new and already existing addresses
            val tree = """{"name":"Testbrowser","root":{"title":"","children":[{"title":"Lesezeichenleiste","children":[
                {"title":"Kotlin","url":"https://kotlinlang.org/","dateAdded":1700000000000},
                {"title":"Neu","url":"https://neu.example/","dateAdded":1700000000000}]}]}}"""
            val imp = withContext(io) { req(port, "POST", "/import", tree, token) }.body()
            assertTrue("\"links\":2" in imp && "\"created\":1" in imp, imp)

            // side panel: tree, folder contents, search in folder, link, trash, images
            delay(200)
            val treeJson = withContext(io) { req(port, "GET", "/tree", token = token) }.body()
            assertTrue("\"title\":\"Technik\"" in treeJson && "\"inboxId\"" in treeJson && "\"count\":2" in treeJson, treeJson)
            val inbox = state.inboxFolderId(create = false)!!
            val items = withContext(io) { req(port, "GET", "/folders/$technik/items", token = token) }.body()
            assertTrue("Kotlin" in items && "Ktor Doku" in items && "\"placementId\"" in items, items)
            assertEquals(404, withContext(io) { req(port, "GET", "/folders/999999/items", token = token) }.statusCode())
            val inFolder = withContext(io) { req(port, "GET", "/search?q=&folder=$technik&sort=title&limit=10", token = token) }.body()
            assertTrue(inFolder.indexOf("Kotlin") in 0 until inFolder.indexOf("Ktor Doku"), inFolder)
            val linked = withContext(io) { req(port, "POST", "/bookmarks/$itemId/link", """{"folderId":$inbox}""", token) }
            assertEquals(200, linked.statusCode(), linked.body())
            delay(200)
            val inboxItems = withContext(io) { req(port, "GET", "/folders/$inbox/items", token = token) }.body()
            assertTrue("Ktor Doku" in inboxItems && "\"linked\":true" in inboxItems, inboxItems)
            assertEquals(404, withContext(io) { req(port, "GET", "/media/$itemId/preview", token = token) }.statusCode())
            assertEquals(200, withContext(io) { req(port, "DELETE", "/bookmarks/$itemId", token = token) }.statusCode())
            delay(200)
            assertTrue("Ktor Doku" !in withContext(io) { req(port, "GET", "/folders/$technik/items", token = token) }.body())
            assertEquals(200, withContext(io) { req(port, "POST", "/bookmarks/$itemId/restore", token = token) }.statusCode())
            delay(200)
            assertTrue("Ktor Doku" in withContext(io) { req(port, "GET", "/folders/$technik/items", token = token) }.body())

            // page jobs for the extension (AlternativeTo): long polling, report result back
            assertEquals(204, withContext(io) { req(port, "GET", "/tasks/next?wait=0", token = token) }.statusCode())
            assertTrue(state.extensionBridge.available, "Abfrage zählt als verbunden")
            val pending = async(io) { state.extensionBridge.fetchHtml("https://alternativeto.net/software/filezilla/about/", 10_000) }
            val task = withContext(io) { req(port, "GET", "/tasks/next?wait=5", token = token) }
            assertEquals(200, task.statusCode(), task.body())
            assertEquals("https://alternativeto.net/software/filezilla/about/", field(task.body(), "url"))
            val taskId = field(task.body(), "id")!!
            assertEquals(204, withContext(io) {
                req(port, "POST", "/tasks/$taskId", """{"status":200,"finalUrl":"https://alternativeto.net/software/filezilla/about/","html":"<html>ok</html>"}""", token)
            }.statusCode())
            assertEquals("<html>ok</html>", pending.await()?.html)
            assertEquals(404, withContext(io) { req(port, "POST", "/tasks/$taskId", """{"status":200}""", token) }.statusCode(), "nur einmal")
            // the bridge does not accept foreign hosts
            assertTrue(runCatching { state.extensionBridge.fetchHtml("https://example.org/", 100) }.isFailure)

            // adopt program page from the browser (AlternativeTo HTML from the extension)
            val hop = state.repo.add("https://github.com/hoppscotch/hoppscotch", "hoppscotch/hoppscotch: Open source API development ecosystem", state.library.folderByPath("Technik")!!).first
            withContext(Dispatchers.Swing) { state.reload() }
            val html = File("../enrich/src/test/resources/alternativeto/postwoman.html").readText()
            val pageJson = kotlinx.serialization.json.buildJsonObject {
                put("url", kotlinx.serialization.json.JsonPrimitive("https://alternativeto.net/software/postwoman/about/"))
                put("html", kotlinx.serialization.json.JsonPrimitive(html))
            }
            val preview = withContext(io) { req(port, "POST", "/apps/page", pageJson.toString(), token) }
            assertEquals(200, preview.statusCode(), preview.body())
            assertTrue("\"name\":\"Hoppscotch\"" in preview.body() && "same repo" in preview.body(), preview.body())
            assertTrue(Regex("\"matches\":\\[\\{\"item\":\\{\"id\":$hop,").containsMatchIn(preview.body()), preview.body())
            val applyJson = kotlinx.serialization.json.JsonObject(pageJson + ("apply" to kotlinx.serialization.json.JsonPrimitive(hop)))
            val applied = withContext(io) { req(port, "POST", "/apps/page", applyJson.toString(), token) }
            assertEquals(200, applied.statusCode(), applied.body())
            assertTrue("\"alternativetoUrl\":\"https://alternativeto.net/software/postwoman/about/\"" in applied.body(), applied.body())
            assertEquals("MIT", de.bookmarksplus.data.Library.load(db).itemsById[hop]!!.app.license)
            assertEquals(422, withContext(io) { req(port, "POST", "/apps/page", """{"url":"https://example.org/"}""", token) }.statusCode())

            // disconnect → token invalid
            state.revokeToken(state.apiTokens.single().hash)
            assertEquals(401, withContext(io) { req(port, "GET", "/tags", token = token) }.statusCode())
        } finally {
            state.close()
        }
    }
}
