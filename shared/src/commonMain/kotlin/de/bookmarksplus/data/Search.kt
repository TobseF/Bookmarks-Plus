package de.bookmarksplus.data

import de.bookmarksplus.i18n.tr
import kotlinx.serialization.Serializable

/** What a free-text search returns (tri-state toggle next to the search bar). */
@Serializable
enum class SearchScope {
    All, Bookmarks, Folders;

    val includesBookmarks: Boolean get() = this != Folders
    val includesFolders: Boolean get() = this != Bookmarks
    val label: String get() = tr("scope.${name.lowercase()}")
    fun next(): SearchScope = entries[(ordinal + 1) % entries.size]
}

/** Folder found by the search; [parentPath] is the path above it ("A / B"). */
data class FolderHit(val folder: FolderInfo, val parentPath: String, val score: Double)

data class SearchResult(val items: List<Item>, val folders: List<FolderHit> = emptyList()) {
    companion object { val EMPTY = SearchResult(emptyList()) }
}

/** A searchable text with its lowercase form; [lang] = null means language-neutral. */
internal class Field(val text: String, val lang: ContentLang? = null, val weight: Double = 1.0) {
    val lower: String = text.lowercase()
}

/** Searchable texts of a bookmark, grouped by tier (computed once per snapshot). */
internal class ItemFields(val title: List<Field>, val other: List<Field>) {
    companion object {
        fun of(item: Item): ItemFields {
            val title = ArrayList<Field>()
            val other = ArrayList<Field>()
            fun add(list: MutableList<Field>, text: String?, lang: ContentLang? = null, weight: Double = 1.0) {
                if (text.isNullOrBlank()) return
                if (list.any { it.text == text && it.lang == lang }) return
                list += Field(text, lang, weight)
            }
            add(title, item.title)
            add(title, item.app.name)
            add(title, item.metaTitle, weight = 0.95)
            add(title, item.titleDe, ContentLang.DE)
            add(title, item.titleEn, ContentLang.EN)
            if (item.aiTitle != item.titleDe && item.aiTitle != item.titleEn) add(title, item.aiTitle)

            add(other, item.url, weight = 0.9)
            add(other, item.description)
            add(other, item.metaDescription)
            add(other, item.descDe, ContentLang.DE)
            add(other, item.descEn, ContentLang.EN)
            add(other, item.summaryDe, ContentLang.DE)
            add(other, item.summaryEn, ContentLang.EN)
            if (item.summary != item.summaryDe && item.summary != item.summaryEn) add(other, item.summary)
            if (item.aiTags.isNotEmpty()) add(other, item.aiTags.joinToString(" "), weight = 0.8)
            add(other, item.author, weight = 0.8)
            add(other, item.org, weight = 0.8)
            return ItemFields(title, other)
        }
    }
}

/** Search term in the typed spelling. Upper case in the term makes case-exact hits rank higher. */
class Term(val raw: String) {
    val lower: String = raw.lowercase()
    val cased: Boolean = raw.any { it.isUpperCase() }
}

/**
 * Relevance ranking for the free-text search.
 *
 * Every term gets the score of its best hit: `tier × match quality × language factor × field weight`.
 * Tiers: title 100 › folder path 60 › tags 50 › other fields 20 › only full text (README, page) 8.
 * Quality: whole field 1.0 › whole word 0.9 › word start 0.7 › inside a word 0.4; if the term contains
 * upper case letters, a hit with a different case loses 0.08. Texts in the other content language count 85 %.
 * The scores of all terms are added up (all terms must match), a phrase hit in the title gives a bonus,
 * short titles get a small bonus over long ones with the same kind of hit.
 *
 * The tiers are weighted rather than strictly ordered: a whole-word hit in the path (54) beats a hit
 * inside a title word (40), but a word start in the title (70) still beats everything below.
 */
object Relevance {
    const val TITLE = 100.0
    const val PATH = 60.0
    const val TAG = 50.0
    const val OTHER = 20.0
    const val FULLTEXT = 8.0

    const val FULL = 1.0
    const val WORD = 0.9
    const val PREFIX = 0.7
    const val INFIX = 0.4
    const val CASE_MISS = 0.08

    const val OTHER_LANG = 0.85
    const val ANCESTOR = 0.9
    const val PHRASE_BONUS = 20.0
    /** Title hits: up to this much extra the larger the share of the title the term covers ("Dune 3D" before a long title). */
    const val COVERAGE = 5.0

    /** Best match quality of [t] in [text] (0 = no hit). */
    fun quality(text: String, t: Term): Double = quality(text, text.lowercase(), t)

