package de.bookmarksplus.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Base64

/**
 * Content of an entry (transferred encrypted): kind, local key, change time (ms), deletion marker, values.
 * Kinds: `bookmark` (incl. `_placements` and `_tags`), `folder`, `tag`, `tag_i18n`, `icon`, `setting`.
 */
@Serializable
data class Entry(val e: String, val k: String, val t: Long, val d: Boolean = false, val v: JsonObject? = null)

/** Images (thumbnails, screenshots) travel separately from the entries; null = not transferred. */
interface MediaTransfer {
    /** Uploads the file (if necessary) and returns its key. */
    fun upload(path: String): String?
    /** Fetches the image for the key and returns the local path. */
    fun download(hash: String): String?
}

/**
 * Reading and applying entries directly via JDBC on the app database (sync, export, import).
 * While applying, `sync.applying` is set so that the sync triggers do not log anything.
 */
class LocalRecords(private val c: Connection, private val media: MediaTransfer?) {

    // ------------------------------------------------------------------------------------------------ Reading

    /** Current content of an entry; null = does not (or no longer) exist. */
    fun read(kind: String, key: String): JsonObject? = when (kind) {
        "setting" -> one("SELECT value FROM setting WHERE key = ?", key)
        "icon" -> one("SELECT mime, data FROM icon WHERE hash = ?", key)
        "tag_i18n" -> one("SELECT de, en FROM tag_i18n WHERE lower(name) = ?", key)
        "tag" -> one("SELECT name, color FROM tag WHERE lower(name) = ?", key)
        "folder" -> if (key == ROOT) null else one(
            """SELECT CASE WHEN p.parent_id IS NULL THEN '$ROOT' ELSE p.uuid END AS parent, f.title, f.position, f.add_date,
                      f.last_modified, f.extra, f.deleted_at
               FROM folder f JOIN folder p ON p.id = f.parent_id WHERE f.uuid = ?""", key,
        )
        "bookmark" -> readBookmark(key)
        else -> null
    }

    private fun readBookmark(uuid: String): JsonObject? {
        val row = one("SELECT * FROM bookmark WHERE uuid = ?", uuid) ?: return null
        val id = (row["id"] as JsonPrimitive).long
        val m = row.toMutableMap()
        m.remove("id")
        MEDIA_COLUMNS.forEach { (col, key) ->
            val path = (m.remove(col) as? JsonPrimitive)?.contentOrNull
            path?.let { media?.upload(it) }?.let { m[key] = JsonPrimitive(it) }
        }
        val shots = (m.remove("shots_json") as? JsonPrimitive)?.contentOrNull
        if (media != null && shots != null) exportShots(shots)?.let { m["shots"] = it }
        val placements = ArrayList<JsonElement>()
        query(
            """SELECT p.uuid, CASE WHEN f.parent_id IS NULL THEN '$ROOT' ELSE f.uuid END AS folder, p.position, p.is_primary, p.title, p.add_date, p.extra
               FROM placement p JOIN folder f ON f.id = p.folder_id WHERE p.bookmark_id = ? AND p.uuid IS NOT NULL ORDER BY p.id""", id,
        ) { rs -> placements += rowJson(rs) }
        val tags = ArrayList<JsonElement>()
        query("SELECT t.name FROM bookmark_tag bt JOIN tag t ON t.id = bt.tag_id WHERE bt.bookmark_id = ? ORDER BY lower(t.name)", id) { tags += JsonPrimitive(it.getString(1)) }
        m["_placements"] = JsonArray(placements)
        m["_tags"] = JsonArray(tags)
        return JsonObject(m)
    }

    /** All locally present entries (kind, key). */
    fun allKeys(): List<Pair<String, String>> {
        val keys = ArrayList<Pair<String, String>>()
        query("SELECT key FROM setting WHERE key IN ($SYNCED_SETTINGS)") { keys += "setting" to it.getString(1) }
        query("SELECT hash FROM icon") { keys += "icon" to it.getString(1) }
        query("SELECT lower(name) FROM tag_i18n") { keys += "tag_i18n" to it.getString(1) }
        query("SELECT lower(name) FROM tag") { keys += "tag" to it.getString(1) }
        query("SELECT uuid FROM folder WHERE parent_id IS NOT NULL") { keys += "folder" to it.getString(1) }
        query("SELECT uuid FROM bookmark") { keys += "bookmark" to it.getString(1) }
        return keys
    }

