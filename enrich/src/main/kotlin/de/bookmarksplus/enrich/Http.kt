package de.bookmarksplus.enrich

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

data class FetchResult(
    val status: Int,
    val finalUrl: String,
    val contentType: String?,
    val body: ByteArray?,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val ok: Boolean get() = status in 200..299
    val isHtml: Boolean get() = contentType?.contains("html", ignoreCase = true) == true
    fun text(): String? = body?.let { b ->
        val cs = contentType?.substringAfter("charset=", "")?.trim(';', ' ', '"')?.takeIf { it.isNotEmpty() }
        val charset = runCatching { cs?.let { Charset.forName(it) } }.getOrNull() ?: Charsets.UTF_8
        String(b, charset)
    }
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

/** Lean HTTP client (java.net.http) with browser-like headers and size limit. */
class Http(private val timeout: Duration = Duration.ofSeconds(15)) {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun get(url: String, maxBytes: Int = 3 shl 20, accept: String = ACCEPT_HTML, extraHeaders: Map<String, String> = emptyMap()): FetchResult {
        val uri = runCatching { URI(url.trim().replace(" ", "%20")) }.getOrElse { return FetchResult(0, url, null, null) }
        val req = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", accept)
            .header("Accept-Language", PageLang.acceptLanguage)
            .header("Accept-Encoding", "gzip, deflate")
            .apply {
                // custom identifier possible (e.g. SourceForge: Cloudflare only checks supposed browsers)
                if (extraHeaders.keys.none { it.equals("User-Agent", true) }) header("User-Agent", USER_AGENT)
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .GET().build()
        return try {
            val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
            val enc = resp.headers().firstValue("Content-Encoding").orElse("")
            val raw = resp.body()
            val stream: InputStream = when {
                enc.contains("gzip", true) -> GZIPInputStream(raw)
                enc.contains("deflate", true) -> InflaterInputStream(raw)
                else -> raw
            }
            val body = stream.use { readLimited(it, maxBytes) }
            FetchResult(resp.statusCode(), resp.uri().toString(), resp.headers().firstValue("Content-Type").orElse(null), body, resp.headers().map())
        } catch (e: Exception) {
            FetchResult(0, url, null, null)
        }
    }

    fun post(url: String, json: String, headers: Map<String, String>, timeout: Duration = this.timeout): FetchResult {
        val req = HttpRequest.newBuilder(URI(url)).timeout(timeout)
            .header("User-Agent", USER_AGENT).header("Content-Type", "application/json")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .POST(HttpRequest.BodyPublishers.ofString(json)).build()
        return try {
            val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
            FetchResult(resp.statusCode(), url, resp.headers().firstValue("Content-Type").orElse(null), resp.body(), resp.headers().map())
        } catch (e: Exception) {
            FetchResult(0, url, null, null)
        }
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, minOf(n, max - out.size()))
        }
        return out.toByteArray()
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
        const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        const val ACCEPT_IMAGE = "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5"
    }
}

/** Image format based on the first bytes. */
object ImageSniffer {
    fun mime(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "image/png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size >= 6 && String(b, 0, 3) == "GIF" -> "image/gif"
        b.size >= 12 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP" -> "image/webp"
        b.size >= 4 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte() && b[3] == 0.toByte() -> "image/x-icon"
        b.size >= 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "image/bmp"
        b.size >= 12 && String(b, 4, 4) == "ftyp" && String(b, 8, 4).startsWith("avi") -> "image/avif"
        String(b, 0, minOf(b.size, 256)).trimStart().let { it.startsWith("<svg") || it.startsWith("<?xml") } -> "image/svg+xml"
        else -> null
    }

    fun extension(mime: String): String = when (mime) {
        "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/gif" -> "gif"; "image/webp" -> "webp"
        "image/x-icon" -> "ico"; "image/bmp" -> "bmp"; "image/avif" -> "avif"; "image/svg+xml" -> "svg"
        else -> "bin"
    }
}
