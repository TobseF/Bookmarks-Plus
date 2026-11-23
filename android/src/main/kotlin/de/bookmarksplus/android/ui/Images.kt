package de.bookmarksplus.android.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import de.bookmarksplus.android.BookmarkStore
import de.bookmarksplus.data.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Images (synchronized thumbnails and favicons from the database) with a small memory cache. */
object Images {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    /** Paths/hashes that could not be decoded (e.g. SVG icons). */
    private val failed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun cached(key: String): ImageBitmap? = cache.get(key)

    fun file(path: String, maxWidth: Int): ImageBitmap? {
        val key = "f:$path@$maxWidth"
        cache.get(key)?.let { return it }
        if (key in failed) return null
        val f = File(path)
        if (!f.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        return bmp?.asImageBitmap()?.also { cache.put(key, it) } ?: run { failed += key; null }
    }

    fun icon(store: BookmarkStore, hash: String): ImageBitmap? {
        val key = "i:$hash"
        cache.get(key)?.let { return it }
        if (key in failed) return null
        val bytes = store.iconBytes(hash)
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        return bmp?.asImageBitmap()?.also { cache.put(key, it) } ?: run { failed += key; null }
    }
}

/**
 * Image paths in display priority as on the desktop: chosen image, for GitHub repos first the GitHub card,
 * otherwise first the screenshot of the page, then the share image (og:image).
 */
fun imageCandidates(item: Item): List<String> =
    if (item.chosenThumbPath != null) listOfNotNull(item.chosenThumbPath, item.thumbnailPath, item.cardImagePath)
    else if (item.domain == "github.com") listOfNotNull(item.cardImagePath, item.thumbnailPath)
    else listOfNotNull(item.thumbnailPath, item.cardImagePath)

@Composable
fun rememberPreview(item: Item, maxWidth: Int = 720): ImageBitmap? {
    val candidates = imageCandidates(item)
    val key = candidates.joinToString("|") + "@$maxWidth"
    val img by produceState(candidates.firstNotNullOfOrNull { Images.cached("f:$it@$maxWidth") }, key) {
        if (value == null) value = withContext(Dispatchers.IO) { candidates.firstNotNullOfOrNull { Images.file(it, maxWidth) } }
    }
    return img
}

@Composable
fun rememberIcon(store: BookmarkStore, hash: String?): ImageBitmap? {
    if (hash == null) return null
    val img by produceState(Images.cached("i:$hash"), hash) {
        if (value == null) value = withContext(Dispatchers.IO) { Images.icon(store, hash) }
    }
    return img
}
