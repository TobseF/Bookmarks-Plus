package de.bookmarksplus

import de.bookmarksplus.data.Item
import de.bookmarksplus.data.Shot
import de.bookmarksplus.data.Shots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShotsTest {
    @Test
    fun roundtripKeepsSourceAndHiddenFlag() {
        val list = listOf(Shot("h1", "/a.jpg", Shots.FLATHUB), Shot("h2", "/b.jpg", Shots.UPLOAD, hidden = true))
        assertEquals(list, Shots.parse(Shots.encode(list)))
        assertNull(Shots.encode(emptyList()))
        assertEquals(emptyList(), Shots.parse("kaputt"))
    }

    @Test
    fun mergeSkipsKnownContentEvenWhenHidden() {
        val cur = listOf(Shot("h1", "/a.jpg", Shots.FLATHUB, hidden = true))
        val merged = Shots.merge(cur, listOf(Shot("h1", "/a2.jpg", Shots.ALTERNATIVETO), Shot("h3", "/c.jpg", Shots.ALTERNATIVETO)))
        assertEquals(listOf("h1", "h3"), merged.map { it.hash })
        assertEquals(true, merged.first().hidden)
        assertEquals("/a.jpg", merged.first().path)
    }

    @Test
    fun thumbnailChoiceResolvesToTheRightFile() {
        fun item(choice: String?) = Item(
            id = 1, url = "https://a.example/", title = "t", aiTitle = null, description = null, summary = null, domain = null, faviconHash = null,
            thumbnailPath = "/web.jpg", cardImagePath = "/card.jpg", cardImageUrl = null, repoUrl = null, websiteUrl = null, stars = null,
            lastCommit = null, lastRelease = null, lastReleaseDate = null, author = null, org = null, contentType = null,
            linkStatus = null, addDate = null, lastModified = null, lastVisited = null, urlNorm = "a",
            shots = listOf(Shot("s1", "/s1.jpg", Shots.FLATHUB), Shot("s2", "/s2.jpg", Shots.UPLOAD, hidden = true)),
            thumbChoice = choice, customThumbPath = "/own.jpg",
        )
        assertNull(item(null).chosenThumbPath)
        assertEquals("/web.jpg", item("website").chosenThumbPath)
        assertEquals("/card.jpg", item("card").chosenThumbPath)
        assertEquals("/own.jpg", item("custom").chosenThumbPath)
        assertEquals("/s1.jpg", item("shot:s1").chosenThumbPath)
        assertNull(item("shot:s2").chosenThumbPath) // hidden → automatic
        assertEquals(listOf("/s1.jpg"), item(null).screenshots)
    }
}

class ShotsMigrationTest {
    @Test
    fun oldScreenshotColumnsBecomeShotList() {
        val sql = java.io.File("src/commonMain/sqldelight/de/bookmarksplus/db/10.sqm").readText()
        java.sql.DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE bookmark (id INTEGER, screenshot_1 TEXT, screenshot_2 TEXT, screenshot_3 TEXT)")
                st.execute("INSERT INTO bookmark VALUES (1, '/a.jpg', '/b.jpg', NULL), (2, NULL, NULL, NULL), (3, '/c.jpg', NULL, NULL)")
                sql.lines().filter { !it.startsWith("--") }.joinToString("\n").split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) }
                val rows = st.executeQuery("SELECT id, shots_json FROM bookmark ORDER BY id").use { rs ->
                    buildMap { while (rs.next()) put(rs.getInt(1), rs.getString(2)) }
                }
                assertEquals(listOf("/a.jpg", "/b.jpg"), Shots.parse(rows[1]).map { it.path })
                assertEquals(Shots.UNKNOWN, Shots.parse(rows[1]).first().source)
                assertNull(rows[2])
                assertEquals(listOf("/c.jpg"), Shots.parse(rows[3]).map { it.path })
            }
        }
    }
}
