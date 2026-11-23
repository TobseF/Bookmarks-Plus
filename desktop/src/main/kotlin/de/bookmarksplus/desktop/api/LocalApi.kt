package de.bookmarksplus.desktop.api

import de.bookmarksplus.i18n.tr
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

// ------------------------------------------------------------------------------------------------ Data formats

@Serializable data class ApiStatus(val app: String = "bookmarks-plus", val version: Int = 3, val authorized: Boolean)
@Serializable data class PairRequest(val client: String = "Browser")
@Serializable data class PairStart(val requestId: String, val code: String)
@Serializable data class PairResult(val status: String, val token: String? = null)
@Serializable data class ApiFolder(val id: Long, val path: String)
@Serializable data class ApiTag(val name: String, val color: String)
@Serializable data class ApiItem(
    val id: Long, val url: String, val title: String, val domain: String? = null,
    val folders: List<ApiFolder> = emptyList(), val tags: List<String> = emptyList(),
    val description: String? = null, val summary: String? = null, val stars: Long? = null, val trashed: Boolean = false,
    // from version 2 (side panel/manager)
    val originalTitle: String? = null,
    val hasIcon: Boolean = false, val hasThumbnail: Boolean = false, val hasCard: Boolean = false,
    val repoUrl: String? = null, val websiteUrl: String? = null, val lastCommit: Long? = null, val lastRelease: String? = null,
    val addDate: Long? = null, val contentType: String? = null, val health: String? = null,
    /** Only in folder lists: placement in this folder and whether it is a link (not primary). */
    val placementId: Long? = null, val linked: Boolean = false,
    // from version 3 (programs)
    val appName: String? = null, val flathubUrl: String? = null, val alternativetoUrl: String? = null,
)
/**
 * Opened Flathub/AlternativeTo page: without [apply]/[create] only evaluate and name matching bookmarks;
 * with [apply] adopt the data into this bookmark; with [create] create a new one (website of the program,
 * otherwise the page itself) and adopt. [html] is required for AlternativeTo (the extension sends the loaded page).
 */
@Serializable data class AppPageRequest(
    val url: String, val html: String? = null, val apply: Long? = null, val create: Boolean = false,
    /** Adopt data into all bookmarks of the same domain; the value is the expected count (protection against a stale state). */
    val applyDomain: Int? = null,
)
/** [group]: `exact` (stored, same page/repo), `domain` (same domain only) or `other` (name/title). */
@Serializable data class AppPageMatch(val item: ApiItem, val reason: String, val linked: Boolean, val group: String = "other")
@Serializable data class AppPageResponse(
    val source: String, val name: String, val website: String? = null, val repo: String? = null, val summary: String? = null,
    val matches: List<AppPageMatch> = emptyList(), val applied: ApiItem? = null, val message: String? = null,
    /** An exact match exists (then the domain search is dropped). */
    val exact: Boolean = false,
    /** Domain of the program website and number of all bookmarks on it (at most three are displayed). */
    val domain: String? = null, val domainTotal: Int = 0, val appliedCount: Int = 0,
)
/** Folder in the tree: [count] = bookmarks incl. subfolders, [direct] = directly contained ones. */
@Serializable data class ApiTreeFolder(val id: Long, val parentId: Long?, val title: String, val count: Int, val direct: Int)
@Serializable data class ApiTree(val rootId: Long?, val inboxId: Long?, val folders: List<ApiTreeFolder>)
@Serializable data class LinkRequest(val folderId: Long)
/** [domainMatches] (at most three) and [domainIds] (all) only with `domain=1` and if the URL itself is not stored. */
@Serializable data class LookupResponse(
    val exists: Boolean, val item: ApiItem? = null,
    val domain: String? = null, val domainMatches: List<ApiItem> = emptyList(), val domainIds: List<Long> = emptyList(),
)
@Serializable data class SaveRequest(
    val url: String, val title: String = "", val description: String? = null,
    val folderId: Long? = null, val tags: List<String> = emptyList(),
)
@Serializable data class SaveResponse(val created: Boolean, val item: ApiItem)
@Serializable data class UpdateRequest(
    val title: String? = null, val description: String? = null, val folderId: Long? = null, val tags: List<String>? = null,
    /** New address (http/https only). */
    val url: String? = null,
)
@Serializable data class BrowserNode(val title: String = "", val url: String? = null, val dateAdded: Double? = null, val children: List<BrowserNode>? = null)
@Serializable data class ImportRequest(val name: String = "Browser", val root: BrowserNode)
@Serializable data class ImportResponse(val links: Int, val created: Int, val linked: Int, val folderId: Long)
@Serializable data class ApiError(val error: String)

