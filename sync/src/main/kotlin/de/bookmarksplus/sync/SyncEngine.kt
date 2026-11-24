package de.bookmarksplus.sync

import de.bookmarksplus.i18n.tr
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** How the first sync proceeds: merge (default) or replace local data with that from the target. */
enum class FirstSyncMode { Ask, TakeRemote, Merge, ReplaceRemote }

/** No longer thrown (entries are always merged); kept for older callers. */
class FirstSyncDecisionNeeded(val remoteBookmarks: Int?) : Exception(tr("sync.firstSync.conflict"))

data class SyncResult(
    val pulled: Int, val pushed: Int, val snapshotWritten: Boolean, val mediaUp: Int, val mediaDown: Int, val firstSync: Boolean,
    /** Entries changed on two devices at the same time (the newer version won). */
    val conflicts: Int = 0,
)

/**
 * Keys for sync at the entry level, derived from the sync key (Argon2id from the passphrase):
 * separate keys for contents, IDs and media. The ID of an entry is an HMAC over kind and local key –
 * so the target can neither distinguish bookmarks from folders or tags nor guess tag names.
 */
class RecordKeys(master: ByteArray) {
    val enc: ByteArray = hmac(master, "bmp-record-enc".toByteArray())
    private val id: ByteArray = hmac(master, "bmp-record-id".toByteArray())
    private val media: ByteArray = hmac(master, "bmp-media-id".toByteArray())

    /** Opaque, stable ID in UUID format. */
    fun recordId(kind: String, key: String): String = uuid(hmac(id, "$kind\u0000$key".toByteArray()))

    fun mediaName(content: ByteArray): String = "media/" + hex(hmac(media, content)) + ".bin"

    /** Identifier of this key (if the passphrase changes, sync starts from scratch). */
    val fingerprint: String = hex(hmac(id, "fingerprint".toByteArray())).take(16)

    fun seal(rid: String, e: Entry): ByteArray = Crypto.seal(enc, JSON.encodeToString(Entry.serializer(), e).toByteArray(), "rec:$rid")

    /** Decrypts and checks that the content matches the ID (the target cannot swap entries). */
    fun open(rid: String, blob: ByteArray): Entry {
        val e = JSON.decodeFromString(Entry.serializer(), String(Crypto.open(enc, blob, "rec:$rid")))
        if (recordId(e.e, e.k) != rid) throw WrongPassphraseException()
        return e
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        fun uuid(h: ByteArray): String {
            val b = h.copyOf(16)
            b[6] = ((b[6].toInt() and 0x0f) or 0x80).toByte() // version 8 (freely defined content)
            b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
            val x = hex(b)
            return "${x.substring(0, 8)}-${x.substring(8, 12)}-${x.substring(12, 16)}-${x.substring(16, 20)}-${x.substring(20)}"
        }
    }
}

/**
 * Synchronizes the local database at the entry level via a [RecordStore].
 *
 * 1. **Fetch:** changes since the last run (server: sequence number; files: file list). Only entries
 *    whose revision has changed are loaded. If the same entry was also changed locally, the newer
 *    version wins (change time in the encrypted content).
 * 2. **Send:** locally changed entries with the last known revision. If it has changed at the target in the meantime,
 *    the target reports a conflict → fetch version from the target, newer wins, send again if necessary.
 *
 * Local bookkeeping in `sync_rec` (ID → kind, key, revision); placements and tags belong to the bookmark.
 */
