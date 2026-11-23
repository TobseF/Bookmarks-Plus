package de.bookmarksplus.enrich

import de.bookmarksplus.data.BookmarkRepository
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.Library
import de.bookmarksplus.enrich.apps.AppService
import de.bookmarksplus.enrich.apps.AppSettings
import de.bookmarksplus.netscape.NetscapeParser
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real Flathub/AlternativeTo queries: ./gradlew :enrich:test --tests "*AppsNetTest*" -Dnet=true */
class AppsNetTest {
    @Test
    fun flathubAndAlternativeTo(): Unit = runBlocking {
        if (System.getProperty("net") != "true") return@runBlocking println("net tests disabled")
        val out = File(System.getProperty("out.dir"), "apps").apply { deleteRecursively(); mkdirs() }
        val links = listOf(
            "https://filezilla-project.org/" to "FileZilla - The free FTP solution",
            "https://flathub.org/en/apps/org.kde.krita" to "Install Krita on Linux | Flathub",
            "https://keepassxc.org/" to "KeePassXC Password Manager",
        )
        val html = "<DL><p>\n" + links.joinToString("\n") { (u, t) -> "<DT><A HREF=\"$u\" ADD_DATE=\"1700000000\">$t</A>" } + "\n</DL><p>"
        val (driver, db) = DatabaseFactory.open(null)
        BookmarkRepository(db).import(NetscapeParser.parse(html))
        val enricher = Enricher(driver, db, File(out, "media"), { EnrichSettings(thumbnails = false, realBrowser = true) })
        val apps = AppService(
            driver, db, File(out, "media"),
            settings = { AppSettings(alternativeToIntervalSec = 5) },
            browserHtml = { enricher.browserHtml(it) },
        )
        enricher.browserHtml("https://alternativeto.net/software/filezilla/about/")?.let { (st, h) ->
            println("BROWSER status=$st challenge=${de.bookmarksplus.enrich.apps.Challenge.isChallenge(h)} len=${h.length} title=${Regex("<title>([^<]*)").find(h)?.groupValues?.get(1)}")
        } ?: println("BROWSER null err=${enricher.screenshotError}")
        for (id in 1L..links.size) {
            enricher.enrich(id)
            apps.processOne(id)
            println("AT via=${apps.alternativeTo.lastVia} paused=${apps.alternativeTo.gate.paused}")
        }
        val items = Library.load(db).items.sortedBy { it.id }
        items.forEach { i ->
            val a = i.app
            println("${i.url}\n  name=${a.name} (${a.nameSource}) platforms=${a.platforms} systems=${a.systems} license=${a.license}" +
                "\n  flathub=${a.flathubUrl} installs=${a.flathubInstalls} dl=${a.flathubDownloadSize} inst=${a.flathubInstalledSize}" +
                "\n  at=${a.alternativetoUrl} pkgs=${a.packages} shots=${i.screenshots.size} website=${i.websiteUrl} repo=${i.repoUrl}")
        }
        val fz = items[0].app
        assertEquals("FileZilla", fz.name)
        assertEquals("https://flathub.org/apps/org.filezillaproject.Filezilla", fz.flathubUrl)
        assertTrue((fz.flathubInstalls ?: 0) > 100_000)
        assertNotNull(fz.flathubDownloadSize)
        assertEquals("GPL-2.0-or-later", fz.license)
        assertTrue(items[0].screenshots.isNotEmpty())
        val krita = items[1].app
        assertEquals("Krita", krita.name)
        assertEquals("flathub", krita.nameSource)
        assertEquals("KeePassXC", items[2].app.name)
        enricher.close()
        apps.close()
    }
}
