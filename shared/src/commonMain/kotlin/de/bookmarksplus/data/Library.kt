package de.bookmarksplus.data

import de.bookmarksplus.i18n.tr

import de.bookmarksplus.db.BookmarksDb
import de.bookmarksplus.netscape.NetscapeParser
import de.bookmarksplus.util.Urls

data class FolderInfo(val id: Long, val parentId: Long?, val title: String, val position: Long)

data class PlacementInfo(val id: Long, val bookmarkId: Long, val folderId: Long, val position: Long, val primary: Boolean, val title: String?)

/** `name` is the canonical name (English for AI tags), `label` the display in the chosen language. */
data class TagInfo(val id: Long, val name: String, val color: Long, val usage: Long, val label: String = name)

/** Language in which AI titles, descriptions, summaries and tags are displayed. */
enum class ContentLang(val code: String, val label: String) { DE("de", "Deutsch"), EN("en", "English") }

/** State of a link according to the last check. */
enum class LinkHealth {
    Unknown, Ok, Redirect, Dead, Unreachable, Blocked, Error;

    val label: String get() = tr("health.${name.replaceFirstChar { it.lowercase() }}")
}

data class Item(
    val id: Long,
    val url: String,
    val title: String,
    val aiTitle: String?,
    /** Already cleaned (entities decoded, `<br>` → line break). */
    val description: String?,
    val summary: String?,
    val domain: String?,
    val faviconHash: String?,
    val thumbnailPath: String?,
    val cardImagePath: String?,
    val cardImageUrl: String?,
    val repoUrl: String?,
    val websiteUrl: String?,
    val stars: Long?,
    val lastCommit: Long?,
    val lastRelease: String?,
    val lastReleaseDate: Long?,
    val author: String?,
    val org: String?,
    val contentType: String?,
    val linkStatus: Long?,
    val addDate: Long?,
    val lastModified: Long?,
    val lastVisited: Long?,
    val urlNorm: String,
    val metaTitle: String? = null,
    val metaDescription: String? = null,
    val finalUrl: String? = null,
    val hasReadme: Boolean = false,
    val scrapedAt: Long? = null,
    /** Tag names suggested by the AI (not yet assigned). */
    val aiTags: List<String> = emptyList(),
    /** Folder path suggested by the AI ("A / B / C"). */
    val aiFolder: String? = null,
    val aiAt: Long? = null,
    /** Set if the bookmark is in the trash. */
    val deletedAt: Long? = null,
    /** Folder path before deletion, if no placement remained. */
    val trashPath: String? = null,
    /** Error type for status 0: dns, connect, timeout, ssl, loop. */
    val linkError: String? = null,
    val statusCheckedAt: Long? = null,
    /** Excluded from "Check all links" ("Don't check anymore"). */
    val linkIgnore: Boolean = false,
    /** Uniform AI texts in both languages (independent of the page language). */
    val titleDe: String? = null,
    val titleEn: String? = null,
    val descDe: String? = null,
    val descEn: String? = null,
    val summaryDe: String? = null,
    val summaryEn: String? = null,
    /** AI description in the chosen display language. */
    val aiDescription: String? = null,
    /** All screenshots of an application (not of the website) including source and hide flag. */
    val shots: List<Shot> = emptyList(),
    /** Thumbnail choice: null = automatic, otherwise `website`, `card`, `custom` or `shot:<hash>`. */
    val thumbChoice: String? = null,
    val customThumbPath: String? = null,
    /** Download of the current version (e.g. SourceForge) including file name. */
    val downloadUrl: String? = null,
    val downloadLabel: String? = null,
    /** Programs and games: clean name, platforms, systems, license, package IDs, directory pages. */
    val app: AppInfo = AppInfo.EMPTY,
    /** Icon sources (hashes in `icon`): GitHub avatar, Flathub, AlternativeTo, favicon of the project website. */
    val avatarHash: String? = null,
    val flathubIconHash: String? = null,
    val alternativetoIconHash: String? = null,
    val websiteIconHash: String? = null,
) {
    /** AI description in the display language, otherwise that of the user/browser, otherwise that of the page. */
    val anyDescription: String? get() = aiDescription?.takeIf { it.isNotBlank() }
        ?: description?.takeIf { it.isNotBlank() } ?: metaDescription?.takeIf { it.isNotBlank() }

    /** Original description (user or page) – shown next to the uniform AI description. */
    val originalDescription: String? get() = description?.takeIf { it.isNotBlank() } ?: metaDescription?.takeIf { it.isNotBlank() }

    val displayTitle: String get() = aiTitle?.takeIf { it.isNotBlank() } ?: title.ifBlank { domain ?: url }
    /** Visible screenshots (paths); hidden ones are missing here. */
    val screenshots: List<String> get() = shots.filter { !it.hidden }.map { it.path }

    /** Path of the chosen thumbnail; null for "automatic" or if the source (no longer) exists. */
    val chosenThumbPath: String? get() = when {
        thumbChoice == null -> null
        thumbChoice == "website" -> thumbnailPath
        thumbChoice == "card" -> cardImagePath
        thumbChoice == "custom" -> customThumbPath
        thumbChoice.startsWith("shot:") -> shots.firstOrNull { it.hash == thumbChoice.removePrefix("shot:") && !it.hidden }?.path
        else -> null
    }

    val isRepo: Boolean get() = (repoUrl != null && Urls.githubRepo(repoUrl) != null) || domain == "github.com"

    /** The bookmark itself is a GitHub repo (then its favicon belongs to GitHub, not the project). */
    val isGithubRepo: Boolean get() = Urls.githubRepo(url) != null

    /**
     * Icon to display, in this order: Flathub → AlternativeTo → favicon of the (stored) website →
     * GitHub avatar. null if nothing matches; for GitHub projects the UI then draws the GitHub logo.
     */
    val iconHash: String? get() = flathubIconHash ?: alternativetoIconHash
        ?: (if (isGithubRepo) websiteIconHash else websiteIconHash ?: faviconHash) ?: avatarHash

    /** GitHub repo of the project: the bookmark itself or the stored repo. */
    val githubUrl: String? get() = if (isGithubRepo) url else repoUrl?.takeIf { Urls.githubRepo(it) != null }

    /** Official website: for GitHub bookmarks the stored one, otherwise the bookmark's URL. */
    val siteUrl: String? get() = if (isGithubRepo) websiteUrl else url

    /** Domain to display: preferably that of the project website, for GitHub projects without a website github.com. */
    val displayDomain: String? get() = (if (isGithubRepo) websiteUrl?.let(Urls::domain) else null) ?: domain

    /** No icon available but a GitHub project → official GitHub logo as a fallback. */
    val iconIsGithubFallback: Boolean get() = iconHash == null && isRepo
    /** Project page on SourceForge (reviews instead of stars, "Last update" instead of commit). */
    val isSourceforge: Boolean get() = Urls.anySourceforgeProject(url) != null || repoUrl?.let(Urls::sourceforgeProject) != null
    val health: LinkHealth get() {
        if (linkError == "parked") return LinkHealth.Dead
        val s = linkStatus ?: return LinkHealth.Unknown
        return when {
            s == 0L -> if (linkError == "dns") LinkHealth.Dead else LinkHealth.Unreachable
            s in 300..399 -> LinkHealth.Redirect
            s in 200..299 -> if (Urls.isMeaningfulRedirect(url, finalUrl)) LinkHealth.Redirect else LinkHealth.Ok
            s == 404L || s == 410L || s == 451L -> LinkHealth.Dead
            s == 401L || s == 403L || s == 429L || s == 999L -> LinkHealth.Blocked
            s >= 500 -> LinkHealth.Error
            else -> LinkHealth.Dead
        }
    }

    /** Dead or permanently unreachable (403/429 due to bot protection does not count). */
    val isDead: Boolean get() = health == LinkHealth.Dead || health == LinkHealth.Unreachable
    /** Web address without thumbnail (screenshot missing or failed). */
    val lacksThumbnail: Boolean get() = thumbnailPath == null && (url.startsWith("http://", true) || url.startsWith("https://", true))

    /** Redirect to the home page – the actual page probably no longer exists. */
    val redirectsHome: Boolean get() = health == LinkHealth.Redirect && Urls.redirectsToHome(url, finalUrl)

    /** Last activity: most recent of change, last commit and release. */
    val lastActivity: Long? get() = listOfNotNull(lastModified, lastCommit, lastReleaseDate).maxOrNull()

    /** Texts for the relevance ranking, grouped by tier. */
    internal val searchFields: ItemFields by lazy { ItemFields.of(this) }

    internal val haystack: String by lazy {
        buildString {
            append(title.lowercase()).append('\n')
            aiTitle?.let { append(it.lowercase()).append('\n') }
            append(url.lowercase()).append('\n')
            description?.let { append(it.lowercase()).append('\n') }
            metaTitle?.let { append(it.lowercase()).append('\n') }
            metaDescription?.let { append(it.lowercase()).append('\n') }
            summary?.let { append(it.lowercase()).append('\n') }
            listOfNotNull(titleDe, titleEn, descDe, descEn, summaryDe, summaryEn).forEach { append(it.lowercase()).append('\n') }
            app.name?.let { append(it.lowercase()).append(' ') }
        }
    }
}

