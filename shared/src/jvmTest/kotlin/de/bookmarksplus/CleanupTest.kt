package de.bookmarksplus

import de.bookmarksplus.data.BookmarkRepository
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.Filter
import de.bookmarksplus.data.FilterEngine
import de.bookmarksplus.data.Item
import de.bookmarksplus.data.Library
import de.bookmarksplus.data.LinkHealth
import de.bookmarksplus.data.Period
import de.bookmarksplus.data.SavedSearch
import de.bookmarksplus.data.SavedSearchRepository
import de.bookmarksplus.data.SettingsRepository
import de.bookmarksplus.data.TagRepository
import de.bookmarksplus.netscape.NetscapeParser
import de.bookmarksplus.netscape.NetscapeWriter
import de.bookmarksplus.util.Urls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleanupTest {

    @Test
    fun normalizeCoversIndexFilesAndParamOrder() {
        assertEquals(Urls.normalize("https://www.x.org/docs/"), Urls.normalize("http://x.org/docs/index.html"))
        assertEquals(Urls.normalize("https://x.org/?b=2&a=1"), Urls.normalize("https://x.org/?a=1&b=2&utm_source=t"))
        assertFalse(Urls.isMeaningfulRedirect("http://x.org/a", "https://www.x.org/a/"))
        assertTrue(Urls.isMeaningfulRedirect("http://x.org/a", "https://y.org/a"))
        assertTrue(Urls.redirectsToHome("https://x.org/old/page", "https://x.org/"))
        assertFalse(Urls.redirectsToHome("https://x.org/", "https://x.org/de"))
    }

    @Test
    fun healthClassification() {
        fun item(status: Long?, error: String? = null, final: String? = null) =
            Item(1, "https://x.org/a", "", null, null, null, "x.org", null, null, null, null, null, null, null, null, null, null, null, null, null,
                status, null, null, null, "", finalUrl = final, linkError = error)
        assertEquals(LinkHealth.Blocked, item(403).health)
        assertFalse(item(403).isDead, "Bot-Schutz ist kein toter Link")
        assertEquals(LinkHealth.Dead, item(404).health)
        assertEquals(LinkHealth.Dead, item(0, "dns").health)
        assertEquals(LinkHealth.Unreachable, item(0, "timeout").health)
        assertTrue(item(0, "timeout").isDead)
        assertEquals(LinkHealth.Ok, item(200, final = "https://www.x.org/a/").health)
        assertEquals(LinkHealth.Redirect, item(200, final = "https://neu.example/a").health)
        assertEquals(LinkHealth.Error, item(503).health)
    }

    @Test
    fun mergeDuplicatesKeepsFoldersTitlesAndTags() {
        val (_, db) = DatabaseFactory.open(null)
        val repo = BookmarkRepository(db)
        repo.import(
            NetscapeParser.parse(
                """<DL><p>
                   <DT><H3>A</H3><DL><p><DT><A HREF="https://x.org/docs/" ADD_DATE="100">Docs</A></DL><p>
                   <DT><H3>B</H3><DL><p><DT><A HREF="http://www.x.org/docs/index.html" ADD_DATE="50">X Doku</A>
                   <DT><A HREF="https://y.org/">Y</A></DL><p>
                   </DL><p>""",
            ),
        )
        repo.renormalize()
        var lib = Library.load(db)
        assertEquals(1, lib.duplicateGroups.size)
        val group = lib.duplicateGroups.values.single()
        val (keep, other) = group[1] to group[0] // keep the younger one to check the add_date adoption
        val tags = TagRepository(db)
        tags.add(listOf(other.id), tags.getOrCreate("doku", 0xFF00FF00))
        assertEquals(group.map { it.id }.toSet(), FilterEngine.apply(lib, Filter(onlyDuplicates = true), 0).map { it.id }.toSet())

        repo.merge(keep.id, listOf(other.id))
        lib = Library.load(db)
        assertTrue(lib.duplicateGroups.isEmpty())
        val merged = lib.itemsById[keep.id]!!
        assertEquals(50, merged.addDate, "ältestes Datum bleibt")
        assertEquals(setOf("A", "B"), lib.placementsByItem[keep.id]!!.map { lib.folders[it.folderId]!!.title }.toSet())
        assertEquals(setOf("doku"), lib.tagsOf[keep.id]!!.map { lib.tagsById[it]!!.name }.toSet())
        assertTrue(other.id in lib.trashById)
        // export: both folders contain the link, the second with its original title
        val out = NetscapeWriter.write(repo.export())
        assertTrue(">${keep.title}</A>" in out && ">${other.title}</A>" in out, out)
    }

    @Test
    fun addDetectsExistingAndSavedSearchRoundtrip() {
        val (_, db) = DatabaseFactory.open(null)
        val repo = BookmarkRepository(db)
        repo.import(NetscapeParser.parse("""<DL><p><DT><A HREF="https://x.org/a">A</A></DL><p>"""))
        val root = repo.rootFolderId()
        val (id1, created1) = repo.add("http://www.x.org/a/", "", root)
        assertFalse(created1)
        val (id2, created2) = repo.add("https://neu.example/seite", "", root)
        assertTrue(created2)
        assertEquals("neu.example", Library.load(db).itemsById[id2]!!.title)
        assertTrue(id1 != id2)

        val saved = SavedSearchRepository(SettingsRepository(db))
        val f = Filter(text = "kotlin #dev", minStars = 1000, added = Period.Year, onlyRepos = true, tagIds = setOf(3, 4))
        saved.save(listOf(SavedSearch("1", "Kotlin-Repos", f)))
        assertEquals(f, saved.list().single().filter)
    }
}
