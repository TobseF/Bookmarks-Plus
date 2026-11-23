package de.bookmarksplus.sync

import de.bookmarksplus.server.Passwords
import de.bookmarksplus.server.ServerConfig
import de.bookmarksplus.server.bookmarksServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoresTest {

    private fun tmp(): File = Files.createTempDirectory("bmstore").toFile()
    private fun freePort() = ServerSocket(0).use { it.localPort }

    /** Common contract of all targets. */
    private fun exercise(store: BlobStore) {
        store.put("changes/0000000000001-a.bin", byteArrayOf(1, 2, 3))
        store.put("changes/0000000000002-b.bin", ByteArray(300_000) { 7 })
        store.put("keyinfo.json", "{}".toByteArray())
        assertEquals(listOf("changes/0000000000001-a.bin", "changes/0000000000002-b.bin"), store.list("changes/"))
        assertTrue("keyinfo.json" in store.list(""))
        assertEquals(300_000, store.get("changes/0000000000002-b.bin")!!.size)
        store.put("changes/0000000000001-a.bin", byteArrayOf(9)) // overwrite
        assertTrue(store.get("changes/0000000000001-a.bin")!!.contentEquals(byteArrayOf(9)))
        store.delete("changes/0000000000001-a.bin")
        assertNull(store.get("changes/0000000000001-a.bin"))
        assertEquals(listOf("changes/0000000000002-b.bin"), store.list("changes/"))
        assertFailsWith<IllegalArgumentException> { store.put("../evil", byteArrayOf()) }
        store.close()
    }

    @Test
    fun folderStore() = exercise(FolderStore(tmp()))

    @Test
    fun serverStore() {
        val port = freePort()
        val data = tmp()
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            bookmarksServer(ServerConfig(data, mapOf("anna" to Passwords.hash("geheim", iterations = 1000), "ben" to Passwords.hash("x", 1000))))
        }.start(wait = false)
        try {
            exercise(ServerStore("http://127.0.0.1:$port", "anna", "geheim"))
            assertTrue(File(data, "anna/changes/0000000000002-b.bin").isFile)
            assertEquals(emptyList(), ServerStore("http://127.0.0.1:$port", "ben", "x").list("changes/"), "Users are separated")
            val e = assertFailsWith<SyncTargetException> { ServerStore("http://127.0.0.1:$port", "anna", "falsch").list("") }
            assertTrue("password" in e.message!!)
        } finally {
            server.stop(100, 1000)
        }
    }

    @Test
    fun ftpStore() {
        val port = freePort()
        val home = tmp()
        val factory = FtpServerFactory()
        factory.addListener("default", ListenerFactory().apply { this.port = port; serverAddress = "127.0.0.1" }.createListener())
        val um = PropertiesUserManagerFactory().apply { file = File(tmp(), "users.properties").apply { createNewFile() } }.createUserManager()
        um.save(BaseUser().apply { name = "u"; password = "p"; homeDirectory = home.absolutePath; authorities = listOf(WritePermission()) })
        factory.userManager = um
        val server = factory.createServer()
        server.start()
        try {
            exercise(FtpStore("127.0.0.1", port, "u", "p", baseDir = "/sync", tls = false))
            assertTrue(File(home, "sync/changes/0000000000002-b.bin").isFile)
            assertFailsWith<SyncTargetException> { FtpStore("127.0.0.1", port, "u", "falsch", tls = false).list("") }
        } finally {
            server.stop()
        }
    }
}
