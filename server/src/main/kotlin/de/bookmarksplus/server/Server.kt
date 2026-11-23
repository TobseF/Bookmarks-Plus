package de.bookmarksplus.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Bookmarks Plus sync server: stores only the encrypted files of the clients, per user
 * in a separate folder. It knows neither passphrase nor contents.
 *
 *   GET    /api/v1/blobs?prefix=changes/   → file names, one per line
 *   GET    /api/v1/blobs/{name}            → content
 *   PUT    /api/v1/blobs/{name}            → store
 *   DELETE /api/v1/blobs/{name}            → delete
 *
 * Entries (v2, one encrypted entry per bookmark/folder/tag; see [RecordDb]):
 *   GET    /api/v2/records?since=<seq>&limit=<n>  → {records:[{id,rev,seq,updated,deleted}], next, more, reset}
 *   POST   /api/v2/records/get   {ids:[…]}         → {records:[{id,rev,data(Base64)}]}
 *   POST   /api/v2/records/put   {items:[{id,base,data}]} → {results:[{id,ok,rev,seq,deleted}]}
 *   GET    /api/v2/info                           → {version, count, seq}
 *   GET    /health                                → ok (without login)
 */
class ServerConfig(
    val dataDir: File,
    /** User → password hash (format of [Passwords.hash]). */
    val users: Map<String, String>,
    val maxBlobBytes: Long = 200L shl 20,
    /** Size of a single entry (bookmark incl. README). */
    val maxRecordBytes: Int = 4 shl 20,
)

object Passwords {
    private const val ITERATIONS = 210_000

    /** `pbkdf2$<iterations>$<salt>$<hash>` (PBKDF2-HMAC-SHA256). */
    fun hash(password: String, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        return "pbkdf2\$$iterations\$${b64(salt)}\$${b64(derive(password, salt, iterations))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val p = stored.split('$')
        if (p.size != 4 || p[0] != "pbkdf2") return false
        val expected = Base64.getDecoder().decode(p[3])
        return MessageDigest.isEqual(expected, derive(password, Base64.getDecoder().decode(p[2]), p[1].toInt()))
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}

private val VALID_NAME = Regex("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$")
private val VALID_USER = Regex("^[A-Za-z0-9._-]{1,64}$")

fun Application.bookmarksServer(cfg: ServerConfig) {
    install(StatusPages) {
        exception<Throwable> { call, e ->
            call.application.environment.log.warn(msg("log.error"), e)
            call.respondText(msg("http.internalError"), status = HttpStatusCode.InternalServerError)
        }
    }
    install(Authentication) {
        basic("users") {
            realm = "bookmarks-plus"
            validate { cred ->
                val stored = cfg.users[cred.name]
                if (stored != null && VALID_USER.matches(cred.name) && Passwords.verify(cred.password, stored)) UserIdPrincipal(cred.name)
                else { delay(800); null } // throttle guessing
            }
        }
    }
    val recordDbs = ConcurrentHashMap<String, RecordDb>()
    fun records(user: String): RecordDb = recordDbs.computeIfAbsent(user) { RecordDb(File(File(cfg.dataDir, it), "records.db")).also { db -> db.purge() } }
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) { recordDbs.values.forEach { runCatching { it.close() } } }

