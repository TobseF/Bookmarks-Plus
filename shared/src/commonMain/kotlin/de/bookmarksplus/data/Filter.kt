package de.bookmarksplus.data

import de.bookmarksplus.i18n.tr

enum class SortField {
    Manual, Added, Modified, Title, Domain, Stars, LastCommit;

    val label: String get() = tr("sort.${name.replaceFirstChar { it.lowercase() }}")
}

/** Time range relative to "now". `maxAgeDays` → younger than, `minAgeDays` → older than. */
enum class Period(val maxAgeDays: Int? = null, val minAgeDays: Int? = null) {
    Any,
    Week(maxAgeDays = 7),
    Month(maxAgeDays = 30),
    Year(maxAgeDays = 365),
    OlderYear(minAgeDays = 365),
    OlderFiveYears(minAgeDays = 5 * 365);

    val label: String get() = tr("period.${name.replaceFirstChar { it.lowercase() }}")

    fun matches(epochSeconds: Long?, now: Long): Boolean {
        if (this == Any) return true
        // Unknown date (ADD_DATE="0" for very old entries) counts as "old"
        if (epochSeconds == null || epochSeconds <= 0) return minAgeDays != null
        val age = (now - epochSeconds) / 86_400
        return (maxAgeDays == null || age <= maxAgeDays) && (minAgeDays == null || age > minAgeDays)
    }
}

@kotlinx.serialization.Serializable
data class Filter(
    val text: String = "",
    val folderId: Long? = null,
    val includeSubfolders: Boolean = true,
    val tagIds: Set<Long> = emptySet(),
    val domain: String? = null,
    val minStars: Long? = null,
    val added: Period = Period.Any,
    val updated: Period = Period.Any,
    val onlyRepos: Boolean = false,
    val onlyDead: Boolean = false,
    val onlySuggested: Boolean = false,
    val onlyRedirects: Boolean = false,
    val onlyDuplicates: Boolean = false,
    val onlyBlocked: Boolean = false,
    /** Only pages without a thumbnail (e.g. failed due to bot protection). */
    val onlyNoThumbnail: Boolean = false,
    /** Show trash instead of library. */
    val trash: Boolean = false,
    val sort: SortField = SortField.Added,
    val descending: Boolean = true,
) {
    val isNarrowing: Boolean
        get() = text.isNotBlank() || tagIds.isNotEmpty() || domain != null || minStars != null ||
            added != Period.Any || updated != Period.Any || onlyRepos || onlyDead || onlySuggested || onlyRedirects || onlyDuplicates || onlyBlocked || onlyNoThumbnail
}

/**
 * Search text with operators:
 * `#tag`, `domain:github.com`, `stars:>1000` / `stars:500`, `added:2024`, `is:repo`, `is:dead`, `-exclusion`,
 * `is:ai` / `is:noai` (AI-evaluated), `is:suggested` (open AI suggestions), `type:article`,
 * `is:redirect`, `is:duplicate`, `is:blocked`, `is:nothumb` (thumbnail missing),
 * Programs: `is:app`, `platform:pc|smartphone|browser|docker`, `os:windows|linux|mac|android`,
 * `has:flathub`, `has:alternativeto`, `has:winget|choco|scoop|brew|npm`, `license:gpl`.
 */
