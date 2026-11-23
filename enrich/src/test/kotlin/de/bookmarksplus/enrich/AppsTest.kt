package de.bookmarksplus.enrich

import de.bookmarksplus.data.AppInfo
import de.bookmarksplus.enrich.apps.AlternativeToParser
import de.bookmarksplus.enrich.apps.AppNameAi
import de.bookmarksplus.enrich.apps.AppNameInput
import de.bookmarksplus.enrich.apps.AppNames
import de.bookmarksplus.enrich.apps.AppUrls
import de.bookmarksplus.enrich.apps.Challenge
import de.bookmarksplus.enrich.apps.FlathubParser
import de.bookmarksplus.enrich.apps.ItchParser
import de.bookmarksplus.enrich.apps.PackageIds
import de.bookmarksplus.enrich.apps.RateGate
import de.bookmarksplus.enrich.apps.RateLimited
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Programs and games: Flathub, AlternativeTo, package IDs, names (stored originals in test/resources). */
class AppsTest {
    private fun res(path: String) = javaClass.getResource(path)!!.readText()

    @Test
    fun flathubAppstreamStatsSummary() {
        val app = FlathubParser.parseAppstream(res("/flathub/filezilla-appstream.json"))!!
        assertEquals("org.filezillaproject.Filezilla", app.id)
        assertEquals("FileZilla", app.name)
        assertEquals("Fast and reliable FTP client", app.summary)
        assertEquals("GPL-2.0-or-later", app.license)
        assertEquals("Tim Kosse", app.developer)
        assertEquals("https://filezilla-project.org/", app.homepage)
        assertEquals("3.72.1", app.version)
        assertTrue(app.iconUrl!!.endsWith(".png"))
        assertTrue(app.screenshots.isNotEmpty() && app.screenshots.size <= 3)
        assertTrue(app.screenshots.none { it.endsWith("_orig.png") }, "keine riesigen Originale: ${app.screenshots}")
        assertTrue(app.description!!.contains("- Easy to use"))
        assertEquals(listOf("Network", "FileTransfer"), app.categories)
        assertEquals(790265L, FlathubParser.parseInstalls(res("/flathub/filezilla-stats.json")))
        assertEquals(14551111L to 37200384L, FlathubParser.parseSizes(res("/flathub/filezilla-summary.json")))
        assertEquals("Proprietary", FlathubParser.cleanLicense("LicenseRef-proprietary=https://example.org/eula"))
    }

    @Test
    fun itchioPage() {
        val app = ItchParser.parse(res("/itchio/flowframes.html"), "https://nmkd.itch.io/flowframes")!!
        assertEquals("flowframes", app.slug)
        assertEquals("https://nmkd.itch.io/flowframes", app.url)
        assertEquals("Flowframes", app.name)
        assertEquals(listOf("windows"), app.systems)
        assertEquals(listOf("pc"), app.platforms)
        assertEquals("Tool", app.category)
        assertEquals("N00MKRAD", app.author)
        assertEquals(listOf("ai", "artificial-intelligence", "video"), app.tags)
        assertEquals(4.6, app.rating)
        assertEquals(113L, app.ratingCount)
        assertEquals(listOf("Flowframes 1.36.0 (Web Installer)"), app.downloads)
        assertTrue(app.screenshots.isNotEmpty() && app.screenshots.size <= 3)
        assertTrue(app.screenshots.all { it.startsWith("https://img.itch.zone/") && "/original/" !in it }, "${app.screenshots}")
        assertTrue(app.description!!.startsWith("Video interpolation"))
    }

    @Test
    fun itchioUrls() {
        assertEquals("nmkd" to "flowframes", ItchParser.pageOf("https://nmkd.itch.io/flowframes?x=1"))
        assertEquals("https://nmkd.itch.io/flowframes", ItchParser.pageUrl("https://nmkd.itch.io/flowframes/devlog/1"))
        assertNull(ItchParser.pageOf("https://nmkd.itch.io/"))
        assertNull(ItchParser.pageOf("https://itch.io/games/free"))
        assertNull(ItchParser.pageOf("https://example.com/flowframes"))
    }

    @Test
    fun flathubSearch() {
        val hits = FlathubParser.parseSearch(
            """{"hits":[{"name":"FileZilla","summary":"Fast","id":"org_filezillaproject_Filezilla","app_id":"org.filezillaproject.Filezilla","developer_name":"Tim Kosse","installs_last_month":11074}]}""",
        )
        assertEquals("org.filezillaproject.Filezilla", hits.single().id)
        assertEquals(11074L, hits.single().installsLastMonth)
    }

