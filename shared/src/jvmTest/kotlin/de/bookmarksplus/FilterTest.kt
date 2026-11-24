package de.bookmarksplus

import de.bookmarksplus.data.BookmarkRepository
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.Filter
import de.bookmarksplus.data.FilterEngine
import de.bookmarksplus.data.Library
import de.bookmarksplus.data.ParsedQuery
import de.bookmarksplus.data.Period
import de.bookmarksplus.data.SearchIndex
import de.bookmarksplus.data.SortField
import de.bookmarksplus.data.TagRepository
import de.bookmarksplus.netscape.NetscapeParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilterTest {
    private val now = 1_795_000_000L

    private val html = """
        <DL><p>
            <DT><H3>Dev</H3>
            <DL><p>
                <DT><A HREF="https://github.com/JetBrains/compose-multiplatform" ADD_DATE="${now - 3 * 86400}">Compose MP</A>
                <DT><H3>Tools</H3>
                <DL><p>
                    <DT><A HREF="https://ripgrep.dev/" ADD_DATE="${now - 400 * 86400}" DESCRIPTION="fast grep &amp; search">ripgrep</A>
                </DL><p>
            </DL><p>
            <DT><A HREF="https://www.spiegel.de/" ADD_DATE="0">Spiegel</A>
        </DL><p>
    """.trimIndent()

    private fun setup(): Triple<Library, TagRepository, SearchIndex> {
        val (driver, db) = DatabaseFactory.open(null)
        BookmarkRepository(db).import(NetscapeParser.parse(html))
        val tags = TagRepository(db)
        val t = tags.getOrCreate("kotlin", 0xFFEF5350)
        tags.add(listOf(1L), t)
        val idx = SearchIndex(driver).apply { rebuild() }
        return Triple(Library.load(db), tags, idx)
    }

    @Test
    fun queryOperators() {
        val q = ParsedQuery.parse("#Kotlin domain:www.github.com stars:>1k is:repo grep -old added:2024")
        assertEquals(listOf("kotlin"), q.tags)
        assertEquals("github.com", q.domain)
        assertEquals(1001L, q.minStars)
        assertTrue(q.isRepo)
        assertEquals(listOf("grep"), q.terms)
        assertEquals(listOf("old"), q.excluded)
        assertEquals(2024, q.addedYear)
    }

    @Test
    fun filtersAndSorting() {
        val (lib, _, idx) = setup()
        fun titles(f: Filter, hits: Map<String, Set<Long>> = emptyMap()) = FilterEngine.apply(lib, f, now, hits).map { it.title }

        assertEquals(3, lib.items.size)
        assertEquals(listOf("Compose MP"), titles(Filter(text = "#kotlin")))
        assertEquals(emptyList(), titles(Filter(text = "#gibtsnicht")))
        assertEquals(listOf("Compose MP"), titles(Filter(onlyRepos = true)))
        assertEquals(listOf("Compose MP"), titles(Filter(added = Period.Week)))
        assertEquals(listOf("ripgrep", "Spiegel").sorted(), titles(Filter(added = Period.OlderYear)).sorted())
        // folder incl. subfolders
        val dev = lib.childrenOf(lib.rootId).first { it.title == "Dev" }.id
        assertEquals(listOf("Compose MP", "ripgrep").sorted(), titles(Filter(folderId = dev)).sorted())
        assertEquals(listOf("Compose MP"), titles(Filter(folderId = dev, includeSubfolders = false)))
        // description is cleaned, free text + full text
        assertEquals("fast grep & search", lib.items.first { it.title == "ripgrep" }.description)
        assertEquals(listOf("ripgrep"), titles(Filter(text = "grep")))
        val hits = idx.searchEach(listOf("searc"))
        assertEquals(1, hits.getValue("searc").size)
        assertEquals(listOf("ripgrep"), titles(Filter(text = "searc"), hits))
        assertEquals(listOf("Spiegel"), titles(Filter(text = "domain:spiegel.de")))
        assertEquals(listOf("Compose MP", "ripgrep", "Spiegel"), titles(Filter(sort = SortField.Title, descending = false)))
        assertEquals(listOf("Compose MP", "ripgrep", "Spiegel"), titles(Filter(sort = SortField.Manual, descending = false)))
        assertEquals(2025, FilterEngine.yearOf(1_750_000_000))
    }

    @Test
    fun realLibraryLoadsFast() {
        val file = System.getProperty("bookmarks.sample")?.let(::File)?.takeIf { it.isFile } ?: return
        val (driver, db) = DatabaseFactory.open(null)
        BookmarkRepository(db).import(NetscapeParser.parse(file.readText()))
        val idx = SearchIndex(driver)
        val t0 = System.nanoTime(); idx.rebuild()
        val t1 = System.nanoTime(); val lib = Library.load(db)
        val t2 = System.nanoTime(); val r = FilterEngine.apply(lib, Filter(text = "kotlin"), now, idx.searchEach(listOf("kotlin")))
        val t3 = System.nanoTime()
        println("fts rebuild ${(t1 - t0) / 1e6} ms, load ${(t2 - t1) / 1e6} ms, filter ${(t3 - t2) / 1e6} ms → ${r.size} hits")
        assertEquals(5675, lib.items.size)
        assertTrue(r.isNotEmpty())
    }
}
