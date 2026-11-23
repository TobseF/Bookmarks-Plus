package de.bookmarksplus.enrich

import de.bookmarksplus.i18n.tr
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.WaitUntilState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * Page screenshots via Playwright. Uses an installed Chrome or Edge (no browser download needed)
 * and otherwise falls back to Playwright's own Chromium.
 * Playwright is not thread-safe: each instance runs on exactly one thread of its own.
 *
 * Two paths: the fast path (several workers, fresh browser per page) and – only if it hits a bot check –
 * the path via the real, installed browser with its own persistent profile ([profileDir]).
 * Cookies and passed checks are kept there. If [visible] is on, the window appears and the
 * user can confirm a check themselves; the capture then continues automatically.
 * Checks are never solved automatically – if it does not succeed, there is no thumbnail (instead of an image of the check page).
 */
class Screenshotter(
    workers: Int = 2,
    private val dark: () -> Boolean = { true },
    private val profileDir: File? = null,
    private val visible: () -> Boolean = { false },
    private val width: Int = 1280,
    private val height: Int = 800,
) : Closeable {

    private sealed interface Shot {
        class Ok(val bytes: ByteArray) : Shot
        /** Bot check (e.g. Cloudflare) instead of the actual page. */
        data object Blocked : Shot
        data object Failed : Shot
        /** HTML only (no image): status and source after rendering. */
        class Html(val status: Int, val html: String) : Shot
    }

    private class Job(val url: String, val result: CompletableDeferred<Shot>, val html: Boolean = false)

    private val queue = Channel<Job>(Channel.UNLIMITED)
    private val realQueue = Channel<Job>(Channel.UNLIMITED)
    private val pool = Executors.newFixedThreadPool(workers + if (profileDir != null) 1 else 0) { r -> Thread(r, "screenshotter").apply { isDaemon = true } }
    @Volatile private var closed = false
    @Volatile var lastError: String? = null
        private set

    init {
        repeat(workers) { pool.execute(::workerLoop) }
        if (profileDir != null) pool.execute(::realLoop)
    }

    /** Returns a JPEG (1280×800) or null if the page could not be loaded or shows a bot check. */
    suspend fun capture(url: String): ByteArray? {
        if (closed) return null
        return when (val r = submit(queue, url)) {
            is Shot.Ok -> r.bytes
            Shot.Blocked -> if (profileDir != null) (submit(realQueue, url) as? Shot.Ok)?.bytes else null
            Shot.Failed, is Shot.Html -> null
        }
    }

    /**
     * Rendered HTML of a page via the real browser with persistent profile (cookies and passed
     * bot checks are kept). Without a profile via a fresh browser. Returns (status, HTML) or null.
     */
    suspend fun html(url: String): Pair<Int, String>? {
        if (closed) return null
        val r = submit(if (profileDir != null) realQueue else queue, url, html = true)
        return (r as? Shot.Html)?.let { it.status to it.html }
    }

    private suspend fun submit(q: Channel<Job>, url: String, html: Boolean = false): Shot {
        val job = Job(url, CompletableDeferred(), html)
        q.send(job)
        return job.result.await()
    }

    // ------------------------------------------------------------ fast path

    private fun workerLoop() {
        var playwright: Playwright? = null
        var browser: Browser? = null
        try {
            playwright = newPlaywright()
            browser = launch(playwright)
            while (!closed) {
                val job = kotlinx.coroutines.runBlocking { queue.receiveCatching().getOrNull() } ?: break
                if (!browser!!.isConnected) browser = launch(playwright)
                job.result.complete(runCatching { if (job.html) htmlFresh(browser!!, job.url) else shootFresh(browser!!, job.url) }.onFailure { lastError = it.message?.lineSequence()?.firstOrNull() }.getOrDefault(Shot.Failed))
            }
        } catch (e: Throwable) {
            lastError = e.message?.lineSequence()?.firstOrNull()
            drain(queue)
        } finally {
            runCatching { browser?.close() }
            runCatching { playwright?.close() }
        }
    }

    private fun newPlaywright(): Playwright =
        Playwright.create(Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")))

    private fun launch(pw: Playwright): Browser {
        val args = listOf("--disable-blink-features=AutomationControlled", "--mute-audio", "--hide-scrollbars")
        for (channel in listOf("chrome", "msedge", null)) {
            val opts = BrowserType.LaunchOptions().setHeadless(true).setArgs(args)
            if (channel != null) opts.setChannel(channel)
            runCatching { return pw.chromium().launch(opts) }
        }
        error(tr("enrich.chromium.missingInstall"))
    }

    private fun scheme() = if (dark()) ColorScheme.DARK else ColorScheme.LIGHT

    private fun shootFresh(browser: Browser, url: String): Shot {
        val ctx = browser.newContext(
            Browser.NewContextOptions()
                .setViewportSize(width, height)
                .setLocale(PageLang.locale)
                .setColorScheme(scheme()) // prefers-color-scheme
                .setUserAgent(Http.USER_AGENT)
                .setJavaScriptEnabled(true)
                .setIgnoreHTTPSErrors(true),
        )
        try {
            prepare(ctx)
            return shoot(ctx.newPage(), url, challengeWaitMs = 0)
        } finally {
            runCatching { ctx.close() }
        }
    }

    // ------------------------------------------------------------ real browser with profile

    private fun realLoop() {
        var playwright: Playwright? = null
        var ctx: BrowserContext? = null
        var launchedVisible = false
        try {
            playwright = newPlaywright()
            while (!closed) {
                val job = kotlinx.coroutines.runBlocking { realQueue.receiveCatching().getOrNull() } ?: break
                val show = visible()
                if (ctx != null && (show != launchedVisible || runCatching { ctx!!.pages() }.isFailure)) {
                    runCatching { ctx!!.close() }
                    ctx = null
                }
                if (ctx == null) {
                    ctx = launchReal(playwright, headless = !show)
                    launchedVisible = show
                }
                job.result.complete(
                    runCatching { if (job.html) htmlReal(ctx!!, job.url, show) else shootReal(ctx!!, job.url, show) }
                        .onFailure { lastError = it.message?.lineSequence()?.firstOrNull() }.getOrDefault(Shot.Failed),
                )
            }
        } catch (e: Throwable) {
            lastError = e.message?.lineSequence()?.firstOrNull()
            drain(realQueue)
        } finally {
            runCatching { ctx?.close() }
            runCatching { playwright?.close() }
        }
    }

    private fun launchReal(pw: Playwright, headless: Boolean): BrowserContext {
        val dir = profileDir!!
        dir.mkdirs()
        for (channel in listOf("chrome", "msedge", null)) {
            val opts = BrowserType.LaunchPersistentContextOptions()
                .setHeadless(headless)
                .setViewportSize(width, height)
                .setLocale(PageLang.locale)
                .setColorScheme(scheme())
                .setIgnoreHTTPSErrors(true)
                .setArgs(listOf("--mute-audio", "--hide-scrollbars"))
                .setIgnoreDefaultArgs(listOf("--enable-automation"))
            if (channel != null) opts.setChannel(channel)
            runCatching { return pw.chromium().launchPersistentContext(dir.toPath(), opts) }
        }
        error(tr("enrich.chromium.missing"))
    }

    private fun shootReal(ctx: BrowserContext, url: String, show: Boolean): Shot {
        prepare(ctx)
        val page = ctx.newPage()
        try {
            // visible: time for a manual confirmation; invisible: wait only briefly (some checks resolve themselves)
            return shoot(page, url, challengeWaitMs = if (show) 90_000 else 12_000, bringToFront = show)
        } finally {
            runCatching { page.close() }
        }
    }

    private fun htmlFresh(browser: Browser, url: String): Shot {
        val ctx = browser.newContext(Browser.NewContextOptions().setLocale("en-US").setUserAgent(Http.USER_AGENT))
        try {
            return loadHtml(ctx.newPage(), url, challengeWaitMs = 8_000, bringToFront = false)
        } finally {
            runCatching { ctx.close() }
        }
    }

    private fun htmlReal(ctx: BrowserContext, url: String, show: Boolean): Shot {
        val page = ctx.newPage()
        try {
            return loadHtml(page, url, challengeWaitMs = if (show) 90_000 else 15_000, bringToFront = show)
        } finally {
            runCatching { page.close() }
        }
    }

    /** Load page, wait for the bot check (never solve it ourselves), then return the source. */
    private fun loadHtml(page: Page, url: String, challengeWaitMs: Int, bringToFront: Boolean): Shot {
        page.setDefaultTimeout(20_000.0)
        val resp = page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(25_000.0))
        val status = resp?.status() ?: 0
        if (runCatching { isChallenge(page) }.getOrDefault(false)) {
            if (bringToFront) runCatching { page.bringToFront() }
            var waited = 0
            while (waited < challengeWaitMs && runCatching { isChallenge(page) }.getOrDefault(true)) {
                runCatching { page.waitForTimeout(1000.0) }
                waited += 1000
            }
            if (runCatching { isChallenge(page) }.getOrDefault(true)) return Shot.Blocked
            runCatching { page.waitForLoadState(LoadState.DOMCONTENTLOADED, Page.WaitForLoadStateOptions().setTimeout(10_000.0)) }
            return Shot.Html(200, page.content())
        }
        return Shot.Html(status, page.content())
    }

    // ------------------------------------------------------------ shared

    private val prepared = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap<BrowserContext, Boolean>()))

    private fun prepare(ctx: BrowserContext) {
        if (!prepared.add(ctx)) return
        ctx.addInitScript(CookieBanners.INIT_SCRIPT)
        ctx.route("**/*") { route ->
            val req = route.request()
            if (CookieBanners.isBlocked(req.url()) || req.resourceType() == "media") route.abort() else route.resume()
        }
    }

    private fun shoot(page: Page, url: String, challengeWaitMs: Int, bringToFront: Boolean = false): Shot {
        page.setDefaultTimeout(20_000.0)
        val resp = page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(25_000.0))
        val status = resp?.status() ?: 0
        if (status >= 400 && status != 403 && status != 503) return Shot.Failed
        runCatching { page.waitForLoadState(LoadState.LOAD, Page.WaitForLoadStateOptions().setTimeout(8_000.0)) }

        if (runCatching { isChallenge(page) }.getOrDefault(false)) {
            if (challengeWaitMs <= 0) return Shot.Blocked
            if (bringToFront) runCatching { page.bringToFront() }
            var waited = 0
            // errors during the redirect after a passed check count as "not finished yet"
            while (waited < challengeWaitMs && runCatching { isChallenge(page) }.getOrDefault(true)) {
                runCatching { page.waitForTimeout(1000.0) }
                waited += 1000
            }
            if (runCatching { isChallenge(page) }.getOrDefault(true)) return Shot.Blocked
            runCatching { page.waitForLoadState(LoadState.LOAD, Page.WaitForLoadStateOptions().setTimeout(8_000.0)) }
        } else if (status == 503) {
            return Shot.Failed
        }

        runCatching { page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(4_000.0)) }
        page.waitForTimeout(600.0)
        runCatching { page.evaluate(CookieBanners.CLEANUP_SCRIPT) }
        page.waitForTimeout(250.0)
        return Shot.Ok(page.screenshot(Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(85).setFullPage(false)))
    }

    /** Does the page show a bot check (Cloudflare etc.) instead of the content? */
    private fun isChallenge(page: Page): Boolean {
        val title = page.title().trim().lowercase()
        if (CHALLENGE_TITLES.any { title.contains(it) }) return true
        return page.locator("#challenge-running, #challenge-form, #cf-challenge-running, #challenge-stage").count() > 0
    }

    private fun drain(q: Channel<Job>) {
        while (true) q.tryReceive().getOrNull()?.result?.complete(Shot.Failed) ?: break
    }

    override fun close() {
        closed = true
        queue.close()
        realQueue.close()
        drain(queue)
        drain(realQueue)
        pool.shutdown()
    }

    private companion object {
        val CHALLENGE_TITLES = listOf(
            "just a moment", "einen moment", "attention required", "verifying you are human", "are you a robot",
            "ddos protection", "security check", "un instant", "un momento",
        )
    }
}
