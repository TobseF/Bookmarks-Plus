package de.bookmarksplus.enrich

import de.bookmarksplus.data.Shot
import de.bookmarksplus.data.Shots
import de.bookmarksplus.db.BookmarksDb
import de.bookmarksplus.util.sha256Hex

/**
 * Stores screenshots of an application: file via [MediaStore] (shrunk), entry in `bookmark.shots_json`
 * with source. Images whose content already exists (even hidden) are not stored again.
 */
object ShotStore {
    /**
     * Appends [images] to the screenshots of bookmark [id]. [write] performs the DB access under the shared
     * write lock. Returns the number of newly added images.
     */
    suspend fun add(
        db: BookmarksDb, media: MediaStore, id: Long, uuid: String, source: String, images: List<ByteArray>,
        write: suspend (() -> Unit) -> Unit,
    ): Int {
        if (images.isEmpty()) return 0
        val known = Shots.parse(db.enrichQueries.shotsOf(id).executeAsOneOrNull()?.shots_json).mapTo(HashSet()) { it.hash }
        val fresh = ArrayList<Shot>()
        for (bytes in images) {
            val hash = sha256Hex(bytes)
            if (hash in known) continue
            val path = media.save(MediaStore.Kind.Screenshot, "$uuid-${hash.take(12)}", bytes) ?: continue
            known += hash
            fresh += Shot(hash, path, source)
        }
        if (fresh.isEmpty()) return 0
        write {
            // read current state only under the lock so that parallel changes (hiding) are not lost
            val cur = Shots.parse(db.enrichQueries.shotsOf(id).executeAsOneOrNull()?.shots_json)
            db.enrichQueries.setShots(Shots.encode(Shots.merge(cur, fresh)), id)
        }
        return fresh.size
    }
}