data class ParsedQuery(
    val terms: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val domain: String? = null,
    val minStars: Long? = null,
    val maxStars: Long? = null,
    val addedYear: Int? = null,
    val isRepo: Boolean = false,
    val isDead: Boolean = false,
    val isAi: Boolean? = null,
    val hasSuggestion: Boolean = false,
    val isRedirect: Boolean = false,
    val isDuplicate: Boolean = false,
    val isBlocked: Boolean = false,
    val noThumbnail: Boolean = false,
    val type: String? = null,
    val isApp: Boolean = false,
    val platforms: List<String> = emptyList(),
    val systems: List<String> = emptyList(),
    val has: List<String> = emptyList(),
    val license: String? = null,
) {
    companion object {
        fun parse(text: String): ParsedQuery {
            var q = ParsedQuery()
            val terms = ArrayList<String>()
            val excluded = ArrayList<String>()
            val tags = ArrayList<String>()
            for (raw in text.trim().split(Regex("\\s+"))) {
                if (raw.isEmpty()) continue
                val lower = raw.lowercase()
                when {
                    lower.startsWith("#") && lower.length > 1 -> tags += lower.substring(1)
                    lower.startsWith("domain:") -> q = q.copy(domain = lower.substringAfter(':').removePrefix("www.").ifEmpty { null })
                    lower.startsWith("stars:") -> {
                        val v = lower.substringAfter(':')
                        when {
                            v.startsWith(">=") -> q = q.copy(minStars = v.drop(2).toNumber())
                            v.startsWith(">") -> q = q.copy(minStars = v.drop(1).toNumber()?.plus(1))
                            v.startsWith("<=") -> q = q.copy(maxStars = v.drop(2).toNumber())
                            v.startsWith("<") -> q = q.copy(maxStars = v.drop(1).toNumber()?.minus(1))
                            else -> q = q.copy(minStars = v.toNumber())
                        }
                    }
                    lower.startsWith("added:") || lower.startsWith("created:") ->
                        q = q.copy(addedYear = lower.substringAfter(':').toIntOrNull())
                    lower == "is:repo" || lower == "is:github" -> q = q.copy(isRepo = true)
                    lower == "is:dead" || lower == "is:broken" -> q = q.copy(isDead = true)
                    lower == "is:redirect" || lower == "is:moved" -> q = q.copy(isRedirect = true)
                    lower == "is:duplicate" || lower == "is:dupe" -> q = q.copy(isDuplicate = true)
                    lower == "is:blocked" -> q = q.copy(isBlocked = true)
                    lower == "is:nothumb" || lower == "is:nothumbnail" -> q = q.copy(noThumbnail = true)
                    lower == "is:ai" -> q = q.copy(isAi = true)
                    lower == "is:noai" -> q = q.copy(isAi = false)
                    lower == "is:suggested" || lower == "is:suggestion" -> q = q.copy(hasSuggestion = true)
                    lower.startsWith("type:") -> q = q.copy(type = lower.substringAfter(':').ifEmpty { null })
                    lower == "is:app" || lower == "is:program" || lower == "is:programm" -> q = q.copy(isApp = true)
                    lower.startsWith("platform:") && lower.length > 9 -> q = q.copy(platforms = q.platforms + lower.substringAfter(':'))
                    (lower.startsWith("os:") || lower.startsWith("system:")) && !lower.endsWith(":") ->
                        q = q.copy(systems = q.systems + lower.substringAfter(':').let { if (it == "macos" || it == "osx") "mac" else it })
                    lower.startsWith("has:") && lower.length > 4 -> q = q.copy(has = q.has + lower.substringAfter(':'))
                    lower.startsWith("license:") && lower.length > 8 -> q = q.copy(license = lower.substringAfter(':'))
                    lower.startsWith("-") && lower.length > 1 -> excluded += lower.substring(1)
                    else -> terms += lower
                }
            }
            return q.copy(terms = terms, excluded = excluded, tags = tags)
        }

        /** `1k` / `2.5k` / `1200`. */
        private fun String.toNumber(): Long? {
            val s = trim()
            return when {
                s.endsWith("k") -> s.dropLast(1).toDoubleOrNull()?.times(1000)?.toLong()
                s.endsWith("m") -> s.dropLast(1).toDoubleOrNull()?.times(1_000_000)?.toLong()
                else -> s.toLongOrNull()
            }
        }
    }
}

object FilterEngine {

