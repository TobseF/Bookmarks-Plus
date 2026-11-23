package de.bookmarksplus

import de.bookmarksplus.data.BookmarkRepository
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.Library
import de.bookmarksplus.db.BookmarksDb
import de.bookmarksplus.netscape.NetscapeParser
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {

    @Test
    fun migratesV1DatabaseWithoutLosingData() {
        val file = File.createTempFile("bmplus-v1", ".db").apply { delete(); deleteOnExit() }
        // create current schema and roll back to the v1 state
        DatabaseFactory.open(file).let { (driver, db) ->
            BookmarkRepository(db).import(NetscapeParser.parse("""<DL><p><DT><A HREF="https://a.example/" ADD_DATE="1">A</A></DL><p>"""))
            driver.close()
        }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { st ->
                // v1 state: sync triggers, table and placement UUID did not exist yet
                val triggers = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
                triggers.forEach { st.execute("DROP TRIGGER $it") }
                st.execute("DROP INDEX placement_uuid_idx")
                st.execute("ALTER TABLE placement DROP COLUMN uuid")
                st.execute("DROP TABLE sync_change")
                st.execute("DROP TABLE tag_i18n")
                st.execute("ALTER TABLE bookmark DROP COLUMN meta_title")
                st.execute("ALTER TABLE bookmark DROP COLUMN meta_description")
                st.execute("ALTER TABLE bookmark DROP COLUMN final_url")
                listOf("page_text", "ai_tags", "ai_folder", "ai_at", "trash_path", "link_error", "title_de", "title_en", "desc_de", "desc_en", "summary_de", "summary_en", "ai_models",
                    // v8, v9
                    "screenshot_1", "screenshot_2", "screenshot_3", "download_url", "download_label",
                    "app_name", "app_platforms", "app_systems", "app_license", "pkg_winget", "pkg_choco", "pkg_brew", "pkg_npm", "pkg_scoop",
                    "flathub_url", "alternativeto_url", "flathub_installs", "flathub_download_size", "flathub_installed_size",
                    "app_name_source", "flathub_checked_at", "alternativeto_checked_at",
                    "avatar_hash", "flathub_icon_hash", "alternativeto_icon_hash", "website_icon_hash",
                    "shots_json", "thumb_choice", "thumb_custom_path", "link_ignore").forEach { st.execute("ALTER TABLE bookmark DROP COLUMN $it") }
                st.execute("PRAGMA user_version = 1")
            }
        }
        // open again → migration 1.sqm runs
        val (driver, db) = DatabaseFactory.open(file)
        driver.use {
            val version = driver.executeQuery(null, "PRAGMA user_version", { c ->
                app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else null)
            }, 0).value
            assertEquals(BookmarksDb.Schema.version, version)
            db.enrichQueries.setMeta("Titel", "Beschreibung", null, null, 1)
            db.aiQueries.setAiResult("KI-Titel", "Kurz", "[\"kotlin\"]", "Technik", 5, "docs", 1)
            val item = Library.load(db).items.single()
            assertEquals("Titel", item.metaTitle)
            assertEquals("A", item.title)
            assertEquals(listOf("kotlin"), item.aiTags)
            assertEquals("Technik", item.aiFolder)
            db.appQueries.updateApp("FileZilla", "user", "pc", "windows,linux", "GPL-2.0", "FileZilla.FileZilla", null, null, null, null,
                "https://flathub.org/apps/org.filezillaproject.Filezilla", null, 1)
            val app = Library.load(db).items.single().app
            assertEquals("FileZilla", app.name)
            assertEquals(listOf("windows", "linux"), app.systems)
            assertEquals("org.filezillaproject.Filezilla", app.flathubId)
            // export (uses SELECT b.*) works with the migrated column order
            assertEquals("https://a.example/", BookmarkRepository(db).export().walk().single().let { (it as de.bookmarksplus.netscape.NsLink).href })
        }
    }
}
