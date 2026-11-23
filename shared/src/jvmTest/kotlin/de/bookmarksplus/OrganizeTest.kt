package de.bookmarksplus

import de.bookmarksplus.data.BookmarkRepository
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.Filter
import de.bookmarksplus.data.FilterEngine
import de.bookmarksplus.data.FolderMatcher
import de.bookmarksplus.data.Library
import de.bookmarksplus.netscape.NetscapeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrganizeTest {

    private val html = """
        <DL><p>
          <DT><H3>Entwicklung</H3>
          <DL><p>
            <DT><H3>Kotlin</H3>
            <DL><p>
              <DT><A HREF="https://kotlinlang.org/docs/coroutines-guide.html">Kotlin Coroutines Guide</A>
              <DT><A HREF="https://kotlinlang.org/docs/multiplatform.html">Kotlin Multiplatform</A>
              <DT><A HREF="https://ktor.io/">Ktor Kotlin server framework</A>
            </DL><p>
            <DT><H3>Python</H3>
            <DL><p>
              <DT><A HREF="https://docs.python.org/3/library/asyncio.html">Python asyncio</A>
              <DT><A HREF="https://pandas.pydata.org/">pandas Python data analysis</A>
            </DL><p>
          </DL><p>
          <DT><H3>Kochen</H3>
          <DL><p>
            <DT><A HREF="https://www.chefkoch.de/rezepte/1.html">Rezept Lasagne</A>
          </DL><p>
          <DT><A HREF="https://example.org/kotlin-flow">Kotlin Flow Tutorial</A>
        </DL><p>
    """.trimIndent()

    private fun setup(): Triple<BookmarkRepository, de.bookmarksplus.db.BookmarksDb, () -> Library> {
        val (_, db) = DatabaseFactory.open(null)
        val repo = BookmarkRepository(db)
        repo.import(NetscapeParser.parse(html))
        return Triple(repo, db) { Library.load(db) }
    }

    @Test
    fun folderMatcherFindsTopicalFolder() {
        val (_, _, load) = setup()
        val lib = load()
        val item = lib.items.first { it.url.endsWith("kotlin-flow") }
        val best = FolderMatcher(lib).candidates(item, 3).map { lib.pathString(it) }
        assertEquals("Entwicklung / Kotlin", best.first(), best.toString())
    }

    @Test
    fun moveLinkAndCreateFolderPath() {
        val (repo, db, load) = setup()
        var lib = load()
        val item = lib.items.first { it.url.endsWith("kotlin-flow") }
        val kotlin = assertNotNull(lib.folderByPath("entwicklung / KOTLIN"))

        repo.moveTo(item.id, kotlin)
        lib = load()
        assertEquals(listOf(kotlin), lib.placementsByItem[item.id]!!.map { it.folderId })

        val newId = repo.ensureFolderPath("Entwicklung / Kotlin / Flow")
        assertEquals(newId, repo.ensureFolderPath("Entwicklung/Kotlin/flow"), "vorhandener Pfad wird wiederverwendet")
        repo.addPlacement(item.id, newId)
        lib = load()
        val ps = lib.placementsByItem[item.id]!!
        assertEquals(2, ps.size)
        assertEquals(kotlin, ps.single { it.primary }.folderId)

        // move into the folder that already has a link → it becomes primary, the old one is dropped
        repo.moveTo(item.id, newId)
        lib = load()
        assertEquals(listOf(newId), lib.placementsByItem[item.id]!!.map { it.folderId })
        assertTrue(lib.placementsByItem[item.id]!!.single().primary)

        // export contains the new folder
        val exported = de.bookmarksplus.netscape.NetscapeWriter.write(repo.export())
        assertTrue("<H3" in exported && ">Flow</H3>" in exported)
        db.aiQueries.setAiFolder("Entwicklung / Kotlin", item.id)
        lib = load()
        assertEquals("Entwicklung / Kotlin", lib.openFolderSuggestion(lib.itemsById[item.id]!!))
    }

    @Test
    fun suggestionFilterAndOperators() {
        val (_, db, load) = setup()
        val id = load().items.first { it.url.endsWith("kotlin-flow") }.id
        db.aiQueries.setAiResult("Kotlin Flow – Einführung", "Erklärt Flows.", Library.encodeTagList(listOf("kotlin", "coroutines")), null, 1, "article", id)
        val lib = load()
        val now = System.currentTimeMillis() / 1000
        assertEquals(listOf(id), FilterEngine.apply(lib, Filter(onlySuggested = true), now).map { it.id })
        assertEquals(listOf(id), FilterEngine.apply(lib, Filter(text = "is:ai"), now).map { it.id })
        assertEquals(lib.items.size - 1, FilterEngine.apply(lib, Filter(text = "is:noai"), now).size)
        assertEquals(listOf(id), FilterEngine.apply(lib, Filter(text = "type:article"), now).map { it.id })
        assertEquals(listOf(id), FilterEngine.apply(lib, Filter(text = "flows"), now).map { it.id }, "Zusammenfassung ist durchsuchbar")
        assertEquals("Kotlin Flow – Einführung", lib.itemsById[id]!!.displayTitle)
    }
}
