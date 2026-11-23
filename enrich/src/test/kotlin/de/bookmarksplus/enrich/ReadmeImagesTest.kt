package de.bookmarksplus.enrich

import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.netscape.NetscapeParser
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadmeImagesTest {
    private val readme = """
        <p align="center"><img src="assets/logo.svg" height="120"></p>
        # Tool
        [![Build](https://github.com/o/r/actions/workflows/ci.yml/badge.svg)](x) ![stars](https://img.shields.io/github/stars/o/r)
        <img src="https://example.org/icon.png" width="32" height="32">
        ![Screenshot](./docs/screenshot.png "Hauptfenster")
        ![Demo][demo]
        <picture><source media="(prefers-color-scheme: dark)" srcset="docs/dark.png 2x"><img src="docs/light.png" height="400"></picture>
        <img alt="x" src='https://github.com/o/r/blob/main/media/big.jpg?raw=true'>
        .. image:: https://example.org/rst.png
           :height: 300

        [demo]: https://user-images.githubusercontent.com/1/demo.gif
    """.trimIndent()

    @Test
    fun findsImagesInOrderWithoutBadges() {
        val c = ReadmeImages.candidates(readme, "o", "r", maxLines = 60)
        assertEquals(
            listOf(
                "https://example.org/icon.png",
                "https://raw.githubusercontent.com/o/r/HEAD/docs/screenshot.png",
                "https://user-images.githubusercontent.com/1/demo.gif",
                "https://raw.githubusercontent.com/o/r/HEAD/docs/dark.png",
                "https://raw.githubusercontent.com/o/r/HEAD/docs/light.png",
                "https://raw.githubusercontent.com/o/r/main/media/big.jpg",
                "https://example.org/rst.png",
            ),
            c.map { it.url },
        )
        assertEquals(32, c.first().declaredHeight)
        assertEquals(300, c.last().declaredHeight)
    }

    @Test
    fun onlyFirstLines() {
        val c = ReadmeImages.candidates(readme, "o", "r", maxLines = 5)
        assertEquals(listOf("https://example.org/icon.png", "https://raw.githubusercontent.com/o/r/HEAD/docs/screenshot.png"), c.map { it.url })
    }

    /** Real repos: ./gradlew :enrich:test --tests "*ReadmeImagesTest*" -Dnet=true (cards under build/enrich-test/readme). */
    @Test
    fun realRepos() = runBlocking {
        if (System.getProperty("net") != "true") return@runBlocking
        val out = File(System.getProperty("out.dir"), "readme").apply { deleteRecursively(); mkdirs() }
        val urls = listOf(
            "https://github.com/sharkdp/bat", "https://github.com/junegunn/fzf", "https://github.com/jesseduffield/lazygit",
            "https://github.com/BurntSushi/ripgrep", "https://github.com/localsend/localsend", "https://github.com/kotlin/kotlinx.coroutines",
            "https://github.com/PapirusDevelopmentTeam/papirus-icon-theme/",
        )
        val html = "<DL><p>\n" + urls.joinToString("\n") { "<DT><A HREF=\"$it\" ADD_DATE=\"1700000000\">$it</A>" } + "\n</DL><p>"
        val (driver, db) = DatabaseFactory.open(null)
        de.bookmarksplus.data.BookmarkRepository(db).import(NetscapeParser.parse(html))
        val enricher = Enricher(driver, db, File(out, "media"), { EnrichSettings(githubToken = System.getenv("GITHUB_TOKEN"), thumbnails = false) })
        for (id in 1L..urls.size) runCatching { enricher.enrich(id) }.onFailure { println("FAIL $id $it") }
        db.bookmarkQueries.viewRows().executeAsList().forEach { r ->
            println("${r.url}\n  card=${r.card_image_path?.let { File(it).name }} src=${r.card_image_url}")
        }
        enricher.close()
    }
}

class ReadmeImagesPapirusTest {
    @Test
    fun papirusPreviewIsFirst() {
        val md = javaClass.getResource("/readme/papirus.md")!!.readText()
        val c = ReadmeImages.candidates(md, "PapirusDevelopmentTeam", "papirus-icon-theme", 60)
        println(c)
        assertEquals("https://raw.githubusercontent.com/PapirusDevelopmentTeam/papirus-icon-theme/master/preview.png", c.firstOrNull()?.url)
    }
}