    fun entry(kind: String, key: String, t: Long, deleted: Boolean): Entry {
        if (deleted) return Entry(kind, key, t, d = true)
        val v = read(kind, key) ?: return Entry(kind, key, t, d = true)
        return Entry(kind, key, t, v = v)
    }

    // ------------------------------------------------------------------------------------------------ Applying

    /**
     * Applies entries (the passed ones win). Folders can point to parents not yet created and bookmarks
     * to folders not yet created → several passes; whatever is still missing then ends up in the root.
     * [record] = adopt changes into the log (import: yes, so that they continue to be synchronized).
     */
    fun apply(entries: Collection<Entry>, record: Boolean = false): Int {
        if (entries.isEmpty()) return 0
        var n = 0
        transaction {
            exec("INSERT OR REPLACE INTO setting(key, value) VALUES ('sync.applying', '1')")
            try {
                val upserts = entries.filter { !it.d }.sortedBy { ORDER.indexOf(it.e) }
                val deletes = entries.filter { it.d }.sortedByDescending { ORDER.indexOf(it.e) }
                var pending = upserts
                for (pass in 0 until 20) {
                    if (pending.isEmpty()) break
                    val retry = ArrayList<Entry>()
                    for (e in pending) if (upsert(e, force = false)) n++ else retry += e
                    if (retry.size == pending.size) {
                        retry.forEach { if (upsert(it, force = true)) n++ }
                        break
                    }
                    pending = retry
                }
                for (e in deletes) { delete(e); n++ }
                entries.forEach { e ->
                    update(
                        "INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES (?, ?, ?, ?, ?)",
                        e.e, e.k, if (record) System.currentTimeMillis() else e.t, if (e.d) 1 else 0, if (record) 1 else 0,
                    )
                }
            } finally {
                exec("DELETE FROM setting WHERE key = 'sync.applying'")
            }
        }
        return n
    }

    /** false = dependency still missing (try again later); [force] puts missing things into the root. */
    private fun upsert(e: Entry, force: Boolean): Boolean {
        val v = e.v ?: return true
        return when (e.e) {
            "setting" -> { if (e.k in SYNCED_SETTING_KEYS) update("INSERT OR REPLACE INTO setting(key, value) VALUES (?, ?)", e.k, v.str("value")); true }
            "icon" -> { update("INSERT OR IGNORE INTO icon(hash, mime, data) VALUES (?, ?, ?)", e.k, v.str("mime"), v.bytes("data")); true }
            "tag_i18n" -> { update("INSERT OR REPLACE INTO tag_i18n(name, de, en) VALUES (?, ?, ?)", e.k, v.str("de"), v.str("en")); true }
            "tag" -> {
                val name = v.str("name") ?: e.k
                val existing = scalarOrNull("SELECT id FROM tag WHERE lower(name) = ?", name.lowercase())
                if (existing != null) update("UPDATE tag SET name = ?, color = ? WHERE id = ?", name, v.long("color") ?: 0xFF888888, existing)
                else update("INSERT INTO tag(name, color) VALUES (?, ?)", name, v.long("color") ?: 0xFF888888)
                true
            }
            "folder" -> {
                if (e.k == ROOT) return true
                val parent = folderId(v.str("parent")) ?: if (force) folderId(ROOT)!! else return false
                update(
                    """INSERT INTO folder(uuid, parent_id, title, position, add_date, last_modified, extra, deleted_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                       ON CONFLICT(uuid) DO UPDATE SET parent_id = excluded.parent_id, title = excluded.title, position = excluded.position,
                         add_date = excluded.add_date, last_modified = excluded.last_modified, extra = excluded.extra, deleted_at = excluded.deleted_at""",
                    e.k, parent, v.str("title") ?: "", v.long("position") ?: 0, v.long("add_date"), v.long("last_modified"), v.str("extra"), v.long("deleted_at"),
                )
                true
            }
            "bookmark" -> upsertBookmark(e.k, v, force)
            else -> true // skip unknown kind (newer version)
        }
    }