    internal fun quality(text: String, lower: String, t: Term): Double {
        val n = t.lower.length
        if (n == 0 || lower.length < n) return 0.0
        // lowercase() can change the length for a few characters → then only compare lower case
        val aligned = text.length == lower.length
        var best = 0.0
        var i = lower.indexOf(t.lower)
        while (i >= 0) {
            val e = i + n
            val start = i == 0 || !lower[i - 1].isLetterOrDigit() || (aligned && text[i - 1].isLowerCase() && text[i].isUpperCase())
            val end = e == lower.length || !lower[e].isLetterOrDigit() || (aligned && text[e - 1].isLowerCase() && text[e].isUpperCase())
            var q = when {
                start && end && i == 0 && e == lower.length -> FULL
                start && end -> WORD
                start -> PREFIX
                else -> INFIX
            }
            if (t.cased && aligned && !text.regionMatches(i, t.raw, 0, n)) q -= CASE_MISS
            if (q > best) best = q
            if (best >= WORD) break
            i = lower.indexOf(t.lower, i + 1)
        }
        return best
    }
}

/** Scores bookmarks and folders of one snapshot for one query. */
internal class Scorer(
    private val lib: Library,
    private val terms: List<Term>,
    private val excluded: List<String>,
    /** Full-text hits (FTS5) per lowercase term. */
    private val fts: Map<String, Set<Long>>,
) {
    private val lang = lib.contentLang
    private val phrase = if (terms.size > 1) terms.joinToString(" ") { it.lower } else null

    private fun best(fields: List<Field>, t: Term, tier: Double, current: Double): Double {
        var best = current
        val coverage = tier == Relevance.TITLE
        for (f in fields) {
            val max = tier * f.weight * (if (f.lang == null || f.lang == lang) 1.0 else Relevance.OTHER_LANG)
            if (max + (if (coverage) Relevance.COVERAGE else 0.0) <= best) continue
            val q = Relevance.quality(f.text, f.lower, t)
            if (q <= 0) continue
            val s = max * q + (if (coverage) Relevance.COVERAGE * t.lower.length / f.lower.length.coerceAtLeast(1) else 0.0)
            if (s > best) best = s
        }
        return best
    }

    /** 0 if not all terms match. */
    fun score(item: Item): Double {
        val fields = item.searchFields
        var total = 0.0
        for (t in terms) {
            val s = termScore(item, fields, t)
            if (s <= 0.0) return 0.0
            total += s
        }
        if (phrase != null && fields.title.any { it.lower.contains(phrase) }) total += Relevance.PHRASE_BONUS
        return total
    }

    private fun termScore(item: Item, fields: ItemFields, t: Term): Double {
        var best = best(fields.title, t, Relevance.TITLE, 0.0)
        val placements = lib.placementsByItem[item.id] ?: lib.trashPlacementsByItem[item.id].orEmpty()
        for (p in placements) p.title?.let { pt -> best = best(lib.placementTitleField(p.id, pt), t, Relevance.TITLE, best) }
        // lower tiers cannot beat a hit above their maximum
        if (best >= Relevance.PATH) return best
        for (p in placements) best = best(lib.folderChainFields(p.folderId), t, Relevance.PATH, best)
        if (best >= Relevance.TAG) return best
        lib.tagsOf[item.id]?.forEach { tid -> best = best(lib.tagFields(tid), t, Relevance.TAG, best) }
        if (best >= Relevance.OTHER) return best
        best = best(fields.other, t, Relevance.OTHER, best)
        if (best > 0.0) return best
        return if (fts[t.lower]?.contains(item.id) == true) Relevance.FULLTEXT * Relevance.PREFIX else 0.0
    }

    /** Folders (below [within], if given) whose name or path contains all terms, best first. */
    fun folders(within: Long?): List<FolderHit> {
        val candidates = if (within != null) lib.descendants(within) - within else lib.folders.keys
        val out = ArrayList<FolderHit>()
        for (fid in candidates) {
            if (fid == lib.rootId) continue
            val chain = lib.folderChainFields(fid)
            if (chain.isEmpty()) continue
            val own = chain.subList(0, 1)
            val ancestors = chain.subList(1, chain.size)
            if (excluded.isNotEmpty() && chain.any { f -> excluded.any { it in f.lower } }) continue
            var total = 0.0
            for (t in terms) {
                var s = best(own, t, Relevance.TITLE, 0.0)
                if (s < Relevance.PATH) s = best(ancestors, t, Relevance.PATH, s)
                if (s <= 0.0) { total = 0.0; break }
                total += s
            }
            if (total <= 0.0) continue
            if (phrase != null && own[0].lower.contains(phrase)) total += Relevance.PHRASE_BONUS
            val folder = lib.folders.getValue(fid)
            out += FolderHit(folder, ancestors.asReversed().joinToString(" / ") { it.text }, total)
        }
        return out.sortedWith(
            compareByDescending<FolderHit> { it.score }
                .thenByDescending { lib.recursiveCounts[it.folder.id] ?: 0 }
                .thenBy { it.parentPath.length },
        )
    }
}
