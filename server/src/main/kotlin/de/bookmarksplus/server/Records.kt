package de.bookmarksplus.server

import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/** Metadata of an entry – this is all the server sees (content is end-to-end encrypted). */
data class RecordMeta(val id: String, val rev: String, val seq: Long, val updated: Long, val deleted: Boolean)

sealed interface PutResult {
    val id: String
    data class Ok(override val id: String, val rev: String, val seq: Long) : PutResult
    /** The revision expected by the client is not (or no longer) the current one. */
    data class Conflict(override val id: String, val rev: String?, val deleted: Boolean) : PutResult
}

class PutItem(val id: String, val base: String?, val data: ByteArray?)

/**
 * Record store of a user (SQLite file in the user folder).
 *
 * - `rev` = SHA-256 of the ciphertext, `seq` = consecutive change number (assigned by the server, independent of client
 *   clocks). Clients only fetch what changed since their last `seq`.
 * - Writing is a compare-and-swap: the client states the revision its change is based on
 *   (`null` = new entry). If the current one differs, nothing is written and a conflict is reported.
 * - Deletions remain as a tombstone (without content) for [TOMBSTONE_DAYS] days so that all devices notice them.
 */
class RecordDb(file: File) : AutoCloseable {
    private val c: Connection

    init {
        file.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        c = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        c.createStatement().use { st ->
            st.execute("PRAGMA journal_mode = WAL")
            st.execute("PRAGMA busy_timeout = 30000")
            st.execute(
                """CREATE TABLE IF NOT EXISTS rec (
                     id TEXT PRIMARY KEY, rev TEXT NOT NULL, seq INTEGER NOT NULL, updated INTEGER NOT NULL,
                     deleted INTEGER NOT NULL DEFAULT 0, data BLOB)""",
            )
            st.execute("CREATE INDEX IF NOT EXISTS rec_seq ON rec(seq)")
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        }
    }

    private fun meta(key: String): Long =
        c.prepareStatement("SELECT value FROM meta WHERE key = ?").use { st -> st.setString(1, key); st.executeQuery().use { if (it.next()) it.getLong(1) else 0 } }

    private fun setMeta(key: String, value: Long) =
        c.prepareStatement("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)").use { it.setString(1, key); it.setLong(2, value); it.executeUpdate() }

    /** Highest assigned change number. */
    val maxSeq: Long @Synchronized get() = meta("seq")

    /** Tombstones up to this number have been removed; anyone older has to fetch the full list. */
    val purgedSeq: Long @Synchronized get() = meta("purged")

    @Synchronized
    fun count(): Long = c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM rec WHERE deleted = 0").use { it.next(); it.getLong(1) } }

    @Synchronized
    fun changes(since: Long, limit: Int): List<RecordMeta> =
        c.prepareStatement("SELECT id, rev, seq, updated, deleted FROM rec WHERE seq > ? ORDER BY seq LIMIT ?").use { st ->
            st.setLong(1, since); st.setInt(2, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(RecordMeta(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getInt(5) == 1)) }
            }
        }

    @Synchronized
    fun get(ids: Collection<String>): List<Pair<RecordMeta, ByteArray>> = ids.mapNotNull { id ->
        c.prepareStatement("SELECT id, rev, seq, updated, deleted, data FROM rec WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next() || rs.getInt(5) == 1) null
                else RecordMeta(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), false) to rs.getBytes(6)
            }
        }
    }

    /** Writes all entries in one transaction; conflicts only affect the respective entry. */
    @Synchronized
    fun put(items: List<PutItem>, now: Long = System.currentTimeMillis()): List<PutResult> {
        c.autoCommit = false
        try {
            var seq = meta("seq")
            val out = items.map { item ->
                val current = c.prepareStatement("SELECT rev, deleted FROM rec WHERE id = ?").use { st ->
                    st.setString(1, item.id)
                    st.executeQuery().use { if (it.next()) it.getString(1) to (it.getInt(2) == 1) else null }
                }
                // tombstone counts as "not present" for a new entry
                val currentRev = current?.takeUnless { it.second && item.base == null }?.first
                if (currentRev != item.base) return@map PutResult.Conflict(item.id, current?.first, current?.second ?: false)
                val data = item.data
                val rev = if (data == null) "deleted-" + sha256(item.id.toByteArray() + now.toString().toByteArray()) else sha256(data)
                if (current == null && data == null) return@map PutResult.Ok(item.id, rev, seq) // deleting an unknown entry
                seq++
                c.prepareStatement("INSERT OR REPLACE INTO rec(id, rev, seq, updated, deleted, data) VALUES (?, ?, ?, ?, ?, ?)").use { st ->
                    st.setString(1, item.id); st.setString(2, rev); st.setLong(3, seq); st.setLong(4, now)
                    st.setInt(5, if (data == null) 1 else 0); st.setBytes(6, data)
                    st.executeUpdate()
                }
                PutResult.Ok(item.id, rev, seq)
            }
            setMeta("seq", seq)
            c.commit()
            return out
        } catch (e: Throwable) {
            c.rollback(); throw e
        } finally {
            c.autoCommit = true
        }
    }

    /** Removes old tombstones. */
    @Synchronized
    fun purge(now: Long = System.currentTimeMillis(), days: Int = TOMBSTONE_DAYS) {
        val cutoff = now - days * 86_400_000L
        val maxPurged = c.prepareStatement("SELECT max(seq) FROM rec WHERE deleted = 1 AND updated < ?").use { st ->
            st.setLong(1, cutoff); st.executeQuery().use { if (it.next()) it.getLong(1) else 0 }
        }
        if (maxPurged <= 0) return
        c.prepareStatement("DELETE FROM rec WHERE deleted = 1 AND updated < ?").use { it.setLong(1, cutoff); it.executeUpdate() }
        setMeta("purged", maxOf(meta("purged"), maxPurged))
    }

    override fun close() = c.close()

    companion object {
        const val TOMBSTONE_DAYS = 180
        val VALID_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