/** Immutable snapshot of all data the views need. */
class Library(
    val rootId: Long?,
    folders: List<FolderInfo>,
    val items: List<Item>,
    val placements: List<PlacementInfo>,
    val tags: List<TagInfo>,
    val tagsOf: Map<Long, Set<Long>>,
    /** Bookmarks in the trash (newest first). */
    val trash: List<Item> = emptyList(),
    /** Placements of the bookmarks in the trash (become active again on restore). */
    trashPlacements: List<PlacementInfo> = emptyList(),
) {
    val trashById: Map<Long, Item> = trash.associateBy { it.id }

    /** Translated display for tag names (also for suggestions that are not tags yet). */
    var tagLabels: Map<String, String> = emptyMap()
        internal set

    fun tagLabel(name: String): String = tagLabels[name.lowercase()] ?: name

    /** Language the snapshot was loaded in; the search prefers texts in this language. */
    var contentLang: ContentLang = ContentLang.DE
        internal set
    val trashPlacementsByItem: Map<Long, List<PlacementInfo>> = trashPlacements.groupBy { it.bookmarkId }
    private val trashPlacementList = trashPlacements

    /** Active bookmark or one from the trash. */
    fun anyItem(id: Long): Item? = itemsById[id] ?: trashById[id]

    val folders: Map<Long, FolderInfo> = folders.associateBy { it.id }
    val subFolders: Map<Long?, List<FolderInfo>> = folders.groupBy { it.parentId }.mapValues { (_, v) -> v.sortedBy { it.position } }
    val itemsById: Map<Long, Item> = items.associateBy { it.id }
    val placementsByFolder: Map<Long, List<PlacementInfo>> = placements.groupBy { it.folderId }
    val placementsByItem: Map<Long, List<PlacementInfo>> = placements.groupBy { it.bookmarkId }
    val tagsById: Map<Long, TagInfo> = tags.associateBy { it.id }

    fun childrenOf(folderId: Long?): List<FolderInfo> = subFolders[folderId].orEmpty()

    /** Folder including all subfolders. */
    fun descendants(folderId: Long): Set<Long> {
        val out = HashSet<Long>()
        val stack = ArrayDeque<Long>().apply { add(folderId) }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (out.add(id)) childrenOf(id).forEach { stack.add(it.id) }
        }
        return out
    }

    fun path(folderId: Long): List<FolderInfo> {
        val out = ArrayList<FolderInfo>()
        var cur = folders[folderId]
        while (cur != null && cur.id != rootId) {
            out += cur
            cur = cur.parentId?.let { folders[it] }
        }
        return out.asReversed()
    }

    fun pathString(folderId: Long): String = path(folderId).joinToString(" / ") { it.title }

    /** Suggested tags that are not yet assigned to the bookmark. */
    fun openTagSuggestions(item: Item): List<String> {
        if (item.aiTags.isEmpty()) return emptyList()
        val assigned = tagsOf[item.id].orEmpty().mapNotNullTo(HashSet()) { tagsById[it]?.name?.lowercase() }
        return item.aiTags.filter { it.lowercase() !in assigned }
    }

    /** Suggested folder, if the bookmark is not yet there. */
    fun openFolderSuggestion(item: Item): String? {
        val path = item.aiFolder?.takeIf { it.isNotBlank() } ?: return null
        val fid = folderByPath(path) ?: return path
        return path.takeIf { placementsByItem[item.id].orEmpty().none { it.folderId == fid } }
    }

    /** Folder name (weight 1) followed by its ancestors (weight [Relevance.ANCESTOR]), for the search. */
    private val folderChains: Map<Long, List<Field>> by lazy {
        this.folders.keys.filter { it != rootId }.associateWith { fid ->
            path(fid).asReversed().mapIndexed { i, f -> Field(f.title, weight = if (i == 0) 1.0 else Relevance.ANCESTOR) }
        }
    }

    internal fun folderChainFields(folderId: Long): List<Field> = folderChains[folderId].orEmpty()

    /** Tag label in the display language and the canonical name. */
    private val tagFieldMap: Map<Long, List<Field>> by lazy {
        tags.associate { t ->
            t.id to buildList {
                add(Field(t.label))
                if (!t.name.equals(t.label, ignoreCase = true)) add(Field(t.name, weight = 0.9))
            }
        }
    }

    internal fun tagFields(tagId: Long): List<Field> = tagFieldMap[tagId].orEmpty()

    /** Own titles of placements (a bookmark can be named differently per folder). */
    private val placementTitles: Map<Long, List<Field>> by lazy {
        (placements + trashPlacementList).filter { !it.title.isNullOrBlank() }.associate { it.id to listOf(Field(it.title!!)) }
    }

    internal fun placementTitleField(placementId: Long, title: String): List<Field> = placementTitles[placementId] ?: listOf(Field(title))

    /** Groups of the same normalized URL (active bookmarks only), oldest first. */
    val duplicateGroups: Map<String, List<Item>> by lazy {
        items.groupBy { it.urlNorm }.filterValues { it.size > 1 }
            .mapValues { (_, v) -> v.sortedWith(compareBy<Item> { it.addDate ?: Long.MAX_VALUE }.thenBy { it.id }) }
    }

    fun duplicatesOf(item: Item): List<Item> = duplicateGroups[item.urlNorm]?.filter { it.id != item.id }.orEmpty()

    /**
     * Which duplicate to keep? The one with the most data (tags, enriched, AI, folders), on a tie the oldest.
     */
    fun bestOf(group: List<Item>): Item = group.maxWith(
        compareBy<Item> { (tagsOf[it.id]?.size ?: 0) * 4 + (if (it.scrapedAt != null) 3 else 0) + (if (it.summary != null) 2 else 0) + (placementsByItem[it.id]?.size ?: 0) }
            .thenByDescending { it.addDate ?: Long.MAX_VALUE }
            .thenByDescending { it.id },
    )

    fun hasOpenSuggestion(item: Item): Boolean = openTagSuggestions(item).isNotEmpty() || openFolderSuggestion(item) != null

    /** Folder ID for a path like "A / B" (case-insensitive), if present. */
    fun folderByPath(path: String): Long? = pathIndex[normPath(path)]

    private val pathIndex: Map<String, Long> by lazy {
        this.folders.keys.filter { it != rootId }.associateBy { normPath(pathString(it)) }
    }

    fun primaryFolder(itemId: Long): Long? =
        placementsByItem[itemId]?.let { ps -> (ps.firstOrNull { it.primary } ?: ps.firstOrNull())?.folderId }

    /** Number of bookmarks per folder incl. subfolders. */
    val recursiveCounts: Map<Long, Int> by lazy {
        val counts = HashMap<Long, Int>()
        fun count(id: Long): Int {
            val n = placementsByFolder[id].orEmpty().size + childrenOf(id).sumOf { count(it.id) }
            counts[id] = n
            return n
        }
        rootId?.let(::count)
        counts
    }

    val domains: List<Pair<String, Int>> by lazy {
        items.mapNotNull { it.domain }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key to it.value }
    }

    companion object {
        fun normPath(path: String): String =
            path.split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.joinToString("/")

        fun encodeTagList(tags: List<String>): String = kotlinx.serialization.json.Json.encodeToString(tags)

        fun parseTagList(json: String?): List<String> =
            if (json.isNullOrBlank()) emptyList()
            else runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(json) }.getOrDefault(emptyList())

        val EMPTY = Library(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())

        fun load(db: BookmarksDb, lang: ContentLang = ContentLang.DE): Library {
            val i18n = db.aiQueries.allTagI18n().executeAsList().associate { it.name.lowercase() to (it.de to it.en) }
            val root = db.folderQueries.root().executeAsOneOrNull()
            val folders = db.folderQueries.all().executeAsList().map { FolderInfo(it.id, it.parent_id, it.title, it.position) }
            val all = db.bookmarkQueries.viewRows().executeAsList().map {
                Item(
                    id = it.id, url = it.url, title = it.title,
                    aiTitle = (if (lang == ContentLang.EN) it.title_en ?: it.title_de else it.title_de ?: it.title_en) ?: it.ai_title,
                    description = it.description?.let(Text::cleanDescription),
                    summary = (if (lang == ContentLang.EN) it.summary_en ?: it.summary_de else it.summary_de ?: it.summary_en) ?: it.summary,
                    aiDescription = if (lang == ContentLang.EN) it.desc_en ?: it.desc_de else it.desc_de ?: it.desc_en,
                    domain = it.domain, faviconHash = it.favicon_hash, thumbnailPath = it.thumbnail_path,
                    cardImagePath = it.card_image_path, cardImageUrl = it.card_image_url, repoUrl = it.repo_url,
                    websiteUrl = it.website_url, stars = it.gh_stars, lastCommit = it.gh_last_commit,
                    lastRelease = it.gh_last_release, lastReleaseDate = it.gh_last_release_date, author = it.gh_author,
                    org = it.gh_org, contentType = it.content_type, linkStatus = it.link_status, addDate = it.add_date,
                    lastModified = it.last_modified, lastVisited = it.last_visited, urlNorm = it.url_norm,
                    metaTitle = it.meta_title, metaDescription = it.meta_description, finalUrl = it.final_url,
                    hasReadme = it.has_readme == true, scrapedAt = it.scraped_at,
                    aiTags = parseTagList(it.ai_tags), aiFolder = it.ai_folder, aiAt = it.ai_at,
                    deletedAt = it.deleted_at, trashPath = it.trash_path,
                    linkError = it.link_error, statusCheckedAt = it.status_checked_at, linkIgnore = it.link_ignore != 0L,
                    titleDe = it.title_de, titleEn = it.title_en, descDe = it.desc_de, descEn = it.desc_en,
                    summaryDe = it.summary_de, summaryEn = it.summary_en,
                    shots = Shots.parse(it.shots_json), thumbChoice = it.thumb_choice, customThumbPath = it.thumb_custom_path,
                    downloadUrl = it.download_url, downloadLabel = it.download_label,
                    app = AppInfo(
                        name = it.app_name, nameSource = it.app_name_source,
                        platforms = AppInfo.split(it.app_platforms), systems = AppInfo.split(it.app_systems),
                        license = it.app_license,
                        winget = it.pkg_winget, choco = it.pkg_choco, brew = it.pkg_brew, npm = it.pkg_npm, scoop = it.pkg_scoop,
                        flathubUrl = it.flathub_url, alternativetoUrl = it.alternativeto_url,
                        flathubInstalls = it.flathub_installs, flathubDownloadSize = it.flathub_download_size,
                        flathubInstalledSize = it.flathub_installed_size,
                        repoUrl = it.repo_url,
                    ),
                    avatarHash = it.avatar_hash, flathubIconHash = it.flathub_icon_hash,
                    alternativetoIconHash = it.alternativeto_icon_hash, websiteIconHash = it.website_icon_hash,
                )
            }
            val (trash, items) = all.partition { it.deletedAt != null }
            val trashIds = trash.mapTo(HashSet()) { it.id }
            val (trashPlacements, placements) = db.bookmarkQueries.allPlacements().executeAsList().map {
                PlacementInfo(it.id, it.bookmark_id, it.folder_id, it.position, it.is_primary == 1L, it.title)
            }.partition { it.bookmarkId in trashIds }
            fun label(name: String) = i18n[name.lowercase()]?.let { (de, en) -> if (lang == ContentLang.EN) en ?: de else de ?: en } ?: name
            val tags = db.tagQueries.allTags().executeAsList().map { TagInfo(it.id, it.name, it.color, it.usage, label(it.name)) }
            val tagsOf = db.tagQueries.allBookmarkTags().executeAsList()
                .groupBy({ it.bookmark_id }, { it.tag_id }).mapValues { it.value.toSet() }
            return Library(root?.id, folders, items, placements, tags, tagsOf, trash.sortedByDescending { it.deletedAt }, trashPlacements)
                .also { lib -> lib.contentLang = lang }
                .also { lib -> lib.tagLabels = i18n.mapValues { (k, v) -> (if (lang == ContentLang.EN) v.second ?: v.first else v.first ?: v.second) ?: k } }
        }
    }
}

object Text {
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]{1,200}>")

    /** Inverse of [cleanDescription] for user-edited descriptions (Netscape-compatible). */
    fun encodeDescription(text: String): String =
        text.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            .replace("\r\n", "\n").replace("\n", "<br>")

    fun cleanDescription(raw: String): String =
        NetscapeParser.decodeEntities(raw.replace(BR, "\n").replace(TAG, "")).trim()
}