    @Test
    fun alternativeToPage() {
        val at = AlternativeToParser.parse(res("/alternativeto/filezilla.html"), "https://alternativeto.net/software/filezilla/about/")!!
        assertEquals("filezilla", at.slug)
        assertEquals("FileZilla", at.name)
        assertEquals("GPL-2.0", at.license)
        assertEquals(true, at.openSource)
        assertEquals("FileZilla Project", at.developer)
        // icon of the app itself (not one of the alternatives), as PNG instead of SVG
        assertEquals("https://img.alternativeto.net/icons/280/png/filezilla_245918.svg", at.iconUrl)
        assertEquals("https://filezilla-project.org", at.website)
        assertEquals("https://flathub.org/apps/org.filezillaproject.Filezilla", at.flathubUrl)
        assertTrue("Windows" in at.operatingSystems && "Linux" in at.operatingSystems)
        assertEquals(listOf("pc"), at.platforms)
        assertEquals(listOf("windows", "mac", "linux"), at.systems)
        assertEquals(1966L, at.likes)
        assertEquals(2, at.screenshots.size)
        assertTrue(at.screenshots.all { "/jpeg/" in it && "618x394" in it }, at.screenshots.toString())
        assertEquals("org.filezillaproject.Filezilla", at.packages.flatpak)
    }

    @Test
    fun alternativeToSearchResults() {
        val hits = AlternativeToParser.parseSearch(res("/alternativeto/search-hoppscotch.html"))
        // ad entry (AliasVault, "Official Partner") does not belong to the hits
        assertEquals(listOf("postwoman" to "Hoppscotch", "hopscotch" to "Hopscotch", "hopscotch-games" to "HopScotch Games"), hits)
        val p = AlternativeToParser.parse(res("/alternativeto/postwoman.html"), "https://alternativeto.net/software/postwoman/about/")!!
        assertEquals("Hoppscotch", p.name)
        assertEquals("MIT", p.license)
        assertEquals("https://github.com/hoppscotch/hoppscotch", p.repoUrl)
        assertEquals(listOf("browser", "docker"), p.platforms)
        assertTrue(AppNames.sameRepo("https://github.com/Hoppscotch/hoppscotch.git", "https://github.com/hoppscotch/hoppscotch/tree/main"))
        assertTrue(AppNames.sameSite("zulip.org", "www.zulip.com"))
        assertFalse(AppNames.sameSite("github.com", "github.io"))
    }

    /** Renamed program: slug from the name gives 404 → search → hit with the same name (without real network). */
    @Test
    fun renamedProgramFoundViaSearch(): Unit = runBlocking {
        val (driver, db) = de.bookmarksplus.data.DatabaseFactory.open(null)
        de.bookmarksplus.data.BookmarkRepository(db).import(
            de.bookmarksplus.netscape.NetscapeParser.parse("""<DL><p><DT><A HREF="https://github.com/hoppscotch/hoppscotch" ADD_DATE="1">Hoppscotch</A></DL><p>"""),
        )
        val id = db.bookmarkQueries.viewRows().executeAsList().single().id
        db.appQueries.updateApp("Hoppscotch", "ai", null, null, null, null, null, null, null, null, null, null, id)
        val requested = mutableListOf<String>()
        val pages = mapOf(
            "https://alternativeto.net/software/hoppscotch/about/" to (404 to "<html><title>Not found</title></html>"),
            "https://alternativeto.net/browse/search/?q=Hoppscotch" to (200 to res("/alternativeto/search-hoppscotch.html")),
            "https://alternativeto.net/software/postwoman/about/" to (200 to res("/alternativeto/postwoman.html")),
        )
        val media = java.nio.file.Files.createTempDirectory("apps").toFile()
        val apps = de.bookmarksplus.enrich.apps.AppService(
            driver, db, media,
            settings = { de.bookmarksplus.enrich.apps.AppSettings(flathub = false, alternativeToIntervalSec = 1, viaExtension = false, alternativeToDirect = false) },
            browserHtml = { url -> requested += url; pages[url] },
        )
        assertTrue(apps.alternativeTo(id, force = true))
        assertEquals(pages.keys.toList(), requested)
        val a = de.bookmarksplus.data.Library.load(db).items.single().app
        assertEquals("https://alternativeto.net/software/postwoman/about/", a.alternativetoUrl)
        assertEquals("MIT", a.license)
        assertEquals(listOf("browser", "docker"), a.platforms)
        apps.close()
    }

    @Test
    fun challengePagesAreRecognized() {
        val html = res("/alternativeto/challenge.html")
        assertTrue(Challenge.isChallenge(html))
        assertNull(AlternativeToParser.parse(html, "https://alternativeto.net/software/filezilla/about/"))
        assertFalse(Challenge.isChallenge(res("/alternativeto/filezilla.html")))
    }

