package de.bookmarksplus.sync

import de.bookmarksplus.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

data class TransferResult(val entries: Int, val bookmarks: Int, val skipped: Int = 0, val removed: Int = 0)

/**
 * Encrypted export and import of the whole database (without thumbnails), with its own passphrase:
 *
 * - **Blob** (`.bmpx`): one file – header `BMPX1`, `keyinfo` (salt, Argon2 parameters), then all entries
 *   encrypted with AES-256-GCM.
 * - **File list**: folder with `keyinfo.json` and `items/<change time>_<ID>.bin` per entry (format of
 *   [FileRecordStore]). Re-exporting into the same folder only updates what has changed and marks
 *   deletions – good for cloud folders, hosters and backups.
 *
 * Import merges: whatever is missing locally or differs and is not older than the local version is adopted.
 * Imported changes are then synchronized like your own.
 */
object Transfer {
    private val MAGIC = "BMPX1\n".toByteArray()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable
    private class Bundle(val created: Long, val entries: List<Entry>)

    // ------------------------------------------------------------------------------------------------ Export

    fun exportBlob(dbFile: File, target: File, passphrase: CharArray): TransferResult {
        val entries = readAll(dbFile)
        val (info, key) = Crypto.newKeyInfo(passphrase)
        val keys = RecordKeys(key)
        val sealed = Crypto.seal(keys.enc, json.encodeToString(Bundle.serializer(), Bundle(System.currentTimeMillis(), entries)).toByteArray(), "export")
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(MAGIC)
            out.write(Crypto.encodeKeyInfo(info))
            out.write('\n'.code)
            out.write(sealed)
        }
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        return TransferResult(entries.size, entries.count { it.e == "bookmark" })
    }

    /** Export as a file list. If an export already exists in the folder, the passphrase must match (it is updated). */
    fun exportFolder(dbFile: File, dir: File, passphrase: CharArray): TransferResult {
        val blobs = FolderStore(dir.apply { mkdirs() })
        val existing = blobs.get("keyinfo.json")
        val key = if (existing != null) Crypto.unlock(passphrase, Crypto.decodeKeyInfo(existing))
        else {
            require(dir.listFiles().orEmpty().none { it.name != "keyinfo.json" }) { tr("sync.transfer.notEmpty") }
            val (info, k) = Crypto.newKeyInfo(passphrase)
            blobs.put("keyinfo.json", Crypto.encodeKeyInfo(info))
            k
        }
        val keys = RecordKeys(key)
        val store = FileRecordStore(blobs)
        val remote = store.changes(null).records.associateBy { it.id }
        val entries = readAll(dbFile)
        val ids = HashSet<String>()
        val items = ArrayList<PushItem>()
        var skipped = 0
        for (e in entries) {
            val id = keys.recordId(e.e, e.k)
            ids += id
            val t = fileTime(e)
            val cur = remote[id]
            // unchanged (same timestamp) → do not rewrite
            if (cur != null && !cur.deleted && cur.rev == FileRecordStore.fileName(t, id)) { skipped++; continue }
            items += PushItem(id, cur?.rev, keys.seal(id, e), t)
        }
        // no longer present locally → mark as deleted
        val now = System.currentTimeMillis()
        val removed = remote.values.filter { !it.deleted && it.id !in ids }
        removed.forEach { items += PushItem(it.id, it.rev, null, now) }
        store.push(items)
        return TransferResult(entries.size, entries.count { it.e == "bookmark" }, skipped, removed.size)
    }

    // ------------------------------------------------------------------------------------------------ Import

    /** Detects blob file or export folder (or its `keyinfo.json`). */
    fun import(dbFile: File, source: File, passphrase: CharArray): TransferResult {
        val entries = when {
            source.isDirectory -> readFolder(source, passphrase)
            source.name == "keyinfo.json" -> readFolder(source.parentFile, passphrase)
            else -> readBlob(source, passphrase)
        }
        return LocalRecords.open(dbFile) { conn ->
            val local = LocalRecords(conn, null)
            val take = entries.filter { e ->
                if (e.d) return@filter local.read(e.e, e.k) != null && (local.localT(e.e, e.k) ?: 0) <= e.t
                val cur = local.read(e.e, e.k) ?: return@filter true
                !sameContent(cur, e.v) && (local.localT(e.e, e.k) ?: 0) <= e.t
            }
            local.apply(take, record = true)
            TransferResult(take.size, take.count { it.e == "bookmark" && !it.d }, entries.size - take.size)
        }
    }

    fun readBlob(file: File, passphrase: CharArray): List<Entry> {
        val bytes = file.readBytes()
        require(bytes.size > MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { tr("sync.transfer.notExport") }
        val nl = (MAGIC.size until bytes.size).first { bytes[it] == '\n'.code.toByte() }
        val info = Crypto.decodeKeyInfo(bytes.copyOfRange(MAGIC.size, nl))
        val keys = RecordKeys(Crypto.unlock(passphrase, info))
        val plain = Crypto.open(keys.enc, bytes.copyOfRange(nl + 1, bytes.size), "export")
        return json.decodeFromString(Bundle.serializer(), String(plain)).entries
    }

    fun readFolder(dir: File, passphrase: CharArray): List<Entry> {
        val blobs = FolderStore(dir)
        val info = blobs.get("keyinfo.json") ?: throw IllegalArgumentException(tr("sync.transfer.noKeyinfo"))
        val keys = RecordKeys(Crypto.unlock(passphrase, Crypto.decodeKeyInfo(info)))
        val store = FileRecordStore(blobs)
        val metas = store.changes(null).records
        val data = store.fetch(metas.filter { !it.deleted }.map { it.id })
        return data.map { (id, rd) -> keys.open(id, rd.second) }
    }

    // ------------------------------------------------------------------------------------------------ Helpers

    private fun readAll(dbFile: File): List<Entry> = LocalRecords.open(dbFile) { conn ->
        val local = LocalRecords(conn, null)
        local.allKeys().map { (kind, k) -> local.entry(kind, k, local.localT(kind, k) ?: 0, false) }.filter { !it.d }
    }

    /** Timestamp for the file name: change time, otherwise change/creation date of the bookmark. */
    fun fileTime(e: Entry): Long = e.t.takeIf { it > 1 }
        ?: listOf("last_modified", "add_date").firstNotNullOfOrNull { (e.v?.get(it) as? JsonPrimitive)?.longOrNull?.takeIf { s -> s > 0 } }?.times(1000)
        ?: 0

    /** Content-equal (change times of the bookmark itself count as well). */
    private fun sameContent(a: kotlinx.serialization.json.JsonObject, b: kotlinx.serialization.json.JsonObject?): Boolean = b != null && a == b
}
