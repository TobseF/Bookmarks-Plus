package de.bookmarksplus.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.bookmarksplus.db.BookmarksDb
import java.io.File
import java.util.Properties

object DatabaseFactory {
    /** Opens (or creates) the database. `file = null` → in-memory (tests). */
    fun open(file: File?): Pair<SqlDriver, BookmarksDb> {
        file?.parentFile?.mkdirs()
        val url = if (file == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:${file.absolutePath}"
        val props = Properties().apply {
            put("foreign_keys", "true")
            put("journal_mode", "WAL")
            put("synchronous", "NORMAL")
            put("busy_timeout", "30000")
        }
        val driver = JdbcSqliteDriver(url, props, BookmarksDb.Schema)
        SyncTriggers.install(driver)
        return driver to BookmarksDb(driver)
    }
}