    routing {
        get("/health") { call.respondText("ok") }
        authenticate("users") {
            fun json(o: JsonObject) = o.toString()

            get("/api/v2/info") {
                val db = records(call.principal<UserIdPrincipal>()!!.name)
                call.respondText(json(buildJsonObject { put("version", 2); put("count", db.count()); put("seq", db.maxSeq) }), ContentType.Application.Json)
            }
            get("/api/v2/records") {
                val db = records(call.principal<UserIdPrincipal>()!!.name)
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 1000).coerceIn(1, 5000)
                // anyone who has been absent since before old tombstones were removed gets the full list
                val reset = since in 1 until db.purgedSeq
                val list = withContext(Dispatchers.IO) { db.changes(if (reset) 0 else since, limit) }
                val body = buildJsonObject {
                    put("records", JsonArray(list.map { m ->
                        buildJsonObject { put("id", m.id); put("rev", m.rev); put("seq", m.seq); put("updated", m.updated); put("deleted", m.deleted) }
                    }))
                    put("next", list.lastOrNull()?.seq ?: if (reset) 0 else since)
                    put("more", list.size == limit)
                    put("reset", reset)
                }
                call.respondText(json(body), ContentType.Application.Json)
            }
            post("/api/v2/records/get") {
                val db = records(call.principal<UserIdPrincipal>()!!.name)
                val ids = Json.parseToJsonElement(call.receiveText()).jsonObject["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                if (ids.size > 1000 || ids.any { !RecordDb.VALID_ID.matches(it) }) return@post call.respondText(msg("http.invalidIds"), status = HttpStatusCode.BadRequest)
                val found = withContext(Dispatchers.IO) { db.get(ids) }
                val body = buildJsonObject {
                    put("records", JsonArray(found.map { (m, data) ->
                        buildJsonObject { put("id", m.id); put("rev", m.rev); put("seq", m.seq); put("data", Base64.getEncoder().encodeToString(data)) }
                    }))
                }
                call.respondText(json(body), ContentType.Application.Json)
            }
            post("/api/v2/records/put") {
                val db = records(call.principal<UserIdPrincipal>()!!.name)
                val text = call.receiveText()
                if (text.length > cfg.maxBlobBytes) return@post call.respondText(msg("http.tooLarge"), status = HttpStatusCode.PayloadTooLarge)
                val items = Json.parseToJsonElement(text).jsonObject["items"]?.jsonArray.orEmpty().map { el ->
                    val o = el.jsonObject
                    PutItem(
                        id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        base = (o["base"] as? JsonPrimitive)?.contentOrNull,
                        data = (o["data"] as? JsonPrimitive)?.contentOrNull?.let { Base64.getDecoder().decode(it) },
                    )
                }
                if (items.size > 1000 || items.any { !RecordDb.VALID_ID.matches(it.id) }) return@post call.respondText(msg("http.invalidIds"), status = HttpStatusCode.BadRequest)
                if (items.any { (it.data?.size ?: 0) > cfg.maxRecordBytes }) return@post call.respondText(msg("http.entryTooLarge"), status = HttpStatusCode.PayloadTooLarge)
                val results = withContext(Dispatchers.IO) { db.put(items) }
                val body = buildJsonObject {
                    put("results", JsonArray(results.map { r ->
                        when (r) {
                            is PutResult.Ok -> buildJsonObject { put("id", r.id); put("ok", true); put("rev", r.rev); put("seq", r.seq) }
                            is PutResult.Conflict -> buildJsonObject {
                                put("id", r.id); put("ok", false); put("rev", r.rev?.let(::JsonPrimitive) ?: JsonNull); put("deleted", r.deleted)
                            }
                        }
                    }))
                }
                call.respondText(json(body), ContentType.Application.Json)
            }

            fun userDir(user: String) = File(cfg.dataDir, user).apply { mkdirs() }
            fun resolve(user: String, name: String?): File? {
                if (name == null || !VALID_NAME.matches(name) || ".." in name) return null
                return File(userDir(user), name)
            }

            get("/api/v1/blobs") {
                val user = call.principal<UserIdPrincipal>()!!.name
                val prefix = call.request.queryParameters["prefix"].orEmpty()
                if (prefix.isNotEmpty() && !Regex("^[A-Za-z0-9._/-]*$").matches(prefix) || ".." in prefix) {
                    return@get call.respondText(msg("http.invalidPrefix"), status = HttpStatusCode.BadRequest)
                }
                val root = userDir(user)
                val names = withContext(Dispatchers.IO) {
                    root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }
                        .map { it.relativeTo(root).invariantSeparatorsPath }
                        .filter { it.startsWith(prefix) }.sorted().toList()
                }
                call.respondText(names.joinToString("\n"), ContentType.Text.Plain)
            }
            get("/api/v1/blobs/{name...}") {
                val user = call.principal<UserIdPrincipal>()!!.name
                val f = resolve(user, call.parameters.getAll("name")?.joinToString("/"))
                    ?: return@get call.respondText(msg("http.invalidName"), status = HttpStatusCode.BadRequest)
                if (!f.isFile) return@get call.respondText(msg("http.notFound"), status = HttpStatusCode.NotFound)
                call.respondBytes(withContext(Dispatchers.IO) { f.readBytes() }, ContentType.Application.OctetStream)
            }
            put("/api/v1/blobs/{name...}") {
                val user = call.principal<UserIdPrincipal>()!!.name
                val f = resolve(user, call.parameters.getAll("name")?.joinToString("/"))
                    ?: return@put call.respondText(msg("http.invalidName"), status = HttpStatusCode.BadRequest)
                val tmp = File(f.parentFile, f.name + ".tmp")
                val ok = withContext(Dispatchers.IO) {
                    f.parentFile.mkdirs()
                    var total = 0L
                    call.receiveChannel().toInputStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > cfg.maxBlobBytes) return@withContext false
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    f.delete()
                    tmp.renameTo(f)
                }
                if (!ok) { tmp.delete(); return@put call.respondText(msg("http.tooLarge"), status = HttpStatusCode.PayloadTooLarge) }
                call.respondText("ok", status = HttpStatusCode.Created)
            }
            delete("/api/v1/blobs/{name...}") {
                val user = call.principal<UserIdPrincipal>()!!.name
                val f = resolve(user, call.parameters.getAll("name")?.joinToString("/"))
                    ?: return@delete call.respondText(msg("http.invalidName"), status = HttpStatusCode.BadRequest)
                withContext(Dispatchers.IO) { f.delete() }
                call.respondText("ok")
            }
        }
    }
}

