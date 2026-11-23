package de.bookmarksplus.desktop.api

import de.bookmarksplus.i18n.tr
import de.bookmarksplus.enrich.apps.BridgeResult
import de.bookmarksplus.enrich.apps.BrowserBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Serializable data class ApiTask(val id: String, val url: String, val kind: String = "html")
@Serializable data class ApiTaskResult(val status: Int = 0, val finalUrl: String? = null, val html: String? = null, val error: String? = null)

/**
 * "Load this page" jobs for the browser extension. The extension polls via long polling
 * (`GET /api/tasks/next?wait=20`) and reports the HTML back (`POST /api/tasks/<id>`). It loads the page in the
 * user's real browser – with its cookies and passed bot checks. For security reasons only for
 * allowed hosts ([ALLOWED_HOSTS]); the extension additionally checks this.
 */
class ExtensionBridge(private val clock: () -> Long = System::currentTimeMillis) : BrowserBridge {

    private class Task(val id: String, val url: String, val result: CompletableDeferred<BridgeResult?>)

    private val queue = LinkedBlockingQueue<Task>()
    private val inflight = ConcurrentHashMap<String, Task>()
    @Volatile var lastPoll: Long = 0
        private set
    @Volatile var lastClient: String? = null
        private set

    /** An extension has reported in during the last 75 s (it polls at least every 30 s). */
    override val available: Boolean get() = clock() - lastPoll < 75_000

    override suspend fun fetchHtml(url: String, timeoutMs: Long): BridgeResult? {
        require(allowed(url)) { tr("api.hostNotAllowed", url) }
        val t = Task(UUID.randomUUID().toString(), url, CompletableDeferred())
        queue.put(t)
        return try {
            withTimeoutOrNull(timeoutMs) { t.result.await() }
        } finally {
            queue.remove(t)
            inflight.remove(t.id)
        }
    }

    /** Next job for the extension; waits up to [waitMs] for one (long polling). */
    fun next(waitMs: Long, client: String?): ApiTask? {
        lastPoll = clock()
        client?.let { lastClient = it }
        val t = queue.poll(waitMs.coerceIn(0, 25_000), TimeUnit.MILLISECONDS) ?: return null
        lastPoll = clock()
        if (t.result.isCompleted) return null
        inflight[t.id] = t
        return ApiTask(t.id, t.url)
    }

    fun complete(id: String, r: ApiTaskResult): Boolean {
        lastPoll = clock()
        val t = inflight.remove(id) ?: return false
        t.result.complete(if (r.error != null && r.html == null) null else BridgeResult(r.status, r.finalUrl ?: t.url, r.html))
        return true
    }

    companion object {
        val ALLOWED_HOSTS = setOf("alternativeto.net")

        fun allowed(url: String): Boolean {
            val u = runCatching { URI(url) }.getOrNull() ?: return false
            val host = u.host?.lowercase()?.removePrefix("www.") ?: return false
            return u.scheme == "https" && host in ALLOWED_HOSTS
        }
    }
}
