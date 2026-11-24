package de.bookmarksplus.sync

import de.bookmarksplus.data.BookmarkRepository
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.Library
import de.bookmarksplus.data.TagRepository
import de.bookmarksplus.db.BookmarksDb
import de.bookmarksplus.netscape.NetscapeParser
import de.bookmarksplus.server.Passwords
import de.bookmarksplus.server.ServerConfig
import de.bookmarksplus.server.bookmarksServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {

    private val html = """
        <DL><p>
          <DT><H3>Technik</H3>
          <DL><p>
            <DT><A HREF="https://kotlinlang.org/">Kotlin</A>
            <DT><A HREF="https://ktor.io/">Ktor</A>
          </DL><p>
          <DT><H3>Kochen</H3>
          <DL><p>
            <DT><A HREF="https://www.chefkoch.de/">Chefkoch</A>
            <DT><A HREF="https://kotlinlang.org/">Kotlin</A>
          </DL><p>
        </DL><p>
    """.trimIndent()

    private class Device(val dir: File, val store: () -> RecordStore, val key: ByteArray) {
        val dbFile = File(dir, "bookmarks.db")
        val opened = DatabaseFactory.open(dbFile)
        val db: BookmarksDb get() = opened.second
        val repo = BookmarkRepository(db)
        fun lib() = Library.load(db)
        fun sync(mode: FirstSyncMode = FirstSyncMode.Ask) = store().use { SyncEngine(dbFile, File(dir, "media"), it, key).sync(mode) }
        fun close() = opened.first.close()
    }

    private fun tmp(): File = Files.createTempDirectory("bmsync").toFile().apply { deleteOnExit() }
    private val key = ByteArray(32) { it.toByte() }

    /** Comparable state: folder paths, bookmarks with title/tags/folders. */
    private fun state(lib: Library): String = lib.items.sortedBy { it.url }.joinToString("\n") { i ->
        val folders = lib.placementsByItem[i.id].orEmpty().map { lib.pathString(it.folderId) }.sorted()
        val tags = lib.tagsOf[i.id].orEmpty().map { lib.tagsById[it]!!.name }.sorted()
        "${i.url} | ${i.title} | $folders | $tags | ${i.summary}"
    } + "\nFOLDERS " + lib.folders.keys.filter { it != lib.rootId }.map { lib.pathString(it) }.sorted() +
        "\nTRASH " + lib.trash.map { it.title }.sorted()

    private fun <T> withServer(block: (port: Int, data: File) -> T): T {
        val port = ServerSocket(0).use { it.localPort }
        val data = tmp()
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            bookmarksServer(ServerConfig(data, mapOf("anna" to Passwords.hash("geheim", iterations = 1000))))
        }.start(wait = false)
        try {
            return block(port, data)
        } finally {
            server.stop(100, 1000)
        }
    }

    // ------------------------------------------------------------------------------------------------ Scenario

    /** Two devices via the same target: create, change, move, tags, delete, conflict, trash. */
    private fun converge(store: () -> RecordStore, assertOpaque: () -> Unit) {
        val a = Device(tmp(), store, key)
        val b = Device(tmp(), store, key)
        try {
            a.repo.import(NetscapeParser.parse(html))
            TagRepository(a.db).let { t -> t.add(listOf(a.lib().items.first { it.title == "Ktor" }.id), t.getOrCreate("server", 0xFF112233)) }
            val r1 = a.sync()
            assertTrue(r1.pushed >= 6, "erster Sync lädt alle Einträge hoch: ${r1.pushed}")
            assertOpaque()

            val rb = b.sync() // empty device takes over
            assertTrue(rb.pulled >= 6)
            assertEquals(state(a.lib()), state(b.lib()))
            assertEquals(0, a.sync().pushed, "nichts geändert → nichts zu senden")

            // changes on A: title, tag, create + move folder, delete, rename tag
            val lib = a.lib()
            val ktor = lib.items.first { it.title == "Ktor" }
            val chef = lib.items.first { it.title == "Chefkoch" }
            a.repo.edit(ktor.id, title = "Ktor Framework", summary = "Server in Kotlin")
            val neu = a.repo.ensureFolderPath("Technik / Server")
            a.repo.moveTo(ktor.id, neu)
            a.repo.deleteForever(listOf(chef.id))
            TagRepository(a.db).let { t -> t.update(t.getOrCreate("server", 0), "backend", 0xFF445566) }
            a.sync()
            b.sync()
            assertEquals(state(a.lib()), state(b.lib()))
            assertNull(b.lib().items.firstOrNull { it.title == "Chefkoch" })
            assertEquals(listOf("backend"), b.lib().tags.map { it.name })

            // incremental: one change → one entry
            val kotlinA = a.lib().items.first { it.url == "https://kotlinlang.org/" }.id
            a.repo.edit(kotlinA, title = "Kotlin!")
            assertEquals(1, a.sync().pushed)
            assertEquals(1, b.sync().pulled)

            // conflict: both change the same title, B later → B wins everywhere
            val kotlinB = b.lib().items.first { it.url == "https://kotlinlang.org/" }.id
            a.repo.edit(kotlinA, title = "Kotlin (A)")
            Thread.sleep(5)
            b.repo.edit(kotlinB, title = "Kotlin (B)")
            a.sync()
            val rc = b.sync()
            assertTrue(rc.conflicts >= 1, "Konflikt erkannt")
            a.sync()
            assertEquals("Kotlin (B)", a.lib().itemsById[kotlinA]!!.title)
            assertEquals(state(a.lib()), state(b.lib()))

            // new bookmark on B in a new folder, trash
            val f = b.repo.createFolder(b.lib().rootId!!, "Neu von B")
            val (nid, _) = b.repo.add("https://example.org/b", "Von B", f)
            b.repo.trash(listOf(nid))
            b.sync(); a.sync()
            assertNotNull(a.lib().trash.firstOrNull { it.title == "Von B" })
            assertEquals(state(a.lib()), state(b.lib()))
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun `progress reports totals first and a stopped sync continues later`() {
        val remote = FolderStore(tmp())
        val a = Device(tmp(), { FileRecordStore(remote) }, key)
        val b = Device(tmp(), { FileRecordStore(remote) }, key)
        try {
            a.repo.import(NetscapeParser.parse(html))
            a.sync()
            // B is stopped right after the first progress report → nothing is lost, the next run completes
            val control = SyncControl()
            val seen = ArrayList<SyncProgress>()
            assertFailsWith<SyncCancelledException> {
                FileRecordStore(remote).use {
                    SyncEngine(b.dbFile, File(b.dir, "media"), it, key, control = control, onProgress = { p -> seen += p; if (p.phase == SyncPhase.Fetch && p.done > 0) control.cancel() }).sync()
                }
            }
            assertTrue(seen.any { it.phase == SyncPhase.Fetch && it.done == 0 && it.total >= 5 }, "total is known before the transfer: $seen")
            b.sync()
            assertEquals(state(a.lib()), state(b.lib()))
        } finally { a.close(); b.close() }
    }

    @Test
    fun convergeOverFiles() {
        val remote = FolderStore(tmp())
        converge({ FileRecordStore(remote) }) {
            assertTrue(remote.list("").none { String(remote.get(it)!!, Charsets.ISO_8859_1).contains("kotlinlang") }, "Ziel sieht nur Chiffrat")
            val names = remote.list("items/")
            assertTrue(names.isNotEmpty() && names.all { FILE_NAME.matches(it) }, names.toString())
        }
    }

    @Test
    fun convergeOverServer() = withServer { port, data ->
        converge({ ServerRecordStore("http://127.0.0.1:$port", "anna", "geheim") }) {
            // the server only knows ID, revision, time – no plaintexts, no kinds
            DriverManager.getConnection("jdbc:sqlite:" + File(data, "anna/records.db").absolutePath).use { c ->
                c.createStatement().executeQuery("SELECT id, data FROM rec").use { rs ->
                    var n = 0
                    while (rs.next()) {
                        n++
                        assertTrue(Regex("[0-9a-f-]{36}").matches(rs.getString(1)))
                        assertTrue("kotlin" !in String(rs.getBytes(2), Charsets.ISO_8859_1).lowercase())
                    }
                    assertTrue(n >= 6)
                }
            }
        }
    }

    @Test
    fun serverDetectsConflictsPerRecord() = withServer { port, _ ->
        val s = ServerRecordStore("http://127.0.0.1:$port", "anna", "geheim")
        val id = RecordKeys(key).recordId("bookmark", "x")
        val r1 = s.push(listOf(PushItem(id, null, byteArrayOf(1), 1))).single() as PushResult.Ok
        // second "new" entry with the same ID → conflict
        assertTrue(s.push(listOf(PushItem(id, null, byteArrayOf(2), 2))).single() is PushResult.Conflict)
        // on the current revision → ok; on an old one → conflict
        val r2 = s.push(listOf(PushItem(id, r1.rev, byteArrayOf(3), 3))).single() as PushResult.Ok
        val c = s.push(listOf(PushItem(id, r1.rev, byteArrayOf(4), 4))).single() as PushResult.Conflict
        assertEquals(r2.rev, c.rev)
        // changes since sequence number
        val all = s.changes(null)
        assertEquals(1, all.records.size)
        assertTrue(s.changes(all.cursor).records.isEmpty())
        s.push(listOf(PushItem(id, r2.rev, null, 5)))
        val del = s.changes(all.cursor).records.single()
        assertTrue(del.deleted)
        assertTrue(s.fetch(listOf(id)).isEmpty())
    }

    @Test
    fun firstSyncWithLocalDataMerges() {
        val remote = FolderStore(tmp())
        val a = Device(tmp(), { FileRecordStore(remote) }, key)
        val b = Device(tmp(), { FileRecordStore(remote) }, key)
        try {
            a.repo.import(NetscapeParser.parse(html))
            a.sync()
            b.repo.import(NetscapeParser.parse("""<DL><p><DT><A HREF="https://nur-auf-b.example/">B</A></DL><p>"""))
            b.sync()
            a.sync()
            val urlsA = a.lib().items.map { it.url }.toSet()
            assertTrue("https://nur-auf-b.example/" in urlsA && "https://ktor.io/" in urlsA, urlsA.toString())
            assertEquals(state(a.lib()), state(b.lib()))
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun duplicateInboxesAreMergedConsistently() {
        val remote = FolderStore(tmp())
        val a = Device(tmp(), { FileRecordStore(remote) }, key)
        val b = Device(tmp(), { FileRecordStore(remote) }, key)
        try {
            a.repo.add("https://desktop.example/", "Desktop", a.repo.createFolder(a.repo.rootFolderId(), "Inbox"))
            a.sync()
            Thread.sleep(1100) // younger creation date (seconds) for the inbox on B
            b.repo.add("https://handy.example/", "Handy", b.repo.createFolder(b.repo.rootFolderId(), "Inbox"))
            b.sync()
            assertEquals(1, b.repo.mergeRootFolders("Inbox"))
            b.sync(); a.sync()
            assertEquals(0, a.repo.mergeRootFolders("Inbox"), "A sieht schon nur noch eine Inbox")
            val lib = a.lib()
            val inboxes = lib.childrenOf(lib.rootId).filter { it.title == "Inbox" }
            assertEquals(1, inboxes.size)
            assertEquals(setOf("Desktop", "Handy"), lib.placementsByFolder[inboxes.single().id].orEmpty().map { lib.itemsById[it.bookmarkId]!!.title }.toSet())
            assertEquals(state(a.lib()), state(b.lib()))
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun takeRemoteReplacesLocalAndMediaIsTransferred() {
        val remote = FolderStore(tmp())
        val a = Device(tmp(), { FileRecordStore(remote) }, key)
        val b = Device(tmp(), { FileRecordStore(remote) }, key)
        try {
            a.repo.import(NetscapeParser.parse(html))
            val img = File(a.dir, "thumb.jpg").apply { writeBytes(ByteArray(2000) { (it * 7).toByte() }) }
            val id = a.lib().items.first { it.title == "Ktor" }.id
            a.db.enrichQueries.setThumbnail(img.absolutePath, id)
            a.sync()
            b.repo.import(NetscapeParser.parse("""<DL><p><DT><A HREF="https://lokal.example/">L</A></DL><p>"""))
            val r = b.sync(FirstSyncMode.TakeRemote)
            assertEquals(1, r.mediaDown)
            val lib = b.lib()
            assertTrue(lib.items.none { it.url == "https://lokal.example/" })
            val thumb = lib.items.first { it.title == "Ktor" }.thumbnailPath!!
            assertTrue(File(thumb).readBytes().contentEquals(img.readBytes()))
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun exportImportBlobAndFolder() {
        val a = Device(tmp(), { FileRecordStore(FolderStore(tmp())) }, key)
        val b = Device(tmp(), { FileRecordStore(FolderStore(tmp())) }, key)
        val c = Device(tmp(), { FileRecordStore(FolderStore(tmp())) }, key)
        try {
            a.repo.import(NetscapeParser.parse(html))
            TagRepository(a.db).let { t -> t.add(listOf(a.lib().items.first { it.title == "Ktor" }.id), t.getOrCreate("server", 0xFF112233)) }
            val pass = "export-passphrase".toCharArray()

            // blob
            val blob = File(tmp(), "bookmarks.bmpx")
            val r = Transfer.exportBlob(a.dbFile, blob, pass)
            assertEquals(3, r.bookmarks)
            assertTrue("kotlinlang" !in String(blob.readBytes(), Charsets.ISO_8859_1))
            assertFailsWith<WrongPassphraseException> { Transfer.import(b.dbFile, blob, "falsch".toCharArray()) }
            Transfer.import(b.dbFile, blob, pass)
            assertEquals(state(a.lib()), state(b.lib()))
            assertEquals(0, Transfer.import(b.dbFile, blob, pass).entries, "zweiter Import ändert nichts")

            // file list: one file per entry "<date>_<UUID>.bin", re-export only updates what changed
            val dir = File(tmp(), "export")
            val f1 = Transfer.exportFolder(a.dbFile, dir, pass)
            val files = File(dir, "items").list()!!.toList()
            assertEquals(f1.entries, files.size)
            assertTrue(files.all { FILE_NAME.matches("items/$it") }, files.toString())
            val ktor = a.lib().items.first { it.title == "Ktor" }.id
            a.repo.edit(ktor, title = "Ktor 3")
            val chef = a.lib().items.first { it.title == "Chefkoch" }.id
            a.repo.deleteForever(listOf(chef))
            val f2 = Transfer.exportFolder(a.dbFile, dir, pass)
            assertEquals(1, f2.removed)
            assertTrue(f2.skipped >= f1.entries - 3, "unveränderte Einträge werden nicht neu geschrieben: $f2")
            Transfer.import(c.dbFile, dir, pass)
            assertEquals(state(a.lib()), state(c.lib()))
            assertFailsWith<IllegalArgumentException> { Transfer.exportFolder(a.dbFile, File(tmp(), "x").apply { mkdirs(); File(this, "fremd.txt").writeText("x") }, pass) }

            // imported changes continue to be synchronized
            assertTrue(c.sync().pushed > 0)
        } finally {
            a.close(); b.close(); c.close()
        }
    }

    @Test
    fun cryptoRejectsWrongPassphraseAndTampering() {
        val (info, k) = Crypto.newKeyInfo("richtig".toCharArray())
        assertTrue(Crypto.unlock("richtig".toCharArray(), info).contentEquals(k))
        assertFailsWith<WrongPassphraseException> { Crypto.unlock("falsch".toCharArray(), info) }
        val sealed = Crypto.seal(k, "geheim".repeat(100).toByteArray(), "changes/1-a.bin")
        assertEquals("geheim".repeat(100), String(Crypto.open(k, sealed, "changes/1-a.bin")))
        assertFailsWith<WrongPassphraseException> { Crypto.open(k, sealed, "changes/2-a.bin") } // swapped name
        assertFailsWith<WrongPassphraseException> { Crypto.open(k, sealed.copyOf().also { it[40] = (it[40] + 1).toByte() }, "changes/1-a.bin") }
        assertEquals(info, Crypto.decodeKeyInfo(Crypto.encodeKeyInfo(info)))
        // entries are bound to their ID
        val keys = RecordKeys(k)
        val id1 = keys.recordId("bookmark", "a")
        val id2 = keys.recordId("bookmark", "b")
        val e = keys.seal(id1, Entry("bookmark", "a", 1))
        assertEquals("a", keys.open(id1, e).k)
        assertFailsWith<WrongPassphraseException> { keys.open(id2, e) }
    }

    @Test
    fun backupAndRestore() {
        val d = Device(tmp(), { FileRecordStore(FolderStore(tmp())) }, key)
        val blobs = FolderStore(tmp())
        try {
            d.repo.import(NetscapeParser.parse(html))
            val name = Backups.upload(d.dbFile, blobs, key)
            assertEquals(listOf(name), Backups.listRemote(blobs))
            assertNotNull(Backups.localDaily(d.dbFile, File(d.dir, "backups")))
            assertNull(Backups.localDaily(d.dbFile, File(d.dir, "backups")), "nur eine pro Tag")
            Backups.prepareRemoteRestore(blobs, key, name, d.dbFile)
        } finally {
            d.close()
        }
        assertTrue(Backups.applyPendingRestore(d.dbFile, File(d.dir, "backups")))
        val (drv, db) = DatabaseFactory.open(d.dbFile)
        drv.use { assertEquals(3, Library.load(db).items.size) }
    }

    @Test
    fun secretsRoundtrip() {
        val s = Secrets(File(tmp(), "secret.key"))
        val p = s.protect("sk-123")
        assertTrue(s.isProtected(p) && "sk-123" !in p)
        assertEquals("sk-123", s.reveal(p))
        assertEquals("alt", s.reveal("alt"))
    }

    companion object {
        val FILE_NAME = Regex("""items/\d{8}T\d{9}Z_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(bin|del)""")
    }
}