    @Test
    fun packageIdsFromInstallCommands() {
        val readme = """
            ## Install
            Windows: `winget install -e --id FileZilla.FileZilla` or `choco install filezilla -y` or `scoop install extras/filezilla`
            macOS: `brew install --cask filezilla`
            Build deps: `brew install cmake gettext` · `npm install -g filezilla-cli` · `flatpak install flathub org.filezillaproject.Filezilla`
        """.trimIndent()
        val f = PackageIds.find(readme, "FileZilla")
        assertEquals("FileZilla.FileZilla", f.winget)
        assertEquals("filezilla", f.choco)
        assertEquals("extras/filezilla", f.scoop)
        assertEquals("--cask filezilla", f.brew)
        assertEquals("filezilla-cli", f.npm)
        assertEquals("org.filezillaproject.Filezilla", f.flatpak)
        // without a name only unambiguous ones: two brew commands → none
        val g = PackageIds.find("brew install cmake\nbrew install ninja\nwinget install Git.Git")
        assertNull(g.brew)
        assertEquals("Git.Git", g.winget)
        assertEquals("Microsoft.VisualStudioCode", PackageIds.all("winget install --exact --id Microsoft.VisualStudioCode")["winget"]!!.single())
        assertEquals("@scope/tool", PackageIds.find("npm i -g @scope/tool", "tool").npm)
    }

    @Test
    fun urlFacts() {
        val fh = AppUrls.facts("https://flathub.org/en/apps/org.filezillaproject.Filezilla")
        assertEquals("https://flathub.org/apps/org.filezillaproject.Filezilla", fh.flathubUrl)
        assertEquals(listOf("linux"), fh.systems)
        assertEquals("https://alternativeto.net/software/filezilla/about/", AppUrls.facts("https://alternativeto.net/software/filezilla/").alternativetoUrl)
        assertEquals(listOf("android"), AppUrls.facts("https://play.google.com/store/apps/details?id=org.videolan.vlc").systems)
        assertEquals(listOf("browser"), AppUrls.facts("https://chromewebstore.google.com/detail/ublock-origin/cjpalhdlnbpafiamejdnhcphjbkeiagm").platforms)
        assertEquals(listOf("docker"), AppUrls.facts("https://hub.docker.com/r/linuxserver/jellyfin").platforms)
        assertEquals("@angular/cli", AppUrls.facts("https://www.npmjs.com/package/@angular/cli").npm)
        assertEquals("--cask firefox", AppUrls.facts("https://formulae.brew.sh/cask/firefox").brew)
        assertEquals("git", AppUrls.facts("https://community.chocolatey.org/packages/git").choco)
        assertEquals(AppInfo.EMPTY, AppUrls.facts("https://example.org/blog"))
    }

    @Test
    fun namesFromTitles() {
        assertEquals("FileZilla", AppNames.fromTitle("FileZilla - The free FTP solution", listOf("filezilla")))
        assertEquals("7-Zip", AppNames.fromTitle("Download 7-Zip 24.08 | SourceForge.net", listOf("7-zip")))
        assertEquals("Obsidian", AppNames.fromTitle("Obsidian - Sharpen your thinking", listOf("obsidian")))
        assertEquals("KeePassXC", AppNames.fromTitle("KeePassXC Password Manager", listOf("keepassxc")))
        assertEquals("Krita", AppNames.fromTitle("Krita | Digital Painting. Creative Freedom.", listOf("krita")))
        // long descriptive title without hint → AI
        assertNull(AppNames.fromTitle("How to set up an FTP server on Ubuntu in 5 minutes"))
        assertEquals("visual-studio-code", AppNames.slug("Visual Studio Code"))
        assertEquals("notepad-plus-plus", AppNames.slug("Notepad++"))
        assertEquals("7-zip", AppNames.slug("7-Zip"))
        assertTrue(AppNames.same("Filezilla", "FileZilla"))
        assertEquals("filezilla", AppNames.domainHint("https://filezilla-project.org/download.php"))
    }

    @Test
    fun aiNameResponse() {
        val items = listOf(
            AppNameInput(10, "https://filezilla-project.org", "FileZilla - The free FTP solution", null, null, null, null),
            AppNameInput(11, "https://heise.de/x", "Ein Artikel", null, null, null, null),
        )
        val r = AppNameAi.parse(
            """```json
            {"items":[{"i":1,"name":"FileZilla","platforms":["pc","desktop"],"systems":["Windows","macOS","linux","ios"]},{"i":2,"name":null}]}
            ```""",
            items,
        )
        assertEquals("FileZilla", r[0].name)
        assertEquals(listOf("pc"), r[0].platforms)
        assertEquals(listOf("windows", "mac", "linux"), r[0].systems)
        assertNull(r[1].name)
        assertEquals(11L, r[1].id)
    }

    @Test
    fun rateGateWaitsPausesAndLimits(): Unit = runBlocking {
        var now = 1_000_000L
        val gate = RateGate("test", minIntervalMs = 50, dailyLimit = 3, clock = { now })
        gate.run { }
        gate.run { }
        gate.run { }
        assertTrue(gate.exhausted)
        assertFailsWith<RateLimited> { gate.run { } }
        now += 86_400_000L
        gate.run { }
        val until = gate.penalize()
        assertEquals(now + 60_000, until)
        assertFailsWith<RateLimited> { gate.run { } }
        assertEquals(now + 120_000, gate.penalize())
        gate.resetPause()
        assertNotNull(gate.run { 1 })
    }
}