    /**
     * Applies the filter. `ftsHits` are the hits of the full-text index (README, summary …) for the
     * free-text terms; a bookmark matches if all terms occur in title/URL/description
     * OR it was found in the full-text index.
     */
    fun apply(lib: Library, f: Filter, now: Long, ftsHits: Set<Long> = emptySet()): List<Item> {
        val q = ParsedQuery.parse(f.text)
        val folderScope: Set<Long>? = f.folderId?.takeIf { !f.trash }?.let { if (f.includeSubfolders) lib.descendants(it) else setOf(it) }
        val inScope: Set<Long>? = folderScope?.let { scope ->
            val ids = HashSet<Long>()
            scope.forEach { fid -> lib.placementsByFolder[fid]?.forEach { ids += it.bookmarkId } }
            ids
        }
        val tagNames = q.tags.toSet()
        val tagIdsFromText = if (tagNames.isEmpty()) emptySet() else
            lib.tags.filter { it.name.lowercase() in tagNames || it.label.lowercase() in tagNames }.map { it.id }.toSet()
        val unknownTagInText = tagNames.size > tagIdsFromText.size
        val requiredTags = f.tagIds + tagIdsFromText
        val domain = q.domain ?: f.domain
        val minStars = listOfNotNull(q.minStars, f.minStars).maxOrNull()

        if (unknownTagInText) return emptyList()

        val result = (if (f.trash) lib.trash else lib.items).filter { item ->
            if (inScope != null && item.id !in inScope) return@filter false
            if (requiredTags.isNotEmpty() && !(lib.tagsOf[item.id] ?: emptySet()).containsAll(requiredTags)) return@filter false
            if (domain != null && item.domain != domain && item.domain?.endsWith(".$domain") != true) return@filter false
            if (minStars != null && (item.stars ?: -1) < minStars) return@filter false
            if (q.maxStars != null && (item.stars ?: Long.MAX_VALUE) > q.maxStars) return@filter false
            if ((f.onlyRepos || q.isRepo) && !item.isRepo) return@filter false
            if ((f.onlyDead || q.isDead) && !item.isDead) return@filter false
            if ((f.onlyRedirects || q.isRedirect) && item.health != LinkHealth.Redirect) return@filter false
            if ((f.onlyBlocked || q.isBlocked) && item.health != LinkHealth.Blocked && item.health != LinkHealth.Error) return@filter false
            if ((f.onlyNoThumbnail || q.noThumbnail) && !item.lacksThumbnail) return@filter false
            if ((f.onlyDuplicates || q.isDuplicate) && item.urlNorm !in lib.duplicateGroups) return@filter false
            if (q.isAi != null && (item.aiAt != null) != q.isAi) return@filter false
            if (q.type != null && item.contentType != q.type) return@filter false
            if (q.isApp && !item.app.isApp) return@filter false
            if (q.platforms.isNotEmpty() && !item.app.platforms.containsAll(q.platforms)) return@filter false
            if (q.systems.isNotEmpty() && !item.app.systems.containsAll(q.systems)) return@filter false
            if (q.license != null && item.app.license?.lowercase()?.contains(q.license) != true) return@filter false
            if (q.has.any { h -> !hasField(item.app, h) }) return@filter false
            if ((f.onlySuggested || q.hasSuggestion) && !lib.hasOpenSuggestion(item)) return@filter false
            if (!f.added.matches(item.addDate, now)) return@filter false
            if (!f.updated.matches(item.lastActivity, now)) return@filter false
            if (q.addedYear != null && item.addDate?.let { yearOf(it) } != q.addedYear) return@filter false
            if (q.excluded.any { it in item.haystack }) return@filter false
            if (q.terms.isNotEmpty() && !(q.terms.all { it in item.haystack } || item.id in ftsHits)) return@filter false
            true
        }
        // Duplicates are grouped together
        if (f.onlyDuplicates || q.isDuplicate) return sort(lib, result, f.sort, f.descending).sortedBy { it.urlNorm }
        return sort(lib, result, f.sort, f.descending)
    }

    fun sort(lib: Library, items: List<Item>, field: SortField, descending: Boolean): List<Item> {
        val cmp: Comparator<Item> = when (field) {
            SortField.Manual -> {
                // Order as in the tree: folder path, then position
                val order = HashMap<Long, Int>()
                var i = 0
                fun walk(fid: Long) {
                    val subs = lib.childrenOf(fid)
                    val ps = lib.placementsByFolder[fid].orEmpty()
                    (subs.map { it.position to { walk(it.id) } } + ps.map { p -> p.position to { order.putIfAbsent(p.bookmarkId, i++); Unit } })
                        .sortedBy { it.first }.forEach { it.second() }
                }
                lib.rootId?.let(::walk)
                return items.sortedBy { order[it.id] ?: Int.MAX_VALUE }.let { if (descending) it.asReversed() else it }
            }
            SortField.Added -> compareBy(nullsFirst()) { it.addDate }
            SortField.Modified -> compareBy(nullsFirst()) { it.lastActivity }
            SortField.Title -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
            SortField.Domain -> compareBy(nullsLast()) { it.domain }
            SortField.Stars -> compareBy(nullsFirst()) { it.stars }
            SortField.LastCommit -> compareBy(nullsFirst()) { it.lastCommit }
        }
        return items.sortedWith(if (descending) cmp.reversed() else cmp)
    }

    private fun hasField(a: AppInfo, field: String): Boolean = when (field) {
        "flathub" -> a.flathubUrl != null
        "alternativeto", "at" -> a.alternativetoUrl != null
        "winget" -> a.winget != null
        "choco", "chocolatey" -> a.choco != null
        "scoop" -> a.scoop != null
        "brew", "homebrew" -> a.brew != null
        "npm" -> a.npm != null
        "package", "pkg" -> a.packages.isNotEmpty()
        "license" -> a.license != null
        else -> true
    }

    /** Year (UTC) of a Unix timestamp, without a platform date API. */
    fun yearOf(epochSeconds: Long): Int {
        var days = epochSeconds / 86_400
        var year = 1970
        while (true) {
            val len = if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 366 else 365
            if (days < len) return year
            days -= len
            year++
        }
    }
}
