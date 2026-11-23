package de.bookmarksplus.data

import de.bookmarksplus.data.NetscapeAttrs.DD
import de.bookmarksplus.data.NetscapeAttrs.ORDER
import de.bookmarksplus.db.BookmarksDb
import de.bookmarksplus.db.EntriesInFolder
import de.bookmarksplus.netscape.NsFolder
import de.bookmarksplus.netscape.NsLink
import de.bookmarksplus.util.Urls
import de.bookmarksplus.util.newUuid
import de.bookmarksplus.util.nowEpochSeconds
import de.bookmarksplus.util.sha256Hex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64

data class ImportResult(val folders: Int, val links: Int, val newBookmarks: Int, val linkedDuplicates: Int)

data class Stats(val folders: Long, val bookmarks: Long, val placements: Long)

class BookmarkRepository(private val db: BookmarksDb) {

    private val folders get() = db.folderQueries
    private val bookmarks get() = db.bookmarkQueries

    fun stats() = Stats(
        folders = folders.countFolders().executeAsOne(),
        bookmarks = bookmarks.countBookmarks().executeAsOne(),
        placements = bookmarks.countPlacements().executeAsOne(),
    )

    fun rootFolderId(title: String = "Bookmarks"): Long = db.transactionWithResult {
        folders.root().executeAsOneOrNull()?.let { return@transactionWithResult it.id }
        folders.insertFolder(newUuid(), null, title, 0, nowEpochSeconds(), null, null)
        db.schemaQueries.lastInsertId().executeAsOne()
    }

    // ---------------------------------------------------------------- Import

    /** Base data of an already imported bookmark, to store duplicates as a deviation (diff). */
    private class Base(val bookmarkId: Long, val title: String, val attrs: Map<String, String>, val order: List<String>?)

    /**
     * Imports a Netscape tree. Identical URLs are merged into one bookmark with several placements;
     * deviations of the copies (title, date, description …) are stored per placement,
     * so that the export restores the file unchanged.
     */
    fun import(tree: NsFolder, intoFolderId: Long? = null): ImportResult {
        var folderCount = 0
        var linkCount = 0
        var newCount = 0
        var dupCount = 0
        val cache = HashMap<String, Base>()

        fun importLink(link: NsLink, folderId: Long, position: Long) {
            linkCount++
            val href = link.href
            val full = fullAttrs(link)
            val base = cache[href] ?: bookmarks.byUrl(href).executeAsOneOrNull()?.let { b ->
                val (attrs, order) = reconstruct(b.url, b.add_date, b.last_modified, b.description, b.extra, icon(b.favicon_hash))
                Base(b.id, b.title, attrs, order)
            }
            if (base == null) {
                newCount++
                val attrs = link.attrs
                var iconHash: String? = null
                var columns = NetscapeAttrs.LINK_COLUMNS
                attrs[NsLink.ICON]?.let { raw ->
                    iconHash = storeIcon(raw)
                    if (iconHash == null) columns = columns - NsLink.ICON
                }
                val flags = LinkedHashMap<String, JsonElement>()
                val description = attrs[NsLink.DESCRIPTION] ?: link.dd
                if (link.dd != null) {
                    if (attrs[NsLink.DESCRIPTION] == null) flags[DD_AS_DESC] = JsonPrimitive(true)
                    else flags[DD] = JsonPrimitive(link.dd)
                }
                val addDate = attrs[NsFolder.ADD_DATE]?.toLongOrNull()
                bookmarks.insertBookmark(
                    uuid = newUuid(),
                    url = href,
                    url_norm = Urls.normalize(href),
                    title = link.title,
                    description = description,
                    domain = Urls.domain(href),
                    favicon_hash = iconHash,
                    add_date = addDate,
                    last_modified = attrs[NsFolder.LAST_MODIFIED]?.toLongOrNull(),
                    last_visited = attrs["LAST_VISIT"]?.toLongOrNull(),
                    extra = NetscapeAttrs.extraFor(attrs, columns, flags),
                )
                val id = db.schemaQueries.lastInsertId().executeAsOne()
                bookmarks.insertPlacement(id, folderId, position, 1, null, addDate, null)
                val keys = attrs.keys.toList()
                cache[href] = Base(id, link.title, full, keys.takeIf { it != NetscapeAttrs.canonicalOrder(it) })
            } else {
                dupCount++
                val d = NetscapeAttrs.diff(base.attrs, full)
                val resultKeys = NetscapeAttrs.ordered(NetscapeAttrs.applyDiff(base.attrs, d).withoutMeta(), base.order).keys.toList()
                val wanted = link.attrs.keys.toList()
                if (resultKeys != wanted) d[ORDER] = JsonPrimitive(wanted.joinToString(","))
                bookmarks.insertPlacement(
                    bookmark_id = base.bookmarkId,
                    folder_id = folderId,
                    position = position,
                    is_primary = 0,
                    title = link.title.takeIf { it != base.title },
                    add_date = link.attrs[NsFolder.ADD_DATE]?.toLongOrNull(),
                    extra = NetscapeAttrs.encode(d),
                )
            }
        }

        fun importChildren(src: NsFolder, folderId: Long, startPos: Long) {
            var pos = startPos
            for (child in src.children) {
                when (child) {
                    is NsFolder -> {
                        folderCount++
                        folders.insertFolder(
                            uuid = newUuid(),
                            parent_id = folderId,
                            title = child.title,
                            position = pos++,
                            add_date = child.addDate,
                            last_modified = child.lastModified,
                            extra = NetscapeAttrs.extraFor(child.attrs, NetscapeAttrs.FOLDER_COLUMNS),
                        )
                        importChildren(child, db.schemaQueries.lastInsertId().executeAsOne(), 0)
                    }
                    is NsLink -> importLink(child, folderId, pos++)
                }
            }
        }

        db.transaction {
            val target = intoFolderId ?: rootFolderId(tree.title)
            val start = (folders.maxChildPosition(target).executeAsOneOrNull()?.max ?: -1L) + 1
            importChildren(tree, target, start)
        }
        return ImportResult(folderCount, linkCount, newCount, dupCount)
    }