class SyncEngine(
    private val dbFile: File,
    private val mediaDir: File,
    private val store: RecordStore,
    key: ByteArray,
    private val syncMedia: Boolean = true,
    private val log: (String) -> Unit = {},
    private val control: SyncControl = SyncControl(),
    private val onProgress: (SyncProgress) -> Unit = {},
) {
    /** Convenience: file targets (FTP, folder) as record files. */
    constructor(
        dbFile: File, mediaDir: File, store: BlobStore, key: ByteArray, syncMedia: Boolean = true, log: (String) -> Unit = {},
        control: SyncControl = SyncControl(), onProgress: (SyncProgress) -> Unit = {},
    ) : this(dbFile, mediaDir, FileRecordStore(store), key, syncMedia, log, control, onProgress)

    private val keys = RecordKeys(key)
    private lateinit var local: LocalRecords
    private var remoteMedia: MutableSet<String>? = null
    private var mediaUp = 0
    private var mediaDown = 0
    private var conflicts = 0
    private var done = 0
    private var total = 0

    private fun report(phase: SyncPhase) = onProgress(SyncProgress(phase, done, total))

    private fun step(phase: SyncPhase, n: Int = 1) { done += n; report(phase) }

    /**
     * Images are not transferred while entries are read or applied: they are queued (tables `sync_media_up` /
     * `sync_media_down`) and sent after all bookmarks, so the bookmarks arrive first. The queue survives a stop.
     */
    private val media = object : MediaTransfer {
        override fun upload(path: String): String? {
            val f = File(path).takeIf { it.isFile } ?: return null
            val name = keys.mediaName(f.readBytes())
            local.update("INSERT OR REPLACE INTO sync_media_up(name, path) VALUES (?, ?)", name, f.absolutePath)
            return name.removePrefix("media/").removeSuffix(".bin")
        }

        override fun download(hash: String): String? {
            if (!hash.matches(Regex("[0-9a-f]{64}"))) return null
            val target = File(mediaDir, "sync/$hash.img")
            if (!target.isFile) local.update("INSERT OR IGNORE INTO sync_media_down(hash) VALUES (?)", hash)
            return target.absolutePath
        }
    }

    private fun remoteMedia(): MutableSet<String> = remoteMedia ?: store.blobs.list("media/").toMutableSet().also { remoteMedia = it }

    fun sync(mode: FirstSyncMode = FirstSyncMode.Ask): SyncResult = LocalRecords.open(dbFile) { conn ->
        local = LocalRecords(conn, if (syncMedia) media else null)
        ensureTables()
        // new key (different passphrase/different target) → reset bookkeeping
        if (setting(KEY) != keys.fingerprint) {
            local.exec("DELETE FROM sync_rec")
            local.update("DELETE FROM setting WHERE key IN (?, ?, ?)", CURSOR, LAST_SYNC, KEY)
            putSetting(KEY, keys.fingerprint)
        }
        val first = setting(LAST_SYNC) == null
        if (first) {
            when (mode) {
                FirstSyncMode.TakeRemote -> { local.wipe(); local.exec("DELETE FROM sync_rec") }
                else -> local.markAllPending()
            }
            log(tr("sync.firstSync.mode", mode))
        }

        // the number of local changes is known at once; the remote number follows with the change list
        total = local.pending().count { it.e in LocalRecords.KINDS }
        report(SyncPhase.Prepare)
        val pulled = pull(force = mode == FirstSyncMode.ReplaceRemote && first)
        val pushed = push()
        if (first) cleanupLegacy()
        putSetting(LAST_SYNC, System.currentTimeMillis().toString())
        if (syncMedia) { uploadMedia(); downloadMedia() }
        SyncResult(pulled, pushed, false, mediaUp, mediaDown, first, conflicts)
    }

    // ------------------------------------------------------------------------------------------------ Fetch

    /** [force] = local version always wins (first sync with "Upload this data"). */
    private fun pull(force: Boolean): Int {
        var cursor = setting(CURSOR)
        val metas = LinkedHashMap<String, RemoteRecord>()
        var full = false
        while (true) {
            val page = store.changes(cursor)
            if (page.full) full = true
            page.records.forEach { metas[it.id] = it }
            cursor = page.cursor
            if (!page.more) break
        }
        val known = HashMap<String, Triple<String, String, String?>>() // id → (kind, key, revision)
        local.query("SELECT rid, kind, k, rev FROM sync_rec") { known[it.getString(1)] = Triple(it.getString(2), it.getString(3), it.getString(4)) }

        // full list: entries known locally but vanished at the target count as deleted
        val vanished = if (full && metas.isNotEmpty()) known.filter { (id, v) -> v.third != null && id !in metas }.keys else emptySet()

        val toFetch = metas.values.filter { !it.deleted && known[it.id]?.third != it.rev }.map { it.id }
        val toDelete = metas.values.filter { it.deleted && known[it.id] != null && known[it.id]!!.third != it.rev } +
            vanished.map { RemoteRecord(it, "", 0, true) }

        total += toFetch.size
        report(SyncPhase.Fetch)
        val incoming = ArrayList<Entry>()
        val revs = HashMap<String, String>()
        toFetch.chunked(25).forEach { chunk ->
            control.checkpoint()
            store.fetch(chunk).forEach { (id, revData) ->
                val e = runCatching { keys.open(id, revData.second) }.getOrElse {
                    log(tr("sync.record.undecryptable", id)); return@forEach
                }
                incoming += e
                revs[id] = revData.first
            }
            step(SyncPhase.Fetch, chunk.size)
        }
        control.checkpoint()
        for (r in toDelete) {
            val (kind, k, _) = known[r.id] ?: continue
            incoming += Entry(kind, k, r.t, d = true)
            revs[r.id] = r.rev
        }

        // conflict with local change: newer version wins
        val apply = ArrayList<Entry>()
        for (e in incoming) {
            val id = keys.recordId(e.e, e.k)
            val localT = local.pendingT(e.e, e.k)
            if (localT != null && (force || localT > e.t)) {
                if (localT > 1) conflicts++
                remember(id, e.e, e.k, revs[id]) // local version is sent right away on this revision
                continue
            }
            if (localT != null && localT > 1) conflicts++
            apply += e
        }
        local.apply(apply)
        local.transaction {
            apply.forEach { e ->
                val id = keys.recordId(e.e, e.k)
                if (e.d) local.update("DELETE FROM sync_rec WHERE rid = ?", id) else remember(id, e.e, e.k, revs[id])
            }
        }
        cursor?.let { putSetting(CURSOR, it) }
        if (apply.isNotEmpty()) log(tr("sync.received", apply.size))
        return apply.size
    }

    // ------------------------------------------------------------------------------------------------ Send

    private fun push(): Int {
        var pushed = 0
        val all = local.pending().filter { it.e in LocalRecords.KINDS }.distinctBy { it.e + "\u0000" + it.k }
        report(SyncPhase.Send)
        for (batch in all.chunked(25)) {
            control.checkpoint()
            pushed += pushBatch(batch)
            step(SyncPhase.Send, batch.size)
        }
        if (pushed > 0) log(tr("sync.sent", pushed))
        return pushed
    }

    private fun pushBatch(batch: List<Entry>): Int {
        var pushed = 0
        var todo = batch
        for (round in 0 until 3) {
            if (todo.isEmpty()) break
            val entries = todo.map { p -> local.entry(p.e, p.k, p.t, p.d) }
            val items = entries.map { e ->
                val id = keys.recordId(e.e, e.k)
                PushItem(id, rev(id), if (e.d) null else keys.seal(id, e), Transfer.fileTime(e))
            }
            val byId = entries.associateBy { keys.recordId(it.e, it.k) }
            val results = store.push(items)
            val retry = ArrayList<Entry>()
            val conflicted = ArrayList<PushResult.Conflict>()
            local.transaction {
                for (r in results) {
                    val e = byId[r.id] ?: continue
                    when (r) {
                        is PushResult.Ok -> {
                            if (e.d) local.update("DELETE FROM sync_rec WHERE rid = ?", r.id) else remember(r.id, e.e, e.k, r.rev)
                            local.markDone(e.e, e.k, e.t)
                            pushed++
                        }
                        is PushResult.Conflict -> conflicted += r
                    }
                }
            }
            // conflicts: fetch version from the target, newer wins
            val remote = store.fetch(conflicted.filter { !it.deleted && it.rev != null }.map { it.id })
            val take = ArrayList<Entry>()
            for (c in conflicted) {
                val mine = byId[c.id]!!
                conflicts++
                val theirs = remote[c.id]?.let { runCatching { keys.open(c.id, it.second) }.getOrNull() }
                if (theirs != null && theirs.t > mine.t) {
                    take += theirs
                    remember(c.id, mine.e, mine.k, remote[c.id]!!.first)
                    local.markDone(mine.e, mine.k, mine.t)
                } else {
                    // own version is newer (or deleted at the target) → send again on the current revision
                    remember(c.id, mine.e, mine.k, c.rev)
                    retry += mine
                }
            }
            if (take.isNotEmpty()) local.apply(take)
            todo = retry
        }
        return pushed
    }

    // ------------------------------------------------------------------------------------------------ Images

    /** Sends queued images (after all bookmarks). Rows stay queued until sent, so a stop loses nothing. */
    private fun uploadMedia() {
        val queue = ArrayList<Pair<String, String>>()
        local.query("SELECT name, path FROM sync_media_up") { queue += it.getString(1) to it.getString(2) }
        if (queue.isEmpty()) return
        total += queue.size
        report(SyncPhase.ImagesUp)
        val remote = remoteMedia()
        for ((name, path) in queue) {
            control.checkpoint()
            val f = File(path)
            if (name !in remote && f.isFile) {
                val ok = runCatching {
                    store.blobs.put(name, Crypto.seal(keys.enc, f.readBytes(), name, compress = false))
                    remote += name
                    mediaUp++
                }.isSuccess
                if (ok) local.update("DELETE FROM sync_media_up WHERE name = ?", name) else log(tr("sync.media.failed", name))
            } else local.update("DELETE FROM sync_media_up WHERE name = ?", name)
            step(SyncPhase.ImagesUp)
        }
    }

    /** Fetches queued images. Images the target does not have yet stay queued for the next run. */
    private fun downloadMedia() {
        val queue = ArrayList<String>()
        local.query("SELECT hash FROM sync_media_down") { queue += it.getString(1) }
        if (queue.isEmpty()) return
        total += queue.size
        report(SyncPhase.ImagesDown)
        val remote = remoteMedia()
        for (hash in queue) {
            control.checkpoint()
            val name = "media/$hash.bin"
            val target = File(mediaDir, "sync/$hash.img")
            if (target.isFile) local.update("DELETE FROM sync_media_down WHERE hash = ?", hash)
            else if (name in remote) {
                val ok = runCatching {
                    val bytes = Crypto.open(keys.enc, store.blobs.get(name) ?: error("missing"), name)
                    target.parentFile.mkdirs()
                    target.writeBytes(bytes)
                }.isSuccess
                if (ok) { mediaDown++; local.update("DELETE FROM sync_media_down WHERE hash = ?", hash) } else log(tr("sync.media.failed", name))
            }
            step(SyncPhase.ImagesDown)
        }
    }

    // ------------------------------------------------------------------------------------------------ Helpers

    private fun ensureTables() {
        local.exec("CREATE TABLE IF NOT EXISTS sync_rec (rid TEXT PRIMARY KEY, kind TEXT NOT NULL, k TEXT NOT NULL, rev TEXT)")
        local.exec("CREATE TABLE IF NOT EXISTS sync_media_up (name TEXT PRIMARY KEY, path TEXT NOT NULL)")
        local.exec("CREATE TABLE IF NOT EXISTS sync_media_down (hash TEXT PRIMARY KEY)")
    }

    private fun rev(id: String): String? = local.text("SELECT rev FROM sync_rec WHERE rid = ?", id)

    private fun remember(id: String, kind: String, k: String, rev: String?) =
        local.update("INSERT OR REPLACE INTO sync_rec(rid, kind, k, rev) VALUES (?, ?, ?, ?)", id, kind, k, rev)

    private fun setting(key: String): String? = local.text("SELECT value FROM setting WHERE key = ?", key)
    private fun putSetting(key: String, value: String) = local.update("INSERT OR REPLACE INTO setting(key, value) VALUES (?, ?)", key, value)

    /** Remove files of the former procedure (change files, snapshots). */
    private fun cleanupLegacy() = runCatching {
        (store.blobs.list("changes/") + store.blobs.list("snapshots/")).forEach { store.blobs.delete(it) }
    }

    companion object {
        private const val CURSOR = "sync2.cursor"
        private const val LAST_SYNC = "sync2.lastSync"
        private const val KEY = "sync2.key"

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
