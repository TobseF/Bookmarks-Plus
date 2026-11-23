package de.bookmarksplus.desktop

import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.netscape.NetscapeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Maintenance in the settings: rename/merge tags, switch addresses to https. */
class MaintenanceTest {
    @Test
    fun renameMergeAndHttps() = runBlocking(Dispatchers.Swing) {
        val (driver, db) = DatabaseFactory.open(null)
        val state = AppState(driver, db, Files.createTempDirectory("maint").toFile())
        try {
            state.repo.import(
                NetscapeParser.parse(
                    """<DL><p><DT><H3>A</H3><DL><p>
                    <DT><A HREF="http://example.org/a">A</A>
                    <DT><A HREF="https://example.org/b">B</A>
                    <DT><A HREF="http://localhost:8080/">Lokal</A>
                    <DT><A HREF="http://192.168.1.1/">Router</A>
                    </DL><p></DL><p>""",
                ),
            )
            state.reload()
            val a = state.library.items.first { it.title == "A" }.id
            val b = state.library.items.first { it.title == "B" }.id

            // rename
            state.addNewTag(listOf(a), "kotln")
            val typo = state.library.tags.single().id
            state.renameTag(typo, "Kotlin")
            assertEquals("Kotlin", state.library.tagsById[typo]!!.name)

            // merge: new name already exists
            state.addNewTag(listOf(b), "jvm")
            val jvm = state.library.tags.first { it.name == "jvm" }.id
            state.renameTag(jvm, "kotlin")
            assertNull(state.library.tagsById[jvm])
            assertEquals(setOf(a, b), state.library.tagsOf.filterValues { typo in it }.keys)

            // https: only public http addresses, undo restores them
            assertEquals(listOf("http://example.org/a"), state.httpItems().map { it.url })
            state.upgradeToHttps()
            assertEquals("https://example.org/a", state.library.anyItem(a)!!.url)
            assertTrue(state.library.items.any { it.url == "http://localhost:8080/" })
            assertTrue(state.httpItems().isEmpty())
            state.messageAction!!.second()
            assertEquals("http://example.org/a", state.library.anyItem(a)!!.url)
        } finally {
            state.close()
        }
    }
}