    /** Attribute map of a link incl. `<DD>` as meta key. */
    private fun fullAttrs(link: NsLink): Map<String, String> {
        val m = LinkedHashMap(link.attrs)
        link.dd?.let { m[DD] = it }
        return m
    }

    /** Stores a `data:` icon if it can be decoded/encoded losslessly. Returns the hash. */
    private fun storeIcon(raw: String): String? {
        val m = DATA_URI.matchEntire(raw) ?: return null
        val (mime, b64) = m.destructured
        val bytes = runCatching { Base64.decode(b64) }.getOrNull() ?: return null
        if (Base64.encode(bytes) != b64) return null
        val hash = sha256Hex(bytes)
        bookmarks.insertIcon(hash, mime, bytes)
        return hash
    }

    private fun icon(hash: String?): Pair<String, ByteArray>? =
        hash?.let { bookmarks.iconByHash(it).executeAsOneOrNull() }?.let { it.mime to it.data_ }

    /** Rebuilds the Netscape attributes of a bookmark from columns + extra. */
    private fun reconstruct(
        url: String, addDate: Long?, lastModified: Long?, description: String?, extraJson: String?,
        icon: Pair<String, ByteArray>?,
    ): Pair<LinkedHashMap<String, String>, List<String>?> {
        val extra = NetscapeAttrs.decode(extraJson)
        val m = LinkedHashMap<String, String>()
        m[NsLink.HREF] = url
        addDate?.let { m[NsFolder.ADD_DATE] = it.toString() }
        lastModified?.let { m[NsFolder.LAST_MODIFIED] = it.toString() }
        extra.forEach { (k, v) -> if (!k.startsWith("#")) m[k] = NetscapeAttrs.string(v)!! }
        val ddAsDesc = extra[DD_AS_DESC] != null
        if (description != null && !ddAsDesc) m[NsLink.DESCRIPTION] = description
        if (icon != null) m[NsLink.ICON] = "data:${icon.first};base64," + Base64.encode(icon.second)
        val order = NetscapeAttrs.orderOf(extra)
        val ordered = NetscapeAttrs.ordered(m, order)
        if (ddAsDesc && description != null) ordered[DD] = description
        NetscapeAttrs.string(extra[DD])?.let { ordered[DD] = it }
        return ordered to order
    }

    // ---------------------------------------------------------------- Export

    fun export(): NsFolder {
        val root = folders.root().executeAsOneOrNull() ?: return NsFolder("Bookmarks")
        val tree = NsFolder(root.title)
        exportInto(root.id, tree)
        return tree
    }

