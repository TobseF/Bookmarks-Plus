package de.bookmarksplus.desktop

import de.bookmarksplus.data.DatabaseFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test

/**
 * Manual end-to-end run of the desktop sync logic (AppState) against a running server – steps one at a time,
 * so that work can be done on the Android device in between. Skipped without `-De2e.step=…`.
 *
 *   gradlew :desktop:test --tests "*E2eSyncRun*" -De2e.step=setup -De2e.dir=<folder> -De2e.source=<bookmarks.db>
 *   … -De2e.step=sync   (sync; shows inbox)
 *   … -De2e.step=edit -De2e.title=<new title for the first inbox bookmark>
 *
 * Runs with its own user.home (see build.gradle.kts), so it touches neither real data nor backups.
 */
class E2eSyncRun {
    private val step = System.getProperty("e2e.step").orEmpty()
    private val dir = File(System.getProperty("e2e.dir") ?: "build/e2e")
    private val dbFile get() = File(dir, "desktop/bookmarks.db")
    private val url = System.getProperty("e2e.url") ?: "http://127.0.0.1:8787"

    @Test
    fun run() {
        if (step.isEmpty()) return
        when (step) {
            "setup" -> setup()
            else -> withState { state ->
                when (step) {
                    "sync" -> sync(state)
                    "edit" -> {
                        val inbox = state.inboxFolderId(create = false) ?: error("keine Inbox")
                        val id = state.library.placementsByFolder[inbox].orEmpty().first().bookmarkId
                        state.repo.edit(id, title = System.getProperty("e2e.title") ?: "Vom Desktop geändert")
                        state.reload()
                        sync(state)
                    }
                }
            }
        }
    }

    private fun setup() {
        dbFile.parentFile.mkdirs()
        File(System.getProperty("e2e.source")).copyTo(dbFile, overwrite = true)
        // neutralize the sync configuration of the copy (never address the real target)
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use { it.execute("DELETE FROM setting WHERE key LIKE 'syncCfg.%' OR key LIKE 'sync.%' OR key LIKE 'sync2.%'") }
        }
        withState { state ->
            state.changeSyncTarget(SyncTarget.Server)
            state.changeSyncServer(url, "e2e", "e2e-passwort")
            state.changeSyncInterval(0)
            val t0 = System.currentTimeMillis()
            state.setPassphrase(System.getProperty("e2e.pass") ?: "e2e-passphrase-123")
            waitIdle(state)
            println("E2E Schlüssel: ${state.syncKeyReady} (${System.currentTimeMillis() - t0} ms)")
            sync(state)
        }
    }

    private fun sync(state: AppState) {
        val t0 = System.currentTimeMillis()
        runBlocking(Dispatchers.Swing) { state.syncNow() }
        waitIdle(state)
        println("E2E Sync: ${System.currentTimeMillis() - t0} ms – ${state.syncStatus}")
        state.reload()
        val inbox = state.inboxFolderId(create = false)
        val items = inbox?.let { state.library.placementsByFolder[it].orEmpty().mapNotNull { p -> state.library.itemsById[p.bookmarkId] } }.orEmpty()
        println("E2E Inbox (${items.size}): " + items.joinToString(" | ") { "${it.title} <${it.url}> scraped=${it.scrapedAt != null}" })
        println("E2E Lesezeichen gesamt: ${state.library.items.size}")
    }

    private fun waitIdle(state: AppState) = runBlocking {
        delay(200)
        while (state.syncRunning) delay(100)
    }

    private fun withState(block: (AppState) -> Unit) {
        val (driver, db) = DatabaseFactory.open(dbFile)
        val state = AppState(driver, db, File(dir, "desktop/media"), dbFile)
        try {
            block(state)
            // briefly wait for enrichment of shared links (runs in the background)
            runBlocking { delay((System.getProperty("e2e.wait") ?: "0").toLong()) }
        } finally {
            state.close()
        }
    }
}
