package de.bookmarksplus.data

import de.bookmarksplus.db.BookmarksDb

class TagRepository(private val db: BookmarksDb) {

    /** Creates a tag or returns the existing one (case-insensitive). */
    fun getOrCreate(name: String, color: Long): Long = db.transactionWithResult {
        // transaction: last_insert_rowid() is only valid on the same connection (not guaranteed for file DBs)
        val clean = name.trim().removePrefix("#")
        db.tagQueries.tagByName(clean).executeAsOneOrNull()?.let { return@transactionWithResult it.id }
        db.tagQueries.insertTag(clean, color)
        db.schemaQueries.lastInsertId().executeAsOne()
    }

    fun update(id: Long, name: String, color: Long) = db.tagQueries.updateTag(name.trim(), color, id)

    fun delete(id: Long) = db.tagQueries.deleteTag(id)

    fun add(bookmarkIds: Collection<Long>, tagId: Long) = db.transaction {
        bookmarkIds.forEach { db.tagQueries.addTag(it, tagId) }
    }

    fun remove(bookmarkIds: Collection<Long>, tagId: Long) = db.transaction {
        bookmarkIds.forEach { db.tagQueries.removeTag(it, tagId) }
    }

    companion object {
        /** Palette for new tags (ARGB), easily readable on a dark background. */
        val PALETTE: List<Long> = listOf(
            0xFFEF5350, 0xFFFF7043, 0xFFFFCA28, 0xFF9CCC65, 0xFF26A69A,
            0xFF29B6F6, 0xFF5C6BC0, 0xFFAB47BC, 0xFFEC407A, 0xFF8D6E63, 0xFF78909C,
        )

        fun colorFor(name: String): Long = PALETTE[(name.lowercase().hashCode() and 0x7FFFFFFF) % PALETTE.size]
    }
}

class SettingsRepository(private val db: BookmarksDb) {
    fun get(key: String): String? = db.settingQueries.getSetting(key).executeAsOneOrNull()
    fun put(key: String, value: String) = db.settingQueries.putSetting(key, value)
}