    private fun exportInto(folderId: Long, target: NsFolder) {
        val subFolders = folders.children(folderId).executeAsList()
        val entries = bookmarks.entriesInFolder(folderId).executeAsList()
        var fi = 0
        var ei = 0
        while (fi < subFolders.size || ei < entries.size) {
            val takeFolder = ei >= entries.size || (fi < subFolders.size && subFolders[fi].position <= entries[ei].position)
            if (takeFolder) {
                val f = subFolders[fi++]
                val extra = NetscapeAttrs.decode(f.extra)
                val m = LinkedHashMap<String, String>()
                f.add_date?.let { m[NsFolder.ADD_DATE] = it.toString() }
                f.last_modified?.let { m[NsFolder.LAST_MODIFIED] = it.toString() }
                extra.forEach { (k, v) -> if (!k.startsWith("#")) m[k] = NetscapeAttrs.string(v)!! }
                val node = NsFolder(f.title, NetscapeAttrs.ordered(m, NetscapeAttrs.orderOf(extra)))
                target.children += node
                exportInto(f.id, node)
            } else {
                target.children += toLink(entries[ei++])
            }
        }
    }

    private fun toLink(e: EntriesInFolder): NsLink {
        val icon = if (e.icon_mime != null && e.icon_data != null) e.icon_mime to e.icon_data else null
        val (base, baseOrder) = reconstruct(e.url, e.add_date, e.last_modified, e.description, e.extra, icon)
        val d = NetscapeAttrs.decode(e.placement_extra)
        val m = NetscapeAttrs.applyDiff(base, d)
        val order = NetscapeAttrs.orderOf(d) ?: baseOrder
        return NsLink(
            title = e.placement_title ?: e.title,
            attrs = NetscapeAttrs.ordered(m.withoutMeta(), order),
            dd = m[DD],
        )
    }

    // ---------------------------------------------------------------- Links

    /**
     * Removes a placement. The remaining links are kept; if it was the primary one,
     * the next one becomes primary. If it was the last one, the bookmark moves to the trash.
     */
    fun deletePlacement(placementId: Long) = db.transaction {
        val p = bookmarks.placementById(placementId).executeAsOneOrNull() ?: return@transaction
        bookmarks.deletePlacement(placementId)
        val remaining = bookmarks.placementsOfBookmark(p.bookmark_id).executeAsList()
        when {
            remaining.isEmpty() -> {
                bookmarks.setTrashPath(folderPath(p.folder_id), p.bookmark_id)
                bookmarks.moveToTrash(nowEpochSeconds(), p.bookmark_id)
            }
            p.is_primary == 1L -> bookmarks.setPrimary(remaining.first().id, p.bookmark_id)
        }
    }

    /** Path "A / B" of a folder (without root). */
    fun folderPath(folderId: Long): String {
        val names = ArrayList<String>()
        var cur = folders.byId(folderId).executeAsOneOrNull()
        while (cur?.parent_id != null) {
            names += cur.title
            cur = folders.byId(cur.parent_id!!).executeAsOneOrNull()
        }
        return names.asReversed().joinToString(" / ")
    }

    // ---------------------------------------------------------------- Trash

    /** Bookmark including all links to the trash (placements are kept for restoring). */
    fun trash(ids: Collection<Long>) = db.transaction {
        val now = nowEpochSeconds()
        ids.forEach { bookmarks.moveToTrash(now, it) }
    }

    /** Restore from the trash; without a remaining placement into the original folder (recreated if necessary). */
    fun restore(ids: Collection<Long>) = db.transaction {
        ids.forEach { id ->
            val b = bookmarks.byId(id).executeAsOneOrNull() ?: return@forEach
            bookmarks.restoreFromTrash(id)
            if (bookmarks.placementsOfBookmark(id).executeAsList().isEmpty()) {
                val folder = b.trash_path?.takeIf { it.isNotBlank() }?.let { ensureFolderPath(it) } ?: rootFolderId()
                bookmarks.insertPlacement(id, folder, nextPosition(folder), 1L, null, null, null)
            }
            bookmarks.clearTrashPath(id)
        }
    }

    fun deleteForever(ids: Collection<Long>) = db.transaction { ids.forEach { bookmarks.deleteForever(it) } }

    fun emptyTrash(): Int = db.transactionWithResult {
        val ids = bookmarks.trashedIds().executeAsList()
        ids.forEach { bookmarks.deleteForever(it) }
        ids.size
    }

    // ---------------------------------------------------------------- Editing