/** Reads `users.txt`: per line `name:hash` (comments with #). */
fun loadUsers(file: File): Map<String, String> =
    if (!file.isFile) emptyMap()
    else file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && ':' in it }
        .associate { it.substringBefore(':') to it.substringAfter(':') }

fun main(args: Array<String>) {
    val dataDir = File(System.getenv("BMP_DATA") ?: "data")
    val usersFile = File(System.getenv("BMP_USERS") ?: File(dataDir, "users.txt").path)
    when (args.firstOrNull()) {
        "add-user" -> {
            val name = args.getOrNull(1) ?: return println(msg("cli.usageAddUser"))
            require(VALID_USER.matches(name)) { msg("cli.invalidUser") }
            val password = args.getOrNull(2) ?: System.console()?.readPassword(msg("cli.passwordPrompt", name))?.let(::String)
                ?: return println(msg("cli.noPassword"))
            usersFile.parentFile?.mkdirs()
            val others = loadUsers(usersFile).filterKeys { it != name }
            usersFile.writeText((others + (name to Passwords.hash(password))).entries.joinToString("\n", postfix = "\n") { "${it.key}:${it.value}" })
            println(msg("cli.userSaved", name, usersFile.absolutePath))
            return
        }
        "hash-password" -> { println(Passwords.hash(args.getOrNull(1) ?: return println(msg("cli.usageHash")))); return }
    }
    val users = loadUsers(usersFile)
    if (users.isEmpty()) println(msg("cli.noUsers", usersFile.absolutePath))
    val port = System.getenv("BMP_PORT")?.toIntOrNull() ?: 8787
    val host = System.getenv("BMP_HOST") ?: "0.0.0.0"
    println(msg("cli.started", host, port, dataDir.absolutePath))
    embeddedServer(Netty, port = port, host = host) {
        bookmarksServer(ServerConfig(dataDir, users))
    }.start(wait = true)
}