/** What the local interface needs from the app (in the app: [de.bookmarksplus.desktop.AppState]). */
interface ApiBackend {
    /** Shows the pairing request with [code]; returns true if the user agrees. */
    fun requestPairing(client: String, code: String): CompletableFuture<Boolean>
    fun tokenHashes(): Set<String>
    fun addToken(client: String, hash: String)
    fun lookup(url: String): ApiItem?
    /** All bookmarks (without trash) on the same domain as [url], newest first. */
    fun byDomain(url: String): List<ApiItem> = emptyList()
    fun save(req: SaveRequest): SaveResponse
    fun update(id: Long, req: UpdateRequest): ApiItem?
    /** Replaces the thumbnail (image data as PNG/JPEG/WebP); false if unknown or not an image. */
    fun setThumbnail(id: Long, image: ByteArray): Boolean
    /** [folderId] restricts to a folder including subfolders; [sort] like `SortField` (added, title, stars, modified, …). */
    fun search(query: String, limit: Int, folderId: Long? = null, sort: String? = null): List<ApiItem>
    fun folders(): List<ApiFolder>
    fun tree(): ApiTree
    /** Bookmarks directly in [folderId], in folder order; null if the folder is unknown. */
    fun folderItems(folderId: Long): List<ApiItem>?
    /** Image of a bookmark: kind = icon | thumbnail | card | preview (card, otherwise thumbnail). Returns bytes + MIME type. */
    fun media(id: Long, kind: String): Pair<ByteArray, String>?
    fun link(id: Long, folderId: Long): ApiItem?
    /** Bookmark including links to the trash. */
    fun trash(id: Long): Boolean
    fun restore(id: Long): Boolean
    fun tags(): List<ApiTag>
    fun importBrowser(req: ImportRequest): ImportResponse
    /** Evaluate or adopt Flathub/AlternativeTo page (from API v3); null = no program page recognized. */
    fun appPage(req: AppPageRequest): AppPageResponse? = null
    /** Page jobs for the extension (from API v3), e.g. for AlternativeTo. */
    val bridge: ExtensionBridge? get() = null
}

/**
 * Local HTTP interface for the browser extension. Listens on 127.0.0.1 only. All calls except
 * `/api/status` and `/api/pair` need `Authorization: Bearer <token>`; tokens are only created after consent
 * in the app and are stored exclusively as SHA-256. Without CORS headers, web pages cannot read the responses;
 * pairing only accepts requests from browser extensions.
 */
