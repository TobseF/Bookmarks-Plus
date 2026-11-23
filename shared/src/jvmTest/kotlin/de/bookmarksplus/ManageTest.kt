package de.bookmarksplus

import de.bookmarksplus.data.BookmarkRepository
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.Filter
import de.bookmarksplus.data.FilterEngine
import de.bookmarksplus.data.Library
import de.bookmarksplus.netscape.NetscapeParser
import de.bookmarksplus.netscape.NetscapeWriter
import de.bookmarksplus.netscape.NsLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManageTest {

    private val html = """
        <DL><p>
          <DT><H3>Technik</H3>
          <DL><p>
            <DT><H3>Alt</H3>
            <DL><p>
              <DT><A HREF="https://a.example/">A</A>
              <DT><A HREF="https://b.example/">B</A>
            </DL><p>
            <DT><A HREF="https://b.example/">B</A>
          </DL><p>
          <DT><A HREF="https://c.example/" ADD_DATE="1">C</A>
        </DL><p>
    """.trimIndent()

    private fun setup(): Pair<BookmarkRepository, () -> Library> {
        val (_, db) = DatabaseFactory.open(null)
        val repo = BookmarkRepository(db)
        repo.import(NetscapeParser.parse(html))
        return repo to { Library.load(db) }
    }

    @Test
    fun trashAndRestoreKeepsAllPlacements() {
        val (repo, load) = setup()
        var lib = load()
        val b = lib.items.first { it.title == "B" }
        assertEquals(2, lib.placementsByItem[b.id]!!.size)

        repo.trash(listOf(b.id))
        lib = load()
        assertNull(lib.itemsById[b.id])
        assertNotNull(lib.trashById[b.id])
        assertEquals(listOf(b.id), FilterEngine.apply(lib, Filter(trash = true), 0).map { it.id })
        assertFalse("b.example" in NetscapeWriter.write(repo.export()), "Papierkorb wird nicht exportiert")

        repo.restore(listOf(b.id))
        lib = load()
        assertEquals(2, lib.placementsByItem[b.id]!!.size)
        assertTrue(lib.trash.isEmpty())
    }

    @Test
    fun deleteFolderTrashesOnlyExclusiveBookmarksAndRestoresPath() {
        val (repo, load) = setup()
        var lib = load()
        val alt = assertNotNull(lib.folderByPath("Technik / Alt"))
        val a = lib.items.first { it.title == "A" }
        val b = lib.items.first { it.title == "B" }

        assertEquals(1, repo.deleteFolder(alt), "nur A lag ausschließlich dort")
        lib = load()
        assertNull(lib.folderByPath("Technik / Alt"))
        assertEquals("Technik / Alt", lib.trashById[a.id]!!.trashPath)
        assertEquals(listOf(lib.folderByPath("Technik")), lib.placementsByItem[b.id]!!.map { it.folderId }, "B bleibt in Technik")

        repo.restore(listOf(a.id))
        lib = load()
        val restoredFolder = assertNotNull(lib.folderByPath("Technik / Alt"), "Ordner wird wieder angelegt")
        assertEquals(listOf(restoredFolder), lib.placementsByItem[a.id]!!.map { it.folderId })
        assertNull(lib.itemsById[a.id]!!.trashPath)
    }

    @Test
    fun editTextsAndDeleteForever() {
        val (repo, load) = setup()
        var lib = load()
        val c = lib.items.first { it.title == "C" }
        repo.edit(c.id, title = "Neu & besser", url = "https://www.d.example/x", description = "Zeile 1\nZeile <2>", summary = "Kurz", readme = "# Titel")
        lib = load()
        val e = lib.itemsById[c.id]!!
        assertEquals("Neu & besser", e.title)
        assertEquals("d.example", e.domain)
        assertEquals("Zeile 1\nZeile <2>", e.description, "Beschreibung übersteht Kodierung")
        assertEquals("Kurz", e.summary)
        assertTrue(e.hasReadme)
        val link = repo.export().walk().filterIsInstance<NsLink>().first { it.href == "https://www.d.example/x" }
        assertEquals("Neu & besser", link.title)
        assertTrue("Neu &amp; besser" in NetscapeWriter.write(repo.export()))

        repo.edit(c.id, description = "")
        assertNull(load().itemsById[c.id]!!.description)

        repo.trash(listOf(c.id))
        assertEquals(1, repo.emptyTrash())
        assertNull(load().anyItem(c.id))
    }

    @Test
    fun foldersCreateRenameMove() {
        val (repo, load) = setup()
        var lib = load()
        val technik = lib.folderByPath("Technik")!!
        val alt = lib.folderByPath("Technik / Alt")!!
        val neu = repo.createFolder(lib.rootId!!, "Neu")
        repo.renameFolder(neu, "Archiv")
        repo.moveFolder(alt, neu)
        repo.moveFolder(neu, alt) // cycle → ignored
        lib = load()
        assertNotNull(lib.folderByPath("Archiv / Alt"))
        assertEquals(lib.rootId, lib.folders[neu]!!.parentId)
        assertTrue(lib.childrenOf(technik).none { it.id == alt })
    }
}
