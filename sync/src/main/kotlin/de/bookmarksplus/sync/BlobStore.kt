package de.bookmarksplus.sync

import de.bookmarksplus.i18n.tr
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Duration
import java.util.Base64

class SyncTargetException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Storage for encrypted files. Names are relative paths like `changes/123-abc.bin`.
 * All targets only see encrypted contents.
 */
interface BlobStore : Closeable {
    /** Names of all files under [prefix] (e.g. `changes/`), sorted. */
    fun list(prefix: String): List<String>
    fun get(name: String): ByteArray?
    fun put(name: String, data: ByteArray)
    fun delete(name: String)
    override fun close() {}

    companion object {
        private val VALID = Regex("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$")
        fun checkName(name: String) = require(VALID.matches(name) && ".." !in name) { tr("sync.blob.invalidName", name) }
    }
}

/** Local folder – e.g. a folder synchronized by OneDrive, Dropbox or Nextcloud. */
class FolderStore(private val root: File) : BlobStore {
    override fun list(prefix: String): List<String> {
        val dir = File(root, prefix.substringBeforeLast('/', ""))
        val base = prefix.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        return dir.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }.map { base + it.name }
            .filter { it.startsWith(prefix) }.sorted()
    }

    override fun get(name: String): ByteArray? {
        BlobStore.checkName(name)
        return File(root, name).takeIf { it.isFile }?.readBytes()
    }

    override fun put(name: String, data: ByteArray) {
        BlobStore.checkName(name)
        val f = File(root, name)
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw SyncTargetException(tr("sync.blob.writeFailed", name)) }
    }

    override fun delete(name: String) {
        BlobStore.checkName(name)
        File(root, name).delete()
    }
}

/** Own Bookmarks Plus server (Ktor) with username/password (HTTP Basic, preferably behind HTTPS). */
class ServerStore(baseUrl: String, user: String, password: String) : BlobStore {
    private val http = ServerHttp(baseUrl, user, password)

    private fun encName(name: String) = name.split('/').joinToString("/") { ServerHttp.enc(it) }

    override fun list(prefix: String): List<String> {
        val r = http.send("GET", "/api/v1/blobs?prefix=" + ServerHttp.enc(prefix))
        if (r.status != 200) throw SyncTargetException("Server: HTTP ${r.status}")
        return String(r.body, Charsets.UTF_8).lines().filter { it.isNotBlank() }.sorted()
    }

    override fun get(name: String): ByteArray? {
        BlobStore.checkName(name)
        val r = http.send("GET", "/api/v1/blobs/" + encName(name))
        return when (r.status) {
            200 -> r.body
            404 -> null
            else -> throw SyncTargetException("Server: HTTP ${r.status}")
        }
    }

    override fun put(name: String, data: ByteArray) {
        BlobStore.checkName(name)
        val r = http.send("PUT", "/api/v1/blobs/" + encName(name), data)
        if (r.status !in 200..299) throw SyncTargetException(tr("sync.server.uploadHttp", r.status))
    }

    override fun delete(name: String) {
        BlobStore.checkName(name)
        http.send("DELETE", "/api/v1/blobs/" + encName(name))
    }
}

/**
 * HTTP access to the own server via [HttpURLConnection] so that the same code also runs on Android
 * (where `java.net.http` is missing).
 */
