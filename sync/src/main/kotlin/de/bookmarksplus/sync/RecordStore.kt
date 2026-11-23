package de.bookmarksplus.sync

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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Closeable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/** State of an entry at the target: opaque ID, revision, change time (ms) and deletion marker. */
data class RemoteRecord(val id: String, val rev: String, val t: Long, val deleted: Boolean)

/**
 * A page of changed entries. [full] = the list is complete (not only changes since [cursor]);
 * then entries that are known locally and missing here count as deleted.
 */
class RecordPage(val records: List<RemoteRecord>, val cursor: String?, val more: Boolean, val full: Boolean)

/** Upload: [base] = revision the change is based on (null = new); [data] = null deletes. */
class PushItem(val id: String, val base: String?, val data: ByteArray?, val t: Long)

sealed interface PushResult {
    val id: String
    data class Ok(override val id: String, val rev: String) : PushResult
    data class Conflict(override val id: String, val rev: String?, val deleted: Boolean) : PushResult
}

/**
 * Target for sync at the entry level. Each bookmark (incl. folder placements and tags), each folder and tag
 * is a separate encrypted entry. [blobs] holds `keyinfo.json`, thumbnails and backups.
 */
interface RecordStore : Closeable {
    val blobs: BlobStore
    fun changes(cursor: String?): RecordPage
    fun fetch(ids: List<String>): Map<String, Pair<String, ByteArray>>
    fun push(items: List<PushItem>): List<PushResult>
    override fun close() = blobs.close()
}

/** Own server, API v2: changes via sequence number, conflict detection at the server (compare-and-swap). */
class ServerRecordStore(baseUrl: String, user: String, password: String) : RecordStore {
    private val http = ServerHttp(baseUrl, user, password)
    override val blobs: BlobStore = ServerStore(baseUrl, user, password)

    override fun changes(cursor: String?): RecordPage {
        val since = cursor?.toLongOrNull() ?: 0
        val o = Json.parseToJsonElement(http.json("GET", "/api/v2/records?since=$since&limit=2000")).jsonObject
        val list = o["records"]!!.jsonArray.map { e ->
            val r = e.jsonObject
            RemoteRecord(r.str("id")!!, r.str("rev")!!, r["updated"]!!.jsonPrimitive.longOrNull ?: 0, r["deleted"]?.jsonPrimitive?.booleanOrNull == true)
        }
        val reset = o["reset"]?.jsonPrimitive?.booleanOrNull == true
        return RecordPage(list, o["next"]!!.jsonPrimitive.content, o["more"]?.jsonPrimitive?.booleanOrNull == true, full = since == 0L || reset)
    }

    override fun fetch(ids: List<String>): Map<String, Pair<String, ByteArray>> {
        val out = HashMap<String, Pair<String, ByteArray>>()
        ids.chunked(500).forEach { chunk ->
            val body = buildJsonObject { put("ids", JsonArray(chunk.map(::JsonPrimitive))) }.toString()
            Json.parseToJsonElement(http.json("POST", "/api/v2/records/get", body)).jsonObject["records"]!!.jsonArray.forEach { e ->
                val r = e.jsonObject
                out[r.str("id")!!] = r.str("rev")!! to Base64.getDecoder().decode(r.str("data")!!)
            }
        }
        return out
    }

    override fun push(items: List<PushItem>): List<PushResult> {
        val out = ArrayList<PushResult>()
        // packages of up to ~8 MB or 500 entries
        var batch = ArrayList<PushItem>()
        var size = 0L
        fun flush() {
            if (batch.isEmpty()) return
            val body = buildJsonObject {
                put("items", JsonArray(batch.map { i ->
                    buildJsonObject {
                        put("id", i.id)
                        put("base", i.base?.let(::JsonPrimitive) ?: JsonNull)
                        put("data", i.data?.let { JsonPrimitive(Base64.getEncoder().encodeToString(it)) } ?: JsonNull)
                    }
                }))
            }.toString()
            Json.parseToJsonElement(http.json("POST", "/api/v2/records/put", body)).jsonObject["results"]!!.jsonArray.forEach { e ->
                val r = e.jsonObject
                out += if (r["ok"]?.jsonPrimitive?.booleanOrNull == true) PushResult.Ok(r.str("id")!!, r.str("rev")!!)
                else PushResult.Conflict(r.str("id")!!, r.str("rev"), r["deleted"]?.jsonPrimitive?.booleanOrNull == true)
            }
            batch = ArrayList(); size = 0
        }
        for (i in items) {
            batch += i
            size += (i.data?.size ?: 0) * 4L / 3 + 200
            if (batch.size >= 500 || size > 8L shl 20) flush()
        }
        flush()
        return out
    }

