package de.bookmarksplus

import de.bookmarksplus.data.BookmarkRepository
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.netscape.NetscapeParser
import de.bookmarksplus.netscape.NetscapeWriter
import de.bookmarksplus.netscape.NsFolder
import de.bookmarksplus.netscape.NsLink
import de.bookmarksplus.util.Urls
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NetscapeRoundtripTest {

    private val sample: File? = System.getProperty("bookmarks.sample")?.let(::File)?.takeIf { it.isFile }

    private fun firstDiff(a: String, b: String): String {
        val la = a.lines(); val lb = b.lines()
        val i = la.indices.firstOrNull { it >= lb.size || la[it] != lb[it] } ?: return "lengths ${la.size} vs ${lb.size}"
        return "line ${i + 1}:\n  expected: ${la[i].take(300)}\n  actual:   ${lb.getOrNull(i)?.take(300)}"
    }

    @Test
    fun parserCountsMatchRealExport() {
        val file = sample ?: return println("sample missing, skipped")
        val tree = NetscapeParser.parse(file.readText())
        val nodes = tree.walk().toList()
        assertEquals(5917, nodes.count { it is NsLink })
        assertEquals(1207, nodes.count { it is NsFolder })
    }

    @Test
    fun parseWriteIsByteIdentical() {
        val file = sample ?: return println("sample missing, skipped")
        val original = file.readText()
        val written = NetscapeWriter.write(NetscapeParser.parse(original))
        assertTrue(original == written, firstDiff(original, written))
    }

    @Test
    fun databaseRoundtripIsByteIdentical() {
        val file = sample ?: return println("sample missing, skipped")
        val original = file.readText()
        val (driver, db) = DatabaseFactory.open(null)
        driver.use {
            val repo = BookmarkRepository(db)
            val t0 = System.nanoTime()
            val result = repo.import(NetscapeParser.parse(original))
            val t1 = System.nanoTime()
            val exported = NetscapeWriter.write(repo.export())
            val t2 = System.nanoTime()
            println("import ${(t1 - t0) / 1_000_000} ms, export ${(t2 - t1) / 1_000_000} ms, $result, ${repo.stats()}")
            assertEquals(5917, result.links)
            assertEquals(5675L, repo.stats().bookmarks)
            assertEquals(5917L, repo.stats().placements)
            assertTrue(original == exported, firstDiff(original, exported))
        }
    }

    @Test
    fun handlesDdAndDuplicatesWithDifferentAttributes() {
        val html = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <H1>Bookmarks Menu</H1>
            <DL><p>
                <DT><H3 ADD_DATE="1" LAST_MODIFIED="2">Dev</H3>
                <DL><p>
                    <DT><A HREF="https://a.example/?x=1&y=2" ADD_DATE="10" LAST_VISIT="11" TAGS="k">A &amp; B</A>
                    <DD>Firefox &lt;Beschreibung&gt;
                </DL><p>
                <DT><A HREF="https://a.example/?x=1&y=2" ICON="data:image/png;base64,AAAA" ADD_DATE="12">Anderer Titel</A>
            </DL><p>
        """.trimIndent()
        val parsed = NetscapeParser.parse(html)
        val expected = NetscapeWriter.write(parsed)
        val (driver, db) = DatabaseFactory.open(null)
        driver.use {
            val repo = BookmarkRepository(db)
            repo.import(parsed)
            assertEquals(1L, repo.stats().bookmarks)
            assertEquals(expected, NetscapeWriter.write(repo.export()))
            val link = (parsed.children[0] as NsFolder).children[0] as NsLink
            assertEquals("A & B", link.title)
            assertEquals("Firefox <Beschreibung>", link.dd)
        }
    }

    @Test
    fun deletingPrimaryPlacementKeepsLinks() {
        val html = """
            <DL><p>
                <DT><H3>X</H3>
                <DL><p><DT><A HREF="https://x.example/">x</A></DL><p>
                <DT><H3>Y</H3>
                <DL><p><DT><A HREF="https://x.example/">x</A></DL><p>
            </DL><p>
        """.trimIndent()
        val (driver, db) = DatabaseFactory.open(null)
        driver.use {
            val repo = BookmarkRepository(db)
            repo.import(NetscapeParser.parse(html))
            val placements = db.bookmarkQueries.placementsOfBookmark(1).executeAsList()
            assertEquals(2, placements.size)
            repo.deletePlacement(placements.first { it.is_primary == 1L }.id)
            val rest = db.bookmarkQueries.placementsOfBookmark(1).executeAsList()
            assertEquals(1, rest.size)
            assertEquals(1L, rest.single().is_primary)
            assertEquals(1L, repo.stats().bookmarks)
            repo.deletePlacement(rest.single().id)
            assertEquals(0L, repo.stats().bookmarks) // in the trash
            assertNotNull(db.bookmarkQueries.byId(1).executeAsOneOrNull()?.deleted_at)
        }
    }

    @Test
    fun urlHelpers() {
        assertEquals("http://github.com/foo/bar", Urls.normalize("https://www.GitHub.com/foo/bar/?utm_source=x#readme"))
        assertEquals("github.com", Urls.domain("https://www.github.com/foo"))
        assertEquals("foo" to "bar", Urls.githubRepo("https://github.com/foo/bar.git"))
        assertEquals(null, Urls.githubRepo("https://github.com/topics/kotlin"))
    }
}
