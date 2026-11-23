package de.bookmarksplus.enrich

import de.bookmarksplus.util.Urls
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SourceForge project pages (stored originals in test/resources/sourceforge). */
class SourceForgeTest {
    private fun page(name: String) = javaClass.getResource("/sourceforge/$name.html")!!.readText()
    private fun day(s: String) = LocalDate.parse(s).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

    @Test
    fun projectUrls() {
        assertEquals("bomberclone", Urls.sourceforgeProject("https://sourceforge.net/projects/bomberclone/"))
        assertEquals("bomberclone", Urls.sourceforgeProject("http://www.sourceforge.net/projects/BomberClone/files/"))
        assertEquals("7zip", Urls.sourceforgeProject("https://sourceforge.net/p/7zip/discussion/"))
        assertNull(Urls.sourceforgeProject("https://sourceforge.net/directory/games/"))
        assertNull(Urls.sourceforgeProject("https://bomberclone.sourceforge.io/"))
    }

    @Test
    fun bomberclone() {
        val i = SourceForgeParser.parse(page("bomberclone"), "https://sourceforge.net/projects/bomberclone/")!!
        assertEquals("bomberclone", i.project)
        assertEquals("BomberClone", i.name)
        assertEquals(listOf("Games", "Side-Scrolling"), i.categories)
        assertEquals(day("2015-03-28"), i.lastUpdate)
        assertEquals(1L, i.reviews)
        assertEquals(listOf("patty21", "stpohle"), i.authors)
        assertTrue(i.description!!.startsWith("This game, I hope it will be soon"), i.description)
        assertEquals(3, i.screenshots.size)
        assertTrue(i.screenshots.all { it.startsWith("https://a.fsdn.com/") && "/max/max/" in it }, i.screenshots.toString())
        assertEquals("https://a.fsdn.com/allura/p/bomberclone/icon?1427583766", i.iconUrl)
        assertEquals("http://www.bomberclone.de", i.homepage)
        assertEquals("https://sourceforge.net/projects/bomberclone/files/latest/download", i.downloadUrl)
        assertEquals("bomberclone-0.11.9.tar.gz", i.downloadFile)
    }

    @Test
    fun sevenZip() {
        val i = SourceForgeParser.parse(page("sevenzip"), "https://sourceforge.net/projects/sevenzip/")!!
        assertEquals("sevenzip", i.project)
        assertEquals("7-Zip", i.name)
        assertEquals("A free file archiver for extremely high compression", i.tagline)
        assertEquals(listOf("System", "Storage", "Archiving", "File Compression"), i.categories)
        assertEquals(833L, i.reviews)
        assertEquals(day("2026-11-08"), i.lastUpdate)
        assertNotNull(i.iconUrl)
        assertTrue("sevenzip/icon" in i.iconUrl!!, i.iconUrl)
    }

    @Test
    fun challengePageIsNoProject() {
        val cf = "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body></body></html>"
        assertNull(SourceForgeParser.parse(cf, "https://sourceforge.net/projects/bomberclone/"))
    }

    @Test
    fun bestReleasePrefersWindows() {
        val json = """{"release":{"filename":"/bomberclone/bomberclone-0.11.9.tar.gz","date":"2010-12-11 21:26:43"},
            "platform_releases":{"windows":{"filename":"/bomberclone/bomberclone-0.11.9.exe","date":"2010-12-11 21:25:33"}}}"""
        val r = SourceForgeParser.parseRelease(json, "bomberclone")!!
        assertEquals("bomberclone-0.11.9.exe", r.file)
        assertEquals("0.11.9", r.version)
        assertEquals("https://sourceforge.net/projects/bomberclone/files/bomberclone/bomberclone-0.11.9.exe/download", r.downloadUrl)
        assertNotNull(r.date)
        val seven = SourceForgeParser.parseRelease("""{"release":{"filename":"/7-Zip/26.06/7z2606-x64.exe","date":"2026-11-08 10:00:00"}}""", "sevenzip")!!
        assertEquals("26.06", seven.version)
    }

    @Test
    fun renderCard() {
        val shot = java.awt.image.BufferedImage(800, 600, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(40, 120, 200), 800f, 600f, java.awt.Color(240, 160, 40))
                fillRect(0, 0, 800, 600)
                dispose()
            }
        }
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(shot, "png", it) }.toByteArray()
        val png = AppCardRenderer.render(
            AppCardRenderer.Card(
                "BomberClone",
                "This game, I hope it will be soon, a good Clone of the game AtomicBomberMan. It supports multiplayer over the Internet.",
                listOf("Games", "Side-Scrolling"), screenshot = bytes,
            ),
        )
        val img = javax.imageio.ImageIO.read(png.inputStream())
        assertEquals(AppCardRenderer.WIDTH, img.width)
        System.getProperty("out.dir")?.let { File(it).apply { mkdirs() }.resolve("sourceforge-card.png").writeBytes(png) }
    }
}
