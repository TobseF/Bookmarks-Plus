package de.bookmarksplus.enrich

import de.bookmarksplus.db.BookmarksDb
import de.bookmarksplus.util.Urls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.Closeable
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/** Result of a link check. `status` 0 = unreachable, then `error` says why. */
data class LinkResult(val status: Int, val finalUrl: String, val error: String? = null)

data class LinkCheckProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val dead: Int = 0,
    val redirects: Int = 0,
    val current: String? = null,
)

/**
 * Fast check of all links without a browser: follows redirects itself (also https→http), remembers the
 * target and distinguishes DNS errors (domain gone), connection errors, timeouts and SSL problems.
 * At most one request per domain at a time.
 */
class LinkChecker(
    private val db: BookmarksDb,
    private val dbLock: Mutex = Mutex(),
    private val onChanged: (Long) -> Unit = {},
) : Closeable {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val domainLocks = ConcurrentHashMap<String, Semaphore>()
    private var job: Job? = null
    /** Pause switch of the running batch. */
    val pause = PauseGate()

    private val _progress = MutableStateFlow(LinkCheckProgress())
    val progress: StateFlow<LinkCheckProgress> = _progress

    /** Checks [ids] or all bookmarks (longest unchecked first). */
    fun start(ids: List<Long>? = null, parallel: Int = 24) {
        if (job?.isActive == true) return
        val all = db.bookmarkQueries.linkCheckTargets().executeAsList()
        // "Check all" skips marked ones; explicitly chosen entries are always checked
        val targets = if (ids == null) all.filter { it.link_ignore == 0L } else ids.toSet().let { s -> all.filter { it.id in s } }
        if (targets.isEmpty()) return
        pause.resume()
        _progress.value = LinkCheckProgress(running = true, total = targets.size)
        job = scope.launch {
            val ch = Channel<Pair<Long, String>>(Channel.UNLIMITED)
            targets.forEach { ch.trySend(it.id to it.url) }
            ch.close()
            val done = AtomicInteger()
            val dead = AtomicInteger()
            val redirects = AtomicInteger()
            coroutineScope {
                repeat(parallel.coerceIn(1, 64)) {
                    launch {
                        for ((id, url) in ch) {
                            if (!isActive) break
                            pause.await()
                            val r = checkWithRetry(url)
                            if (r.status == 0 || r.status == 404 || r.status == 410) dead.incrementAndGet()
                            if (r.status in 200..299 && Urls.isMeaningfulRedirect(url, r.finalUrl)) redirects.incrementAndGet()
                            dbLock.withLock {
                                db.bookmarkQueries.setLinkCheck(
                                    r.status.toLong(), r.error, r.finalUrl.takeIf { it != url && r.status != 0 },
                                    System.currentTimeMillis() / 1000, id,
                                )
                            }
                            onChanged(id)
                            _progress.update { it.copy(done = done.incrementAndGet(), dead = dead.get(), redirects = redirects.get(), current = Urls.domain(url)) }
                        }
                    }
                }
            }
        }.also { j -> j.invokeOnCompletion { _progress.update { it.copy(running = false, current = null) } } }
    }

    fun stop() {
        val j = job ?: return
        scope.launch { j.cancelAndJoin() }
    }

    private suspend fun checkWithRetry(url: String): LinkResult {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return LinkResult(200, url)
        val host = Urls.domain(url) ?: return LinkResult(0, url, "url")
        val lock = domainLocks.getOrPut(host) { Semaphore(1) }
        var r = lock.withPermit { check(url) }
        // retry timeouts and server errors once – often only temporary
        if (r.error == "timeout" || r.error == "connect" || r.status in 500..599 || r.status == 429) {
            delay(1500)
            r = lock.withPermit { check(url) }
        }
        return r
    }

    /** One check (blocking): follows up to 8 redirects. */
    fun check(url: String): LinkResult {
        var current = url.trim()
        repeat(9) { hop ->
            val uri = runCatching { URI(current.replace(" ", "%20")) }.getOrElse { return LinkResult(0, current, "url") }
            val req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                // SourceForge: Cloudflare only checks supposed browsers, an honest identifier gets through
                .header("User-Agent", if (Urls.domain(current) == "sourceforge.net") SourceForgeClient.USER_AGENT else Http.USER_AGENT)
                .header("Accept", Http.ACCEPT_HTML)
                .header("Accept-Language", PageLang.acceptLanguage)
                .GET().build()
            val resp = try {
                client.send(req, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: Exception) {
                return LinkResult(0, current, classify(e))
            }
            // do not load the body completely
            runCatching { resp.body().use { it.readNBytes(4096) } }
            val code = resp.statusCode()
            if (code in 300..399) {
                val loc = resp.headers().firstValue("Location").orElse(null) ?: return LinkResult(code, current)
                val next = runCatching { uri.resolve(loc.trim().replace(" ", "%20")).toString() }.getOrNull() ?: return LinkResult(code, current)
                if (hop == 8) return LinkResult(0, current, "loop")
                current = next
                return@repeat
            }
            return LinkResult(code, current)
        }
        return LinkResult(0, current, "loop")
    }

    /** Error type across the whole cause chain – the outer exception is usually just a ConnectException. */
    private fun classify(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(10).toList()
        return when {
            chain.any { it is UnknownHostException || it is java.nio.channels.UnresolvedAddressException } -> "dns"
            chain.any { it is HttpConnectTimeoutException || it is HttpTimeoutException } -> "timeout"
            chain.any { it is SSLException } -> "ssl"
            else -> "connect"
        }
    }

    override fun close() {
        runBlocking { job?.cancelAndJoin() }
        scope.cancel()
    }
}
