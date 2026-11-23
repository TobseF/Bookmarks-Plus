package de.bookmarksplus.enrich

import de.bookmarksplus.data.BookmarkRepository
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.netscape.NetscapeParser
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real network tests: ./gradlew :enrich:test -Dnet=true (results under build/enrich-test). */
class EnricherNetTest {
    private val enabled = System.getProperty("net") == "true"

    @Test
    fun enrichesRealPages() = runBlocking {
        if (!enabled) return@runBlocking println("net tests disabled")
        val out = File(System.getProperty("out.dir")).apply { deleteRecursively(); mkdirs() }
        val urls = listOf(
            "https://github.com/JetBrains/compose-multiplatform",
            "https://github.com/BurntSushi/ripgrep",
            "https://ktor.io/",
            "https://www.spiegel.de/",
            "https://www.heise.de/",
            "https://www.chefkoch.de/",
            "https://this-domain-does-not-exist-bmplus.example/",
        )
        val html = "<DL><p>\n" + urls.joinToString("\n") { "<DT><A HREF=\"$it\" ADD_DATE=\"1700000000\">$it</A>" } + "\n</DL><p>"
        val (driver, db) = DatabaseFactory.open(null)
        BookmarkRepository(db).import(NetscapeParser.parse(html))
        val enricher = Enricher(driver, db, File(out, "media"), { EnrichSettings(githubToken = System.getenv("GITHUB_TOKEN")) })
        val t0 = System.currentTimeMillis()
        for (id in 1L..urls.size) {
            val t = System.currentTimeMillis()
            runCatching { enricher.enrich(id) }.onFailure { println("FAIL $id: $it") }
            println("enriched ${urls[(id - 1).toInt()]} in ${System.currentTimeMillis() - t} ms")
        }
        println("total ${System.currentTimeMillis() - t0} ms, screenshot error: ${enricher.screenshotError}")
        enricher.close()

        val rows = db.bookmarkQueries.viewRows().executeAsList().associateBy { it.url }
        rows.values.forEach { r ->
            println("${r.url}\n  status=${r.link_status} title=${r.meta_title} stars=${r.gh_stars} commit=${r.gh_last_commit} release=${r.gh_last_release}" +
                " author=${r.gh_author} org=${r.gh_org} repo=${r.repo_url} web=${r.website_url} type=${r.content_type}" +
                "\n  card=${r.card_image_path?.let { File(it).name }} thumb=${r.thumbnail_path?.let { File(it).name }} favicon=${r.favicon_hash != null} readme=${r.has_readme}")
        }
        val compose = rows.getValue(urls[0])
        assertTrue((compose.gh_stars ?: 0) > 1000, "stars")
        assertNotNull(compose.card_image_path, "github card")
        assertEquals("JetBrains", compose.gh_org)
        assertEquals(true, compose.has_readme)
        val ktor = rows.getValue("https://ktor.io/")
        assertNotNull(ktor.thumbnail_path, "thumbnail")
        assertEquals("https://github.com/ktorio/ktor", ktor.repo_url)
        assertEquals(0L, rows.getValue(urls.last()).link_status)
    }
}
