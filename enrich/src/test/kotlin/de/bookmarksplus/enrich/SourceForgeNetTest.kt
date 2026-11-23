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

/** Real SourceForge pages: ./gradlew :enrich:test --tests "*SourceForgeNetTest*" -Dnet=true */
class SourceForgeNetTest {
    @Test
    fun enrichesSourceforgeProjects() = runBlocking {
        if (System.getProperty("net") != "true") return@runBlocking println("net tests disabled")
        val out = File(System.getProperty("out.dir"), "sourceforge").apply { deleteRecursively(); mkdirs() }
        val urls = listOf("https://sourceforge.net/projects/bomberclone/", "https://sourceforge.net/projects/7zip/")
        val html = "<DL><p>\n" + urls.joinToString("\n") { "<DT><A HREF=\"$it\" ADD_DATE=\"1700000000\">$it</A>" } + "\n</DL><p>"
        val (driver, db) = DatabaseFactory.open(null)
        BookmarkRepository(db).import(NetscapeParser.parse(html))
        val enricher = Enricher(driver, db, File(out, "media"), { EnrichSettings(thumbnails = true) })
        for (id in 1L..urls.size) enricher.enrich(id)

        val rows = db.bookmarkQueries.viewRows().executeAsList()
        rows.forEach { r ->
            println("${r.url}\n  status=${r.link_status} title=${r.meta_title} stars=${r.gh_stars} author=${r.gh_author} update=${r.gh_last_commit}" +
                " release=${r.gh_last_release}\n  download=${r.download_url} (${r.download_label})\n  shots=${de.bookmarksplus.data.Shots.parse(r.shots_json).size}" +
                " thumb=${r.thumbnail_path} favicon=${r.favicon_hash != null}\n  desc=${r.meta_description?.take(80)}")
        }
        val tags = db.tagQueries.allTags().executeAsList().map { it.name }
        println("tags: $tags")
        val bomber = rows.first { "bomberclone" in it.url }
        assertEquals(200L, bomber.link_status)
        assertEquals(1L, bomber.gh_stars)
        assertEquals(3, de.bookmarksplus.data.Shots.parse(bomber.shots_json).size)
        assertNotNull(bomber.download_url)
        assertNotNull(bomber.thumbnail_path)
        assertTrue("Games" in tags && "Side-Scrolling" in tags, tags.toString())
        enricher.close()
    }
}