    /** Number of entries on the server (for the connection test). */
    fun count(): Long = Json.parseToJsonElement(http.json("GET", "/api/v2/info")).jsonObject["count"]?.jsonPrimitive?.longOrNull ?: 0

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
}

/**
 * Entries as individual files in a [BlobStore] (FTP, folder, export):
 * `items/<time of change>_<ID>.bin`, deleted ones as empty `…_<ID>.del`. Name = revision.
 * This way the state can be read from the file list alone – suitable for cloud folders, hosters and backups.
 * Without a server there is no real compare-and-swap; before writing, the list is re-read, and if after
 * a race two versions exist after all, the newer one wins.
 */
class FileRecordStore(override val blobs: BlobStore) : RecordStore {
    private var current: Map<String, Pair<String, List<String>>>? = null // id → (current name, older names)

    private fun scan(): Map<String, Pair<String, List<String>>> {
        val byId = HashMap<String, MutableList<String>>()
        for (name in blobs.list(PREFIX)) {
            val m = NAME.matchEntire(name.removePrefix(PREFIX)) ?: continue
            byId.getOrPut(m.groupValues[2]) { ArrayList() } += name
        }
        return byId.mapValues { (_, names) ->
            val sorted = names.sortedWith(compareBy<String> { it.removePrefix(PREFIX).substringBefore('_') }.thenBy { it.endsWith(".bin") })
            sorted.last() to sorted.dropLast(1)
        }.also { current = it }
    }

    override fun changes(cursor: String?): RecordPage {
        val all = scan()
        val list = all.map { (id, v) ->
            val n = v.first.removePrefix(PREFIX)
            RemoteRecord(id, n, parseTime(n.substringBefore('_')), n.endsWith(".del"))
        }
        // clean up older versions after races (a few at most per run)
        all.values.flatMap { it.second }.take(50).forEach { runCatching { blobs.delete(it) } }
        return RecordPage(list, null, more = false, full = true)
    }

    override fun fetch(ids: List<String>): Map<String, Pair<String, ByteArray>> {
        val names = current ?: scan()
        return ids.mapNotNull { id ->
            val name = names[id]?.first?.takeIf { it.endsWith(".bin") } ?: return@mapNotNull null
            blobs.get(name)?.let { id to (name.removePrefix(PREFIX) to it) }
        }.toMap()
    }

    override fun push(items: List<PushItem>): List<PushResult> {
        val names = scan()
        return items.map { item ->
            val existing = names[item.id]
            val currentRev = existing?.first?.removePrefix(PREFIX)
            // tombstone counts as "not present" for a new entry
            val effective = currentRev?.takeUnless { it.endsWith(".del") && item.base == null }
            if (effective != item.base) return@map PushResult.Conflict(item.id, currentRev, currentRev?.endsWith(".del") == true)
            if (item.data == null && existing == null) return@map PushResult.Ok(item.id, "")
            val rev = fileName(item.t, item.id, item.data == null)
            blobs.put(PREFIX + rev, item.data ?: ByteArray(0))
            existing?.let { (cur, older) -> (older + cur).filter { it != PREFIX + rev }.forEach { runCatching { blobs.delete(it) } } }
            PushResult.Ok(item.id, rev)
        }.also { current = null }
    }

    companion object {
        const val PREFIX = "items/"
        private val NAME = Regex("""(\d{8}T\d{9}Z)_([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.(bin|del)""")
        private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)

        fun fileName(t: Long, id: String, deleted: Boolean = false) =
            FORMAT.format(Instant.ofEpochMilli(t.coerceAtLeast(0))) + "_" + id + if (deleted) ".del" else ".bin"

        fun parseTime(s: String): Long = runCatching { Instant.from(FORMAT.parse(s)).toEpochMilli() }.getOrDefault(0)
    }
}