class ServerHttp(baseUrl: String, user: String, password: String) {
    private val base = baseUrl.trim().trimEnd('/')
    private val auth = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    class Resp(val status: Int, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    fun send(method: String, path: String, body: ByteArray? = null, contentType: String = "application/octet-stream"): Resp {
        val resp = try {
            val conn = URI(base + path).toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 5 * 60_000
                conn.setRequestProperty("Authorization", auth)
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (body != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.setRequestProperty("Content-Type", contentType)
                    conn.outputStream.use { it.write(body) }
                }
                val status = conn.responseCode
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                Resp(status, stream?.use { it.readBytes() } ?: ByteArray(0))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            throw SyncTargetException(tr("sync.server.unreachable", e.message ?: e::class.simpleName), e)
        }
        when (resp.status) {
            401 -> throw SyncTargetException(tr("sync.server.badCredentials"))
            403 -> throw SyncTargetException(tr("sync.server.forbidden"))
            404 -> if (path.startsWith("/api/v2")) throw SyncTargetException(tr("sync.server.tooOld"))
            413 -> throw SyncTargetException(tr("sync.server.tooLarge"))
        }
        return resp
    }

    fun json(method: String, path: String, body: String? = null): String {
        val r = send(method, path, body?.toByteArray(Charsets.UTF_8), "application/json")
        if (r.status !in 200..299) throw SyncTargetException("Server: HTTP ${r.status} ${r.text.take(200)}")
        return r.text
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** FTP or FTPS (explicit, TLS). One connection per sync run. */
class FtpStore(
    private val host: String,
    private val port: Int = 21,
    private val user: String,
    private val password: String,
    baseDir: String = "/bookmarks-plus",
    private val tls: Boolean = true,
) : BlobStore {
    private val base = "/" + baseDir.trim('/').let { if (it.isEmpty()) "" else it }
    private var client: FTPClient? = null

    private fun ftp(): FTPClient {
        client?.takeIf { it.isConnected }?.let { return it }
        val c = if (tls) FTPSClient(false) else FTPClient()
        c.connectTimeout = 15_000
        c.setDataTimeout(Duration.ofSeconds(60))
        try {
            c.connect(host, port)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw SyncTargetException(tr("sync.ftp.refused", c.replyString.trim()))
            if (c is FTPSClient) { c.execPBSZ(0); c.execPROT("P") }
            if (!c.login(user, password)) throw SyncTargetException(tr("sync.ftp.loginFailed"))
            c.enterLocalPassiveMode()
            c.setFileType(FTP.BINARY_FILE_TYPE)
        } catch (e: SyncTargetException) {
            runCatching { c.disconnect() }; throw e
        } catch (e: Exception) {
            runCatching { c.disconnect() }; throw SyncTargetException(tr("sync.ftp.unreachable", e.message ?: e::class.simpleName), e)
        }
        client = c
        return c
    }

    private fun path(name: String) = if (base == "/") "/$name" else "$base/$name"

    private fun ensureDirs(dir: String) {
        val c = ftp()
        var cur = ""
        dir.trim('/').split('/').filter { it.isNotEmpty() }.forEach { part ->
            cur += "/$part"
            if (!c.changeWorkingDirectory(cur)) c.makeDirectory(cur)
        }
    }

    override fun list(prefix: String): List<String> {
        val dirPart = prefix.substringBeforeLast('/', "")
        val dir = path(dirPart).trimEnd('/').ifEmpty { "/" }
        val names = io { ftp().listNames(dir) }.orEmpty().map { it.substringAfterLast('/') }.filter { it.isNotBlank() && !it.endsWith(".tmp") }
        return names.map { if (dirPart.isEmpty()) it else "$dirPart/$it" }.filter { it.startsWith(prefix) }.sorted()
    }

    override fun get(name: String): ByteArray? {
        BlobStore.checkName(name)
        val out = ByteArrayOutputStream()
        val ok = io { ftp().retrieveFile(path(name), out) }
        return if (ok) out.toByteArray() else null
    }

    override fun put(name: String, data: ByteArray) {
        BlobStore.checkName(name)
        val p = path(name)
        ensureDirs(p.substringBeforeLast('/'))
        val tmp = "$p.tmp"
        val ok = io { ftp().storeFile(tmp, ByteArrayInputStream(data)) }
        if (!ok) throw SyncTargetException(tr("sync.ftp.uploadFailed", client?.replyString?.trim()))
        io { ftp().deleteFile(p); ftp().rename(tmp, p) }.let { if (!it) throw SyncTargetException(tr("sync.ftp.renameFailed")) }
    }

    override fun delete(name: String) {
        BlobStore.checkName(name)
        io { ftp().deleteFile(path(name)) }
    }

    private fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: SyncTargetException) {
        throw e
    } catch (e: IOException) {
        runCatching { client?.disconnect() }; client = null
        throw SyncTargetException(tr("sync.ftp.error", e.message), e)
    }

    override fun close() {
        client?.let { runCatching { it.logout() }; runCatching { it.disconnect() } }
        client = null
    }
}
