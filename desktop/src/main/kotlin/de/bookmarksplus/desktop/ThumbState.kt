package de.bookmarksplus.desktop

import de.bookmarksplus.i18n.tr
import de.bookmarksplus.data.Shots
import de.bookmarksplus.enrich.MediaStore
import de.bookmarksplus.enrich.ShotStore
import de.bookmarksplus.enrich.apps.RateLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

// Thumbnail choice, own images and screenshot management (dialog "Choose thumbnail", detail view)

private suspend fun AppState.dbWrite(block: () -> Unit) = dbLock.withLock { db.transaction { block() } }

/** Set thumbnail: `website`, `card`, `custom`, `shot:<hash>` or null for automatic. */
fun AppState.setThumbChoice(id: Long, choice: String?) {
    scope.launch(Dispatchers.IO) {
        dbWrite { db.enrichQueries.setThumbChoice(choice, id) }
        dirty.set(true)
        reload()
    }
}

/** Own thumbnail (file or clipboard): stored shrunk and selected right away. */
fun AppState.setCustomThumb(id: Long, raw: ByteArray?) {
    if (raw == null) { notify(tr("thumb.noImage")); return }
    scope.launch(Dispatchers.IO) {
        val bytes = ImageInput.readable(raw)
        val b = db.enrichQueries.forEnrich(id).executeAsOneOrNull()
        val path = if (bytes != null && b != null) uploads.save(MediaStore.Kind.Custom, "${b.uuid}-${System.currentTimeMillis()}", bytes) else null
        if (path == null || b == null) { notify(tr("thumb.unreadable")); return@launch }
        dbWrite { db.enrichQueries.setCustomThumb(path, "custom", id) }
        if (b.thumb_custom_path != null && b.thumb_custom_path != path) uploads.delete(b.thumb_custom_path)
        dirty.set(true)
        reload()
        notify(tr("thumb.customApplied"))
    }
}

/** Store own image as an additional screenshot (source "Upload"). */
fun AppState.addScreenshot(id: Long, raw: ByteArray?) {
    if (raw == null) { notify(tr("thumb.noImage")); return }
    scope.launch(Dispatchers.IO) {
        val bytes = ImageInput.readable(raw)
        val b = db.enrichQueries.forEnrich(id).executeAsOneOrNull()
        if (bytes == null || b == null) { notify(tr("thumb.unreadable")); return@launch }
        val n = ShotStore.add(db, uploads, id, b.uuid, Shots.UPLOAD, listOf(bytes)) { w -> dbWrite { w() } }
        dirty.set(true)
        reload()
        notify(if (n > 0) tr("thumb.screenshotAdded") else tr("thumb.duplicate"))
    }
}

/** Hide or show a screenshot again (stays in the database but no longer counts). */
fun AppState.setShotHidden(id: Long, hash: String, hidden: Boolean) {
    scope.launch(Dispatchers.IO) {
        dbWrite {
            val cur = Shots.parse(db.enrichQueries.shotsOf(id).executeAsOneOrNull()?.shots_json)
            db.enrichQueries.setShots(Shots.encode(Shots.setHidden(cur, hash, hidden)), id)
        }
        dirty.set(true)
        reload()
    }
}

/** Specifically reload Flathub, AlternativeTo or the GitHub README card (for the thumbnail dialog). */
fun AppState.loadThumbSource(id: Long, source: String) {
    if (source in thumbLoading) return
    thumbLoading += source
    scope.launch(Dispatchers.IO) {
        val label = when (source) { "flathub" -> "Flathub"; "alternativeto" -> "AlternativeTo"; else -> "GitHub README" }
        val before = library.anyItem(id)?.shots?.size ?: 0
        val ok = try {
            runCatching { apps.local(id) }
            when (source) {
                "flathub" -> apps.flathub(id, force = true)
                "alternativeto" -> apps.alternativeTo(id, force = true)
                else -> enricher.rerenderGithubCard(id)
            }
        } catch (e: RateLimited) {
            notify(tr("thumb.rateLimited", label)); false
        } catch (e: Exception) {
            notify("$label: ${e.message ?: tr("api.error")}"); false
        }
        dirty.set(true)
        reload()
        thumbLoading -= source
        val added = (library.anyItem(id)?.shots?.size ?: 0) - before
        if (source == "readme" && ok) setThumbChoice(id, "card")
        notify(
            when {
                source == "readme" -> if (ok) tr("thumb.readmeCard") else tr("thumb.noReadmeImage")
                ok && added > 0 -> tr("thumb.newImages", label, added)
                ok -> tr("thumb.noNewImages", label)
                else -> tr("thumb.nothingFound", label)
            },
        )
    }
}