    /**
     * Changes texts of a bookmark. `null` = leave field unchanged. The description is stored encoded in a
     * Netscape-compatible way so that the export stays valid.
     */
    fun edit(id: Long, title: String? = null, url: String? = null, description: String? = null, summary: String? = null, readme: String? = null) =
        db.transaction {
            val b = bookmarks.byId(id).executeAsOneOrNull() ?: return@transaction
            if (title != null || url != null) {
                val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: b.url
                bookmarks.updateTexts(title?.trim() ?: b.title, u, Urls.normalize(u), Urls.domain(u), nowEpochSeconds(), id)
            }
            description?.let { bookmarks.updateDescription(it.trim().takeIf { t -> t.isNotEmpty() }?.let(Text::encodeDescription), id) }
            summary?.let { bookmarks.updateSummary(it.trim().ifEmpty { null }, id) }
            readme?.let { bookmarks.updateReadme(it.ifBlank { null }, id) }
        }

    // ---------------------------------------------------------------- Adding, duplicates

    /**
     * New bookmark in [folderId]. If the (normalized) URL already exists, nothing is created and
     * `false` is returned with the existing ID.
     */
    fun add(url: String, title: String, folderId: Long): Pair<Long, Boolean> = db.transactionWithResult {
        val u = url.trim()
        val norm = Urls.normalize(u)
        bookmarks.allUrls().executeAsList().firstOrNull { it.url_norm == norm }?.let { existing ->
            val b = bookmarks.byId(existing.id).executeAsOne()
            if (b.deleted_at == null) return@transactionWithResult existing.id to false
        }
        val now = nowEpochSeconds()
        bookmarks.insertBookmark(newUuid(), u, norm, title.trim().ifEmpty { Urls.domain(u) ?: u }, null, Urls.domain(u), null, now, now, null, null)
        val id = db.schemaQueries.lastInsertId().executeAsOne()
        bookmarks.insertPlacement(id, folderId, nextPosition(folderId), 1L, null, null, null)
        id to true
    }

    /** Recomputes `url_norm` (after changes to [Urls.normalize]). Returns the number of changed entries. */
    fun renormalize(): Int = db.transactionWithResult {
        var n = 0
        bookmarks.allUrls().executeAsList().forEach { r ->
            val norm = Urls.normalize(r.url)
            if (norm != r.url_norm) { bookmarks.setUrlNorm(norm, r.id); n++ }
        }
        n
    }

    /**
     * Merges duplicates into [keepId]: folders become links (differing titles are kept per folder),
     * tags are merged, missing fields are filled in. The remaining entries go to the trash.
     */
    fun merge(keepId: Long, others: Collection<Long>) = db.transaction {
        val keep = bookmarks.byId(keepId).executeAsOneOrNull() ?: return@transaction
        val now = nowEpochSeconds()
        others.filter { it != keepId }.forEach { oid ->
            val o = bookmarks.byId(oid).executeAsOneOrNull() ?: return@forEach
            val keepFolders = bookmarks.placementsOfBookmark(keepId).executeAsList().map { it.folder_id }.toMutableSet()
            val own = bookmarks.placementsOfBookmark(oid).executeAsList()
            own.firstOrNull()?.let { bookmarks.setTrashPath(folderPath(it.folder_id), oid) }
            own.forEach { p ->
                if (p.folder_id in keepFolders) bookmarks.deletePlacement(p.id)
                else {
                    bookmarks.reassignPlacement(keepId, o.title.takeIf { it != keep.title }, p.id)
                    keepFolders += p.folder_id
                }
            }
            bookmarks.copyTags(keepId, oid)
            bookmarks.fillFrom(oid, keepId)
            bookmarks.moveToTrash(now, oid)
        }
    }

    // ---------------------------------------------------------------- Folders

    fun createFolder(parentId: Long, title: String): Long = db.transactionWithResult {
        folders.insertFolder(newUuid(), parentId, title.trim(), nextPosition(parentId), nowEpochSeconds(), null, null)
        db.schemaQueries.lastInsertId().executeAsOne()
    }

    fun renameFolder(id: Long, title: String) = folders.renameFolder(title.trim(), nowEpochSeconds(), id)

