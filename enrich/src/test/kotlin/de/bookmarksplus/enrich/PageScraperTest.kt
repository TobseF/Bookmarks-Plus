package de.bookmarksplus.enrich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageScraperTest {

    @Test
    fun extractsMetaImageIconsAndRepo() {
        val html = """
            <html><head>
              <title>Fallback</title>
              <meta property="og:title" content="Ktor – Build Asynchronous Servers">
              <meta name="description" content="Ktor is a framework">
              <meta property="og:image" content="/img/share.png">
              <link rel="icon" href="/favicon.svg" type="image/svg+xml">
              <link rel="icon" href="/icon-32.png" sizes="32x32">
              <link rel="apple-touch-icon" href="/apple.png" sizes="180x180">
            </head><body>
              <a href="https://github.com/ktorio/ktor">GitHub</a>
              <a href="https://github.com/ktorio/ktor/issues">Issues</a>
              <a href="https://github.com/sponsors/someone">Sponsor</a>
              <a href="https://github.com/other/thing">Other</a>
            </body></html>
        """.trimIndent()
        val m = PageScraper.parse(html, "https://ktor.io/docs/")
        assertEquals("Ktor – Build Asynchronous Servers", m.title)
        assertEquals("Ktor is a framework", m.description)
        assertEquals("https://ktor.io/img/share.png", m.imageUrl)
        assertEquals("https://ktor.io/icon-32.png", m.iconUrls.first())
        assertTrue(m.iconUrls.last() == "https://ktor.io/favicon.ico" || m.iconUrls.last().endsWith(".svg"))
        assertEquals("https://github.com/ktorio/ktor", m.repoUrl)
        assertEquals("docs", m.contentType)
    }

    @Test
    fun ignoresUnrelatedRepoLinks() {
        val html = """<a href="https://github.com/foo/bar">x</a><a href="https://github.com/baz/qux">y</a>"""
        assertNull(PageScraper.parse(html, "https://example.com/").repoUrl)
        assertNull(PageScraper.parse(html, "https://github.com/foo/bar").repoUrl)
    }

    @Test
    fun blocksConsentHosts() {
        assertTrue(CookieBanners.isBlocked("https://consent.cookiebot.com/uc.js"))
        assertTrue(CookieBanners.isBlocked("https://cdn.privacy-mgmt.com/wrapper.js"))
        assertTrue(!CookieBanners.isBlocked("https://www.spiegel.de/"))
    }

    @Test
    fun sniffsImages() {
        assertEquals("image/png", ImageSniffer.mime(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals("image/svg+xml", ImageSniffer.mime("<svg xmlns='x'/>".toByteArray()))
    }
}