class LocalApi(private val backend: ApiBackend, private val preferredPort: Int = DEFAULT_PORT) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private var server: HttpServer? = null
    private val random = SecureRandom()
    private class Pending(val client: String, val result: CompletableFuture<Boolean>, val created: Long, @Volatile var token: String? = null)
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    val port: Int? get() = server?.address?.port

    /** Starts on the preferred port or one of the next four. */
    fun start(): Int {
        var lastError: Exception? = null
        for (p in preferredPort..preferredPort + 4) {
            try {
                val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), p), 16)
                s.createContext("/api/") { ex -> handle(ex) }
                s.executor = Executors.newFixedThreadPool(6) { r -> Thread(r, "local-api").apply { isDaemon = true } }
                s.start()
                server = s
                return p
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(tr("api.noPort", preferredPort), lastError)
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path.removePrefix("/api")
            val method = ex.requestMethod
            if (method == "OPTIONS") return send(ex, 204, null)
            when {
                path == "/status" && method == "GET" -> send(ex, 200, json.encodeToString(ApiStatus.serializer(), ApiStatus(authorized = authorized(ex))))
                path == "/pair" && method == "POST" -> pairStart(ex)
                path.startsWith("/pair/") && method == "GET" -> pairPoll(ex, path.removePrefix("/pair/"))
                !authorized(ex) -> error(ex, 401, tr("api.notPaired"))
                path == "/lookup" && method == "GET" -> {
                    val url = query(ex)["url"] ?: return error(ex, 400, tr("api.urlMissing"))
                    val item = backend.lookup(url)
                    val same = if (item == null && query(ex)["domain"] == "1") backend.byDomain(url) else emptyList()
                    val resp = LookupResponse(
                        item != null, item, domain = same.firstOrNull()?.domain,
                        domainMatches = same.take(3), domainIds = same.map { it.id },
                    )
                    send(ex, 200, json.encodeToString(LookupResponse.serializer(), resp))
                }
                path == "/bookmarks" && method == "POST" -> {
                    val req = json.decodeFromString(SaveRequest.serializer(), body(ex))
                    if (!req.url.startsWith("http://") && !req.url.startsWith("https://")) return error(ex, 400, tr("api.httpOnly"))
                    send(ex, 200, json.encodeToString(SaveResponse.serializer(), backend.save(req)))
                }
                path.startsWith("/bookmarks/") && method == "PATCH" -> {
                    val id = path.removePrefix("/bookmarks/").toLongOrNull() ?: return error(ex, 400, tr("api.idMissing"))
                    val item = backend.update(id, json.decodeFromString(UpdateRequest.serializer(), body(ex))) ?: return error(ex, 404, tr("api.notFound"))
                    send(ex, 200, json.encodeToString(ApiItem.serializer(), item))
                }
                path.startsWith("/bookmarks/") && path.endsWith("/thumbnail") && method == "PUT" -> {
                    val id = path.removePrefix("/bookmarks/").removeSuffix("/thumbnail").toLongOrNull() ?: return error(ex, 400, tr("api.idMissing"))
                    val bytes = ex.requestBody.use { it.readNBytes((10 shl 20) + 1) }
                    if (bytes.size > 10 shl 20) return error(ex, 413, tr("api.imageTooLarge"))
                    if (!backend.setThumbnail(id, bytes)) return error(ex, 422, tr("api.invalidImage"))
                    send(ex, 200, "{}")
                }
                path.startsWith("/bookmarks/") && path.endsWith("/link") && method == "POST" -> {
                    val id = path.removePrefix("/bookmarks/").removeSuffix("/link").toLongOrNull() ?: return error(ex, 400, tr("api.idMissing"))
                    val req = json.decodeFromString(LinkRequest.serializer(), body(ex))
                    val item = backend.link(id, req.folderId) ?: return error(ex, 404, tr("api.notFound"))
                    send(ex, 200, json.encodeToString(ApiItem.serializer(), item))
                }
                path.startsWith("/bookmarks/") && path.endsWith("/restore") && method == "POST" -> {
                    val id = path.removePrefix("/bookmarks/").removeSuffix("/restore").toLongOrNull() ?: return error(ex, 400, tr("api.idMissing"))
                    if (!backend.restore(id)) return error(ex, 404, tr("api.notFound"))
                    send(ex, 200, "{}")
                }
                path.startsWith("/bookmarks/") && method == "DELETE" -> {
                    val id = path.removePrefix("/bookmarks/").toLongOrNull() ?: return error(ex, 400, tr("api.idMissing"))
                    if (!backend.trash(id)) return error(ex, 404, tr("api.notFound"))
                    send(ex, 200, "{}")
                }
                path.startsWith("/media/") && method == "GET" -> {
                    // /media/{id}/{kind}
                    val parts = path.removePrefix("/media/").split('/')
                    val id = parts.getOrNull(0)?.toLongOrNull() ?: return error(ex, 400, tr("api.idMissing"))
                    val (bytes, mime) = backend.media(id, parts.getOrNull(1) ?: "preview") ?: return error(ex, 404, tr("api.noImage"))
                    sendBytes(ex, bytes, mime)
                }
                path == "/search" && method == "GET" -> {
                    val q = query(ex)
                    val limit = q["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 30
                    val items = backend.search(q["q"].orEmpty(), limit, q["folder"]?.toLongOrNull(), q["sort"])
                    send(ex, 200, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ApiItem.serializer()), items))
                }
                path == "/tree" && method == "GET" ->
                    send(ex, 200, json.encodeToString(ApiTree.serializer(), backend.tree()))
                path.startsWith("/folders/") && path.endsWith("/items") && method == "GET" -> {
                    val id = path.removePrefix("/folders/").removeSuffix("/items").toLongOrNull() ?: return error(ex, 400, tr("api.idMissing"))
                    val items = backend.folderItems(id) ?: return error(ex, 404, tr("api.folderUnknown"))
                    send(ex, 200, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ApiItem.serializer()), items))
                }
                path == "/folders" && method == "GET" ->
                    send(ex, 200, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ApiFolder.serializer()), backend.folders()))
                path == "/tags" && method == "GET" ->
                    send(ex, 200, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ApiTag.serializer()), backend.tags()))
                path == "/import" && method == "POST" -> {
                    val req = json.decodeFromString(ImportRequest.serializer(), body(ex, maxBytes = 50 shl 20))
                    send(ex, 200, json.encodeToString(ImportResponse.serializer(), backend.importBrowser(req)))
                }
                path == "/apps/page" && method == "POST" -> {
                    val req = json.decodeFromString(AppPageRequest.serializer(), body(ex, maxBytes = 8 shl 20))
                    val r = backend.appPage(req) ?: return error(ex, 422, tr("api.noAppPage"))
                    send(ex, 200, json.encodeToString(AppPageResponse.serializer(), r))
                }
                path == "/tasks/next" && method == "GET" -> {
                    val b = backend.bridge ?: return send(ex, 204, null)
                    val wait = query(ex)["wait"]?.toLongOrNull()?.times(1000) ?: 0L
                    val task = b.next(wait, ex.requestHeaders.getFirst("Origin"))
                    if (task == null) send(ex, 204, null) else send(ex, 200, json.encodeToString(ApiTask.serializer(), task))
                }
                path.startsWith("/tasks/") && method == "POST" -> {
                    val b = backend.bridge ?: return error(ex, 404, tr("api.noJobs"))
                    val r = json.decodeFromString(ApiTaskResult.serializer(), body(ex, maxBytes = 8 shl 20))
                    if (b.complete(path.removePrefix("/tasks/"), r)) send(ex, 204, null) else error(ex, 404, tr("api.jobUnknown"))
                }
                else -> error(ex, 404, tr("api.unknownRoute", method, path))
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            error(ex, 400, tr("api.badRequest", e.message))
        } catch (e: Exception) {
            error(ex, 500, e.message ?: e::class.simpleName ?: tr("api.error"))
        }
    }

    /**
     * Pairing, step 1 (from extensions only): immediately returns a code that the extension displays;
     * the app asks the user with the same code.
     */
    private fun pairStart(ex: HttpExchange) {
        val origin = ex.requestHeaders.getFirst("Origin").orEmpty()
        if (!(origin.startsWith("chrome-extension://") || origin.startsWith("moz-extension://") || origin.startsWith("extension://"))) {
            return error(ex, 403, tr("api.pairOnlyExtension"))
        }
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.created > 5 * 60_000 }
        if (pending.values.any { !it.result.isDone }) return error(ex, 409, tr("api.pairRunning"))
        val req = json.decodeFromString(PairRequest.serializer(), body(ex).ifBlank { "{}" })
        val code = (1000 + random.nextInt(9000)).toString()
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))
        val client = req.client.take(60)
        val p = Pending(client, backend.requestPairing(client, code), now)
        p.result.thenAccept { ok ->
            if (ok) {
                val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
                backend.addToken(client, sha256(token))
                p.token = token
            }
        }
        pending[id] = p
        send(ex, 200, json.encodeToString(PairStart.serializer(), PairStart(id, code)))
    }

    /** Pairing, step 2: query status; the token is delivered exactly once. */
    private fun pairPoll(ex: HttpExchange, id: String) {
        val p = pending[id] ?: return send(ex, 200, json.encodeToString(PairResult.serializer(), PairResult("unknown")))
        val result = when {
            !p.result.isDone -> PairResult("pending")
            p.token != null -> PairResult("approved", p.token).also { pending.remove(id) }
            p.result.getNow(false) -> PairResult("pending") // token is being generated
            else -> PairResult("denied").also { pending.remove(id) }
        }
        send(ex, 200, json.encodeToString(PairResult.serializer(), result))
    }

    private fun authorized(ex: HttpExchange): Boolean {
        val token = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val h = sha256(token)
        return backend.tokenHashes().any { MessageDigest.isEqual(it.toByteArray(), h.toByteArray()) }
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        ex.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

    private fun body(ex: HttpExchange, maxBytes: Int = 2 shl 20): String {
        val bytes = ex.requestBody.use { it.readNBytes(maxBytes + 1) }
        require(bytes.size <= maxBytes) { tr("api.tooLarge") }
        return String(bytes, Charsets.UTF_8)
    }

    private fun error(ex: HttpExchange, code: Int, message: String) = send(ex, code, json.encodeToString(ApiError.serializer(), ApiError(message)))

    private fun sendBytes(ex: HttpExchange, bytes: ByteArray, mime: String) {
        ex.responseHeaders.add("Content-Type", mime)
        ex.responseHeaders.add("Cache-Control", "private, max-age=300")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    private fun send(ex: HttpExchange, code: Int, body: String?) {
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        if (body == null) {
            ex.sendResponseHeaders(code, -1)
        } else {
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        ex.close()
    }

    companion object {
        const val DEFAULT_PORT = 47811
        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