    private fun upsertBookmark(uuid: String, v: JsonObject, force: Boolean): Boolean {
        val placements = (v["_placements"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // folder present? Otherwise later (or into the root with force)
        val folders = placements.map { p -> folderId(p.str("folder")) }
        if (!force && folders.any { it == null }) return false

        val localCols = columns("bookmark") - setOf("id", "shots_json", "screenshot_1", "screenshot_2", "screenshot_3") - ICON_COLUMNS - MEDIA_COLUMNS.keys
        val values = LinkedHashMap<String, Any?>()
        values["uuid"] = uuid
        for (col in localCols) if (col != "uuid" && v.containsKey(col)) values[col] = v.value(col)
        values["url"] = values["url"] ?: ""
        values["url_norm"] = values["url_norm"] ?: values["url"]
        values["title"] = values["title"] ?: ""
        // icons arrive as separate entries; only adopt hashes whose image is already there
        ICON_COLUMNS.forEach { col -> values[col] = v.str(col)?.takeIf { scalar("SELECT count(*) FROM icon WHERE hash = ?", it) > 0 } }
        if (media != null) {
            MEDIA_COLUMNS.forEach { (col, key) -> values[col] = v.str(key)?.let(media::download) }
            values["shots_json"] = importShots(v["shots"])
        }
        val cols = values.keys.toList()
        update(
            "INSERT INTO bookmark(${cols.joinToString()}) VALUES (${cols.joinToString { "?" }}) " +
                "ON CONFLICT(uuid) DO UPDATE SET " + cols.filter { it != "uuid" }.joinToString { "$it = excluded.$it" },
            *values.values.toTypedArray(),
        )
        val bid = idByUuid("bookmark", uuid)!!

        // placements: exactly those from the entry
        val keep = ArrayList<String>()
        placements.forEachIndexed { i, p ->
            val pu = p.str("uuid") ?: return@forEachIndexed
            val fid = folders[i] ?: folderId(ROOT)!!
            keep += pu
            update(
                """INSERT INTO placement(uuid, bookmark_id, folder_id, position, is_primary, title, add_date, extra) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(uuid) DO UPDATE SET bookmark_id = excluded.bookmark_id, folder_id = excluded.folder_id, position = excluded.position,
                     is_primary = excluded.is_primary, title = excluded.title, add_date = excluded.add_date, extra = excluded.extra""",
                pu, bid, fid, p.long("position") ?: 0, p.long("is_primary") ?: 0, p.str("title"), p.long("add_date"), p.str("extra"),
            )
        }
        if (v.containsKey("_placements")) {
            update("DELETE FROM placement WHERE bookmark_id = ? AND (uuid IS NULL OR uuid NOT IN (${keep.joinToString { "?" }.ifEmpty { "''" }}))", bid, *keep.toTypedArray())
        }

        // tags: exactly those from the entry
        if (v.containsKey("_tags")) {
            val names = (v["_tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val tagIds = names.map { name ->
                scalarOrNull("SELECT id FROM tag WHERE lower(name) = ?", name.lowercase()) ?: run {
                    update("INSERT INTO tag(name, color) VALUES (?, ?)", name, 0xFF888888)
                    scalar("SELECT id FROM tag WHERE lower(name) = ?", name.lowercase())
                }
            }
            update("DELETE FROM bookmark_tag WHERE bookmark_id = ? AND tag_id NOT IN (${tagIds.joinToString { "?" }.ifEmpty { "-1" }})", bid, *tagIds.toTypedArray())
            tagIds.forEach { update("INSERT OR IGNORE INTO bookmark_tag(bookmark_id, tag_id) VALUES (?, ?)", bid, it) }
        }
        return true
    }

    private fun delete(e: Entry) {
        when (e.e) {
            "folder" -> if (e.k != ROOT) update("DELETE FROM folder WHERE uuid = ? AND parent_id IS NOT NULL", e.k)
            "bookmark" -> update("DELETE FROM bookmark WHERE uuid = ?", e.k)
            "tag" -> update("DELETE FROM tag WHERE lower(name) = ?", e.k)
            "setting" -> update("DELETE FROM setting WHERE key = ?", e.k)
            "tag_i18n" -> update("DELETE FROM tag_i18n WHERE lower(name) = ?", e.k)
        }
    }

    // ------------------------------------------------------------------------------------------------ Log

    /** Locally changed, not yet transferred entries: kind, key, change time, deleted. */
    fun pending(): List<Entry> {
        // placements and tag assignments live in the bookmark entry (triggers also mark the bookmark)
        exec("UPDATE sync_change SET pending = 0 WHERE pending = 1 AND entity IN ('placement', 'bookmark_tag')")
        val out = ArrayList<Entry>()
        query("SELECT entity, key, changed_at, deleted FROM sync_change WHERE pending = 1 ORDER BY changed_at") { rs ->
            if (rs.getString(1) != "folder" || rs.getString(2) != ROOT) out += Entry(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4) == 1)
        }
        return out
    }

    /** Change time and status of a locally changed entry (null = not pending). */
    fun pendingT(kind: String, key: String): Long? =
        scalarOrNull("SELECT changed_at FROM sync_change WHERE entity = ? AND key = ? AND pending = 1", kind, key)

    fun localT(kind: String, key: String): Long? = scalarOrNull("SELECT changed_at FROM sync_change WHERE entity = ? AND key = ?", kind, key)

    /** Mark as transferred – but only if nothing has changed since. */
    fun markDone(kind: String, key: String, t: Long) =
        update("UPDATE sync_change SET pending = 0 WHERE entity = ? AND key = ? AND changed_at = ?", kind, key, t)

    /** Queue all local entries for upload (very old timestamp: versions present at the target win). */
    fun markAllPending() = transaction {
        allKeys().forEach { (e, k) ->
            update("INSERT OR IGNORE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES (?, ?, 1, 0, 1)", e, k)
            update("UPDATE sync_change SET pending = 1 WHERE entity = ? AND key = ?", e, k)
        }
    }

    fun wipe() = transaction {
        exec("INSERT OR REPLACE INTO setting(key, value) VALUES ('sync.applying', '1')")
        exec("DELETE FROM bookmark_tag")
        exec("DELETE FROM placement")
        exec("DELETE FROM bookmark")
        exec("DELETE FROM tag")
        exec("DELETE FROM tag_i18n")
        exec("DELETE FROM folder WHERE parent_id IS NOT NULL")
        exec("DELETE FROM sync_change")
        exec("DELETE FROM setting WHERE key = 'sync.applying'")
    }

    // ------------------------------------------------------------------------------------------------ Media in screenshots

    private fun exportShots(shotsJson: String): JsonArray? {
        val arr = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(shotsJson) as? JsonArray }.getOrNull() ?: return null
        return JsonArray(arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val hash = (o["p"] as? JsonPrimitive)?.contentOrNull?.let { media?.upload(it) } ?: return@mapNotNull null
            JsonObject(mapOf("h" to (o["h"] ?: JsonPrimitive(hash)), "m" to JsonPrimitive(hash), "s" to (o["s"] ?: JsonPrimitive("unknown")), "x" to (o["x"] ?: JsonPrimitive(0))))
        })
    }