    /**
     * Deletes a folder including subfolders. Bookmarks that only lived there go to the trash
     * (with path for restoring); links in other folders are kept.
     * Returns the number of bookmarks moved to the trash.
     */
    fun deleteFolder(id: Long): Int = db.transactionWithResult {
        val subtree = HashSet<Long>()
        val stack = ArrayDeque<Long>().apply { add(id) }
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (subtree.add(f)) folders.children(f).executeAsList().forEach { stack.add(it.id) }
        }
        var trashed = 0
        subtree.forEach { fid ->
            db.bookmarkQueries.entriesInFolder(fid).executeAsList().forEach { e ->
                val before = bookmarks.placementsOfBookmark(e.id).executeAsList()
                if (before.all { it.folder_id in subtree }) trashed++
                deletePlacement(e.placement_id)
            }
        }
        folders.deleteFolder(id)
        trashed
    }

    /**
     * Merges folders of the same name directly under the root – e.g. "Inbox" if two devices created it before their first
     * sync. The oldest one is kept (on a tie the smaller UUID) so that all devices decide the same way;
     * bookmarks and subfolders move there. Returns the number of removed folders.
     */
    fun mergeRootFolders(title: String): Int = db.transactionWithResult {
        val root = folders.root().executeAsOneOrNull() ?: return@transactionWithResult 0
        val same = folders.children(root.id).executeAsList().filter { it.title.trim().equals(title, ignoreCase = true) }
        if (same.size < 2) return@transactionWithResult 0
        val keep = same.minWith(compareBy<de.bookmarksplus.db.Folder>({ it.add_date ?: Long.MAX_VALUE }, { it.uuid }))
        for (f in same) {
            if (f.id == keep.id) continue
            for (e in db.bookmarkQueries.entriesInFolder(f.id).executeAsList()) {
                val inKeep = bookmarks.placementsOfBookmark(e.id).executeAsList().any { it.folder_id == keep.id }
                if (inKeep) bookmarks.deletePlacement(e.placement_id)
                else bookmarks.movePlacement(keep.id, nextPosition(keep.id), e.placement_id)
            }
            folders.children(f.id).executeAsList().forEach { folders.moveFolder(keep.id, nextPosition(keep.id), it.id) }
            folders.deleteFolder(f.id)
        }
        same.size - 1
    }

    /** Moves a folder under another parent folder (not into itself or its own subfolders). */
    fun moveFolder(id: Long, newParentId: Long) = db.transaction {
        // not into itself or one of its own subfolders
        var cur: Long? = newParentId
        while (cur != null) {
            if (cur == id) return@transaction
            cur = folders.byId(cur).executeAsOneOrNull()?.parent_id
        }
        folders.moveFolder(newParentId, nextPosition(newParentId), id)
    }

    private fun nextPosition(folderId: Long): Long =
        (folders.maxChildPosition(folderId).executeAsOneOrNull()?.max ?: -1L) + 1

    /** Creates an additional link in [folderId] (if none exists there yet). */
    fun addPlacement(bookmarkId: Long, folderId: Long) = db.transaction {
        val existing = bookmarks.placementsOfBookmark(bookmarkId).executeAsList()
        if (existing.any { it.folder_id == folderId }) return@transaction
        bookmarks.insertPlacement(bookmarkId, folderId, nextPosition(folderId), if (existing.isEmpty()) 1L else 0L, null, null, null)
    }

    /**
     * Moves the bookmark to [folderId]: the primary placement moves there, other links remain.
     * If a link already exists there, it becomes primary and the previous primary is dropped.
     */
    fun moveTo(bookmarkId: Long, folderId: Long) = db.transaction {
        val existing = bookmarks.placementsOfBookmark(bookmarkId).executeAsList()
        val primary = existing.firstOrNull { it.is_primary == 1L } ?: existing.firstOrNull()
        val target = existing.firstOrNull { it.folder_id == folderId }
        when {
            primary == null -> bookmarks.insertPlacement(bookmarkId, folderId, nextPosition(folderId), 1L, null, null, null)
            target != null && target.id != primary.id -> {
                bookmarks.deletePlacement(primary.id)
                bookmarks.setPrimary(target.id, bookmarkId)
            }
            target == null -> bookmarks.movePlacement(folderId, nextPosition(folderId), primary.id)
        }
    }

    /** Folder for a path like "Tech / Kotlin" below the root; missing levels are created. */
    fun ensureFolderPath(path: String): Long = db.transactionWithResult {
        var parent = rootFolderId()
        for (name in path.split('/').map { it.trim() }.filter { it.isNotEmpty() }) {
            val found = folders.children(parent).executeAsList().firstOrNull { it.title.trim().equals(name, ignoreCase = true) }
            parent = found?.id ?: run {
                folders.insertFolder(newUuid(), parent, name, nextPosition(parent), nowEpochSeconds(), null, null)
                db.schemaQueries.lastInsertId().executeAsOne()
            }
        }
        parent
    }

    private fun Map<String, String>.withoutMeta(): Map<String, String> = filterKeys { !it.startsWith("#") }

    private companion object {
        const val DD_AS_DESC = "#ddDesc"
        val DATA_URI = Regex("^data:([a-zA-Z0-9.+/-]+);base64,([A-Za-z0-9+/=]+)$")
    }
}
