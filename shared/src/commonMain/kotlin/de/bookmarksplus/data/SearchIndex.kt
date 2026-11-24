package de.bookmarksplus.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Full-text index (SQLite FTS5) over title, description, summary, URL, README and page text
 * (page text goes into the readme column).
 * Lives outside the SQLDelight schema because its grammar does not know FTS5 tables.
 */
class SearchIndex(private val driver: SqlDriver) {

    fun ensure() {
        driver.execute(
            null,
            """CREATE VIRTUAL TABLE IF NOT EXISTS bookmark_fts USING fts5(
                title, ai_title, description, summary, url, readme,
                tokenize = 'unicode61 remove_diacritics 2')""",
            0,
        )
    }

    fun rebuild() {
        ensure()
        driver.execute(null, "DELETE FROM bookmark_fts", 0)
        driver.execute(
            null,
            """INSERT INTO bookmark_fts(rowid, title, ai_title, description, summary, url, readme)
               $SELECT WHERE deleted_at IS NULL""",
            0,
        )
    }

    /** Updates the index entry of a bookmark (after enrichment/editing). */
    fun update(id: Long) {
        driver.execute(null, "DELETE FROM bookmark_fts WHERE rowid = ?", 1) { bindLong(0, id) }
        driver.execute(null, "INSERT INTO bookmark_fts(rowid, title, ai_title, description, summary, url, readme) $SELECT WHERE id = ? AND deleted_at IS NULL", 1) { bindLong(0, id) }
    }

    fun count(): Long = runCatching {
        driver.executeQuery(null, "SELECT count(*) FROM bookmark_fts", { c ->
            QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0 else 0)
        }, 0).value
    }.getOrDefault(-1)

    /** Hits for all terms (prefix search, AND-combined). */
    fun search(terms: List<String>, limit: Int = 5000): Set<Long> {
        val q = toMatch(terms) ?: return emptySet()
        return runCatching {
            driver.executeQuery(null, "SELECT rowid FROM bookmark_fts WHERE bookmark_fts MATCH ? LIMIT $limit", { c ->
                val out = HashSet<Long>()
                while (c.next().value) c.getLong(0)?.let(out::add)
                QueryResult.Value(out)
            }, 1) { bindString(0, q) }.value
        }.getOrDefault(emptySet())
    }

    /** Full-text hits per (lowercase) term, so that terms may match in different places (title, README …). */
    fun searchEach(terms: List<String>, limit: Int = 5000): Map<String, Set<Long>> =
        terms.map { it.lowercase() }.distinct().associateWith { search(listOf(it), limit) }

    companion object {
        private const val SELECT = "SELECT id, coalesce(title, '') || ' ' || coalesce(meta_title, ''), ai_title, " +
            "coalesce(description, '') || ' ' || coalesce(meta_description, ''), summary, url, " +
            "coalesce(readme, '') || ' ' || coalesce(page_text, '') FROM bookmark"

        internal fun toMatch(terms: List<String>): String? {
            val parts = terms.map { t -> t.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' } }
                .filter { it.isNotEmpty() }
                .map { "\"$it\"*" }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
    }
}