    private fun importShots(el: JsonElement?): String? {
        val arr = el as? JsonArray ?: return null
        val out = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val path = (o["m"] as? JsonPrimitive)?.contentOrNull?.let { media?.download(it) } ?: return@mapNotNull null
            JsonObject(mapOf("h" to (o["h"] ?: JsonPrimitive(path)), "p" to JsonPrimitive(path), "s" to (o["s"] ?: JsonPrimitive("unknown")), "x" to (o["x"] ?: JsonPrimitive(0))))
        }
        return if (out.isEmpty()) null else JsonArray(out).toString()
    }

    // ------------------------------------------------------------------------------------------------ JDBC helpers

    fun <T> transaction(block: () -> T): T {
        val auto = c.autoCommit
        if (!auto) return block()
        c.autoCommit = false
        try {
            val r = block()
            c.commit()
            return r
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    private fun bind(st: PreparedStatement, args: Array<out Any?>) = args.forEachIndexed { i, a ->
        when (a) {
            null -> st.setObject(i + 1, null)
            is Long -> st.setLong(i + 1, a)
            is Int -> st.setInt(i + 1, a)
            is Double -> st.setDouble(i + 1, a)
            is ByteArray -> st.setBytes(i + 1, a)
            else -> st.setString(i + 1, a.toString())
        }
    }

    fun update(sql: String, vararg args: Any?) = c.prepareStatement(sql).use { bind(it, args); it.executeUpdate() }
    fun exec(sql: String) = c.createStatement().use { it.execute(sql) }
    fun query(sql: String, vararg args: Any?, row: (ResultSet) -> Unit) =
        c.prepareStatement(sql).use { st -> bind(st, args); st.executeQuery().use { rs -> while (rs.next()) row(rs) } }

    fun scalar(sql: String, vararg args: Any?): Long = scalarOrNull(sql, *args) ?: 0

    fun scalarOrNull(sql: String, vararg args: Any?): Long? =
        c.prepareStatement(sql).use { st -> bind(st, args); st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).takeUnless { rs.wasNull() } else null } }

    fun text(sql: String, vararg args: Any?): String? =
        c.prepareStatement(sql).use { st -> bind(st, args); st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }

    private fun rowJson(rs: ResultSet): JsonObject {
        val md = rs.metaData
        return JsonObject((1..md.columnCount).associate { i ->
            md.getColumnLabel(i) to when (val o = rs.getObject(i)) {
                null -> JsonNull
                is Int -> JsonPrimitive(o.toLong())
                is Long -> JsonPrimitive(o)
                is Double -> JsonPrimitive(o)
                is Float -> JsonPrimitive(o.toDouble())
                is ByteArray -> JsonObject(mapOf("b64" to JsonPrimitive(Base64.getEncoder().encodeToString(o))))
                else -> JsonPrimitive(o.toString())
            }
        })
    }

    private fun one(sql: String, vararg args: Any?): JsonObject? =
        c.prepareStatement(sql).use { st -> bind(st, args); st.executeQuery().use { rs -> if (rs.next()) rowJson(rs) else null } }

    private val columnCache = HashMap<String, Set<String>>()
    private fun columns(table: String): Set<String> = columnCache.getOrPut(table) {
        val out = LinkedHashSet<String>()
        query("PRAGMA table_info($table)") { out += it.getString("name") }
        out
    }

    private fun idByUuid(table: String, uuid: String?): Long? = uuid?.let { scalarOrNull("SELECT id FROM $table WHERE uuid = ?", it) }

    private fun folderId(ref: String?): Long? = when (ref) {
        null -> null
        ROOT -> scalarOrNull("SELECT id FROM folder WHERE parent_id IS NULL ORDER BY id LIMIT 1")
            ?: run { update("INSERT INTO folder(uuid, parent_id, title, position) VALUES (lower(hex(randomblob(16))), NULL, 'Bookmarks', 0)"); folderId(ROOT) }
        else -> idByUuid("folder", ref)
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bytes(k: String): ByteArray? = (this[k] as? JsonObject)?.get("b64")?.jsonPrimitive?.contentOrNull?.let { Base64.getDecoder().decode(it) }
    private fun JsonObject.value(k: String): Any? = when (val el: JsonElement? = this[k]) {
        null, is JsonNull -> null
        is JsonObject -> el["b64"]?.jsonPrimitive?.contentOrNull?.let { Base64.getDecoder().decode(it) }
        is JsonPrimitive -> if (el.isString) el.content else el.longOrNull ?: el.doubleOrNull ?: el.booleanOrNull?.let { if (it) 1L else 0L }
        else -> null
    }
    private val JsonPrimitive.long: Long get() = longOrNull ?: content.toLong()
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    companion object {
        const val ROOT = "@root"
        /** Local image paths and their key in the entry (image is transferred as medium). */
        val MEDIA_COLUMNS = linkedMapOf("thumbnail_path" to "thumb_hash", "card_image_path" to "card_hash", "thumb_custom_path" to "thumbc_hash")
        val ICON_COLUMNS = setOf("favicon_hash", "avatar_hash", "flathub_icon_hash", "alternativeto_icon_hash", "website_icon_hash")
        /** Order when applying (dependencies first). */
        val ORDER = listOf("setting", "icon", "tag_i18n", "tag", "folder", "bookmark")
        val KINDS = ORDER.toSet()
        private val SYNCED_SETTING_KEYS = setOf("savedSearches")
        private const val SYNCED_SETTINGS = "'savedSearches'"

        fun <T> open(dbFile: File, block: (Connection) -> T): T {
            Class.forName("org.sqlite.JDBC")
            return DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("PRAGMA foreign_keys = ON")
                    st.execute("PRAGMA busy_timeout = 30000")
                }
                block(conn)
            }
        }
    }
}
