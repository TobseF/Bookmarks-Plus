package de.bookmarksplus.data

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Locally finds the folders that best match a bookmark by content (TF-IDF over folder path,
 * title and domains of the contained bookmarks). The AI only gets these candidates instead of all ~1,200 folders.
 */
class FolderMatcher(private val lib: Library) {

    /** Word counts per folder: own bookmarks + folder name (heavily weighted) + path. */
    private val profiles: Map<Long, Map<String, Int>>
    private val idf: Map<String, Double>
    private val norms = HashMap<Long, Double>()

    init {
        val p = HashMap<Long, HashMap<String, Int>>()
        for ((fid, _) in lib.folders) {
            if (fid == lib.rootId) continue
            val m = HashMap<String, Int>()
            lib.path(fid).forEachIndexed { i, f ->
                val w = if (f.id == fid) 6 else 2 + i / 2
                tokens(f.title).forEach { m.merge(it, w, Int::plus) }
            }
            lib.placementsByFolder[fid]?.forEach { pl -> lib.itemsById[pl.bookmarkId]?.let { itemTokens(it).forEach { t -> m.merge(t, 1, Int::plus) } } }
            p[fid] = m
        }
        profiles = p
        val df = HashMap<String, Int>()
        p.values.forEach { m -> m.keys.forEach { df.merge(it, 1, Int::plus) } }
        val n = p.size.coerceAtLeast(1).toDouble()
        idf = df.mapValues { (_, d) -> ln(1 + n / d) }
    }

    /** The [limit] best-matching folders, best first. */
    fun candidates(item: Item, limit: Int = 15): List<Long> {
        val q = HashMap<String, Int>()
        itemTokens(item).forEach { q.merge(it, 1, Int::plus) }
        if (q.isEmpty()) return emptyList()
        val own = lib.placementsByItem[item.id].orEmpty().map { it.folderId }.toSet()
        val qVec = q.mapValues { (t, c) -> c * (idf[t] ?: 0.0) }
        val qNorm = sqrt(qVec.values.sumOf { it * it }).takeIf { it > 0 } ?: return emptyList()
        val scores = ArrayList<Pair<Long, Double>>()
        for ((fid, prof) in profiles) {
            var dot = 0.0
            for ((t, w) in qVec) {
                var c = prof[t] ?: continue
                // subtract own contribution so that the current folder does not win merely because of itself
                if (fid in own) c -= q[t] ?: 0
                if (c > 0) dot += w * c * (idf[t] ?: 0.0)
            }
            if (dot <= 0) continue
            val norm = norms.getOrPut(fid) { sqrt(prof.entries.sumOf { (t, c) -> (c * (idf[t] ?: 0.0)).let { it * it } }) }
            // do not favor small folders too strongly
            val size = lib.placementsByFolder[fid]?.size ?: 0
            scores += fid to dot / (qNorm * norm) * (1 + ln(1.0 + size) / 8)
        }
        return scores.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    companion object {
        private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val STOP = setOf(
            "the", "and", "for", "with", "you", "your", "from", "how", "what", "this", "that", "are", "com", "www", "org", "net",
            "html", "http", "https", "index", "php", "der", "die", "das", "und", "mit", "für", "von", "ein", "eine", "den",
            "dem", "des", "ist", "auf", "im", "in", "zu", "zum", "zur", "bei", "wie", "was", "oder", "auch", "nicht",
            "home", "page", "seite", "startseite", "official", "site", "welcome", "github",
        )

        fun tokens(text: String?): List<String> =
            text.orEmpty().lowercase().split(SPLIT).filter { it.length >= 3 && it !in STOP && !it.all(Char::isDigit) }

        fun itemTokens(item: Item): List<String> = buildList {
            addAll(tokens(item.title))
            item.metaTitle?.takeIf { it != item.title }?.let { addAll(tokens(it)) }
            item.aiTitle?.let { addAll(tokens(it)) }
            item.domain?.let { d -> d.split('.').dropLast(1).filter { it.length >= 3 && it !in STOP }.forEach { add(it) }; add(d) }
            addAll(item.aiTags.flatMap { tokens(it) })
        }
    }
}
