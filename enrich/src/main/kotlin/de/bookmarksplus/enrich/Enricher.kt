package de.bookmarksplus.enrich

import app.cash.sqldelight.db.SqlDriver
import de.bookmarksplus.data.SearchIndex
import de.bookmarksplus.db.BookmarksDb
import de.bookmarksplus.util.Urls
import de.bookmarksplus.util.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class EnrichSettings(
    val githubToken: String? = null,
    val thumbnails: Boolean = true,
    val browserWorkers: Int = 2,
    /** Load pages for the screenshot with prefers-color-scheme: dark. */
    val darkScreenshots: Boolean = true,
    /** On bot check use the real browser with its own profile. */
    val realBrowser: Boolean = true,
    /** Show its window so that a check can be confirmed manually. */
    val visibleBrowser: Boolean = false,
    val httpWorkers: Int = 6,
    /** README thumbnail: search in this many lines at the beginning … */
    val readmeImageLines: Int = 60,
    /** … and only take images from this height (px); 0 = do not use README images. */
    val readmeImageMinHeight: Int = 200,
)

data class EnrichProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val errors: Int = 0,
    val current: String? = null,
    val githubPausedUntil: Long? = null,
    val lastError: String? = null,
)

/**
 * Enriches bookmarks: HTTP status, page metadata, share image, favicon, GitHub data incl. README,
 * thumbnail of the (project) website. Runs in the background with limited parallelism and at most one
 * simultaneous request per domain.
 */
class Enricher(
    private val driver: SqlDriver,
    private val db: BookmarksDb,
    private val mediaRoot: File,
    private val settings: () -> EnrichSettings,
    private val onChanged: (Long) -> Unit = {},
    /** Shared write lock with other background services (AI). */
    private val dbLock: Mutex = Mutex(),
    /** After scraping: app facts without network (URL, package IDs from README, name) – see AppService.local. */
    private val afterScrape: suspend (Long) -> Unit = {},
) : Closeable {

    private val http = Http()
    private val github = GitHubClient(http) { settings().githubToken }
    private val sourceforge = SourceForgeClient(http)
    private val media = MediaStore(mediaRoot)
    private val index = SearchIndex(driver)
    private val q get() = db.enrichQueries
    private val domainLocks = ConcurrentHashMap<String, Semaphore>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** Pause switch of the running batch. */
    val pause = PauseGate()
    private var shooter: Screenshotter? = null
    private var shooterWorkers = 0
    @Volatile private var githubPausedUntil = 0L

    init {
        index.ensure()
    }

    private val _progress = MutableStateFlow(EnrichProgress())
    val progress: StateFlow<EnrichProgress> = _progress

    fun pendingCount(): Long = q.countPending().executeAsOne()

    /** Starts enrichment for [ids] or – if none are given – for all bookmarks not yet scraped. */
    fun start(ids: List<Long>? = null, force: Boolean = false) {
        if (job?.isActive == true) return
        val targets = ids ?: (q.pendingIds().executeAsList() + q.pendingGithubIds().executeAsList() + q.pendingSourceforgeIds().executeAsList()).distinct()
        if (targets.isEmpty()) return
        pause.resume()
        _progress.value = EnrichProgress(running = true, total = targets.size)
        job = scope.launch {
            val channel = Channel<Long>(Channel.UNLIMITED)
            targets.forEach { channel.trySend(it) }
            channel.close()
            val done = AtomicInteger()
            val errors = AtomicInteger()
            coroutineScope {
                repeat(settings().httpWorkers.coerceIn(1, 16)) {
                    launch {
                        for (id in channel) {
                            if (!isActive) break
                            pause.await()
                            val ok = withTimeoutOrNull(120_000) { runCatching { enrich(id, force) }.onFailure { e -> _progress.update { it.copy(lastError = e.message) } }.isSuccess } ?: false
                            if (!ok) errors.incrementAndGet()
                            val d = done.incrementAndGet()
                            _progress.update { it.copy(done = d, errors = errors.get()) }
                        }
                    }
                }
            }
            _progress.update { it.copy(running = false, current = null) }
        }
    }

    fun stop() {
        val j = job ?: return
        scope.launch {
            j.cancelAndJoin()
            _progress.update { it.copy(running = false, current = null) }
        }
    }

    /** Enriches a single bookmark. `force` also reloads existing images. */
    suspend fun enrich(id: Long, force: Boolean = false) {
        val b = q.forEnrich(id).executeAsOneOrNull() ?: return
        val url = b.url.trim()
        val now = System.currentTimeMillis() / 1000
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            write { q.markScraped(now, id) }
            return
        }
        _progress.update { it.copy(current = Urls.domain(url) ?: url) }
        sourceforgeProjectOf(b)?.let { project ->
            enrichSourceforge(b, project, now)
            return
        }
        val ghRepo = Urls.githubRepo(url)
        val host = Urls.domain(url) ?: "?"

        // 1) fetch page (at most one request per domain at a time)
        val page = domainLocks.getOrPut(host) { Semaphore(1) }.withPermit { http.get(url) }
        val meta = if (page.ok && page.isHtml) page.text()?.let { runCatching { PageScraper.parse(it, page.finalUrl) }.getOrNull() } else null
        write {
            q.setFetchResult(page.status.toLong(), now, page.finalUrl.takeIf { it != url && page.status != 0 }, id)
            if (meta != null) q.setMeta(meta.title, meta.description, if (ghRepo == null) meta.imageUrl else null, meta.contentType, id)
            // page text for AI and full-text search (for repos the README is used)
            if (meta?.text != null && ghRepo == null) db.aiQueries.setPageText(meta.text, id)
        }

        // 2) favicon, if none came with the import
        if (b.favicon_hash == null && meta != null) fetchFavicon(id, meta.iconUrls)

        // 3) GitHub: own repo bookmark or repo linked on the project page
        // Flathub pages link the package manifest (github.com/flathub/…), not the project
        val repoUrl = ghRepo?.let { (o, r) -> "https://github.com/$o/$r" } ?: b.repo_url?.takeIf { Urls.githubRepo(it) != null }
            ?: meta?.repoUrl?.takeUnless { Urls.githubRepo(it)?.first.equals("flathub", ignoreCase = true) }
        var website: String? = b.website_url
        if (repoUrl != null) {
            write { q.setRepoUrlIfMissing(repoUrl, id) }
            val (owner, name) = Urls.githubRepo(repoUrl)!!
            // avatar of the user/organization (no API call, hence also during the API pause)
            if (force || b.avatar_hash == null) fetchAvatar(owner)?.let { h -> write { q.setAvatar(h, id) } }
            if (now >= githubPausedUntil) {
                try {
                    val info = github.fetch(owner, name)
                    if (info != null) {
                        write {
                            q.setGithub(
                                info.stars, info.lastCommit, info.latestRelease, info.latestReleaseDate,
                                info.author, info.organization, info.readme, now, info.description, id,
                            )
                            if (ghRepo != null && info.homepage != null) q.setWebsiteUrlIfMissing(normalizeHomepage(info.homepage), id)
                            info.license?.let { db.appQueries.setLicenseIfMissing(it, id) }
                        }
                        // adopt GitHub topics as tags (only for repo bookmarks)
                        if (ghRepo != null && info.topics.isNotEmpty()) write {
                            val tagRepo = de.bookmarksplus.data.TagRepository(db)
                            info.topics.forEach { t ->
                                tagRepo.add(listOf(id), tagRepo.getOrCreate(t, de.bookmarksplus.data.TagRepository.colorFor(t)))
                            }
                        }
                        if (ghRepo != null) website = website ?: info.homepage?.let(::normalizeHomepage)
                        // card only for repo bookmarks – for project pages their own share image remains.
                        // Prefers own card with the thumbnail from the README, otherwise GitHub's social card.
                        if (ghRepo != null && (force || b.card_image_path == null)) {
                            val own = renderReadmeCard(
                                b.uuid, info.owner, info.name, info.name, info.description, info.topics, info.stars, info.readme,
                            )
                            if (own != null) write { q.setCardImage(own.first, own.second, id) }
                            else github.downloadCard(info)?.let { bytes ->
                                media.save(MediaStore.Kind.Card, b.uuid, bytes)?.let { path ->
                                    write { q.setCardImage(path, info.cardUrl, id) }
                                }
                            }
                        }
                    }
                } catch (e: GitHubRateLimited) {
                    githubPausedUntil = e.resetAt
                    _progress.update { it.copy(githubPausedUntil = e.resetAt) }
                }
            }
        }

        // favicon of a repo's project website (the favicon of github.com is not suitable for that)
        if (ghRepo != null && website != null && (force || b.website_icon_hash == null)) {
            fetchWebsiteIcon(website)?.let { h -> write { q.setWebsiteIcon(h, id) } }
        }

        // 4) share image (og:image) of normal pages
        if (ghRepo == null && meta?.imageUrl != null && (force || b.card_image_path == null)) {
            val img = http.get(meta.imageUrl, maxBytes = 6 shl 20, accept = Http.ACCEPT_IMAGE)
            if (img.ok && img.body != null) media.save(MediaStore.Kind.Card, b.uuid, img.body)?.let { path ->
                write { q.setCardImage(path, meta.imageUrl, id) }
            }
        }

        // 5) thumbnail: for repos from the project website, otherwise from the page itself
        val shotTarget = if (ghRepo != null) website else url
        if (settings().thumbnails && shotTarget != null && (force || b.thumbnail_path == null) && (ghRepo != null || page.status != 0)) {
            screenshotter().capture(shotTarget)?.let { bytes ->
                media.save(MediaStore.Kind.Thumbnail, b.uuid, bytes)?.let { path -> write { q.setThumbnail(path, id) } }
            }
        }
        write {
            q.markScraped(now, id)
            index.update(id)
        }
        runCatching { afterScrape(id) }
        onChanged(id)
    }

    /**
     * SourceForge project of a bookmark: direct project URL, subdomain scheme (`egoboo.sourceforge.net`)
     * or already stored project URL in the repo field. For subdomains the project page
     * (`sourceforge.net/projects/<name>/`) is reconstructed and – if still empty – stored in the repo field (`repo_url`).
     */
    private suspend fun sourceforgeProjectOf(b: de.bookmarksplus.db.ForEnrich): String? {
        val url = b.url.trim()
        Urls.sourceforgeProject(url)?.let { return it }
        val project = Urls.sourceforgeSubdomainProject(url) ?: b.repo_url?.let(Urls::sourceforgeProject) ?: return null
        if (b.repo_url.isNullOrBlank()) write { q.setRepoUrl(Urls.sourceforgeProjectUrl(project), b.id) }
        return project
    }

    /**
     * SourceForge project: name, description ("Summary"), authors, categories as tags, reviews as stars,
     * "Last Update", project icon as favicon, up to three "Project Samples" as app screenshots, download link.
     * The thumbnail is rendered from the first screenshot instead of being captured from the website.
     */
    private suspend fun enrichSourceforge(b: de.bookmarksplus.db.ForEnrich, project: String, now: Long) {
        val id = b.id
        val lock = domainLocks.getOrPut("sourceforge.net") { Semaphore(1) }
        val page = lock.withPermit { sourceforge.page(project) }
        val info = page.text()?.takeIf { page.ok }?.let { SourceForgeParser.parse(it, page.finalUrl) }
        if (info == null) {
            write {
                q.setFetchResult(page.status.toLong(), now, null, id)
                // do not retry deleted projects on every run, but do retry temporary errors
                if (page.status == 404 || page.status == 410) q.markEnriched(now, id)
                q.markScraped(now, id)
            }
            onChanged(id)
            return
        }
        val release = lock.withPermit { sourceforge.release(info.project) }
        val authors = info.authors.take(3).joinToString(", ").ifEmpty { null }
        write {
            q.setFetchResult(200, now, page.finalUrl.takeIf { Urls.sourceforgeProject(it) != project }, id)
            q.setSourceforge(
                reviews = info.reviews ?: 0, lastUpdate = info.lastUpdate ?: release?.date,
                release = release?.version, releaseDate = release?.date, author = authors, now = now,
                name = info.name, summary = info.summaryText,
                downloadUrl = release?.downloadUrl ?: info.downloadUrl ?: "https://sourceforge.net/projects/${info.project}/files/latest/download",
                downloadLabel = release?.file ?: info.downloadFile,
                homepage = info.homepage, id = id,
            )
            val text = listOfNotNull(info.tagline, info.description, info.features.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- $it" })
                .joinToString("\n\n")
            if (text.isNotBlank()) db.aiQueries.setPageText(text, id)
            // directory categories as tags ("Open Source Software › Games › Side-Scrolling" → Games, Side-Scrolling)
            if (info.categories.isNotEmpty()) {
                val tagRepo = de.bookmarksplus.data.TagRepository(db)
                info.categories.forEach { c -> tagRepo.add(listOf(id), tagRepo.getOrCreate(c, de.bookmarksplus.data.TagRepository.colorFor(c))) }
            }
        }

        // project icon instead of SourceForge favicon
        val iconBytes = info.iconUrl?.let { sourceforge.image(it, maxBytes = 1 shl 20) }
        iconBytes?.let { bytes ->
            val mime = ImageSniffer.mime(bytes)
            if (mime != null && mime != "image/svg+xml" && mime != "image/avif") {
                val hash = sha256Hex(bytes)
                write {
                    db.bookmarkQueries.insertIcon(hash, mime, bytes)
                    q.setFavicon(hash, id)
                }
            }
        }

        // Project Samples → up to three app screenshots
        val shots = info.screenshots.mapNotNull { u -> sourceforge.image(u) }
        if (shots.isNotEmpty()) {
            ShotStore.add(db, media, id, b.uuid, de.bookmarksplus.data.Shots.SOURCEFORGE, shots) { block -> write { block() } }
        }

        // render thumbnail from screenshot, icon and texts
        val card = AppCardRenderer.render(
            AppCardRenderer.Card(
                name = info.name ?: b.url, tagline = info.tagline ?: info.description,
                categories = info.categories, screenshot = shots.firstOrNull(), icon = iconBytes,
            ),
        )
        media.save(MediaStore.Kind.Thumbnail, b.uuid, card)?.let { path -> write { q.setThumbnail(path, id) } }
        write {
            q.markScraped(now, id)
            index.update(id)
        }
        runCatching { afterScrape(id) }
        onChanged(id)
    }

    /**
     * Recreates only the thumbnail (without reloading page, metadata or GitHub).
     * The new file name contains a timestamp so that image caches of the app replace the image immediately.
     */
    suspend fun refreshThumbnail(id: Long): Boolean {
        val b = q.forEnrich(id).executeAsOneOrNull() ?: return false
        // SourceForge: preview is rendered from the app screenshots, not captured from the website
        sourceforgeProjectOf(b)?.let { project ->
            enrichSourceforge(b, project, System.currentTimeMillis() / 1000)
            return true
        }
        val ghRepo = Urls.githubRepo(b.url.trim())
        val target = (if (ghRepo != null) b.website_url else b.url.trim())?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return false
        _progress.update { it.copy(current = Urls.domain(target) ?: target) }
        val bytes = screenshotter().capture(target) ?: return false
        val path = media.save(MediaStore.Kind.Thumbnail, "${b.uuid}-${System.currentTimeMillis()}", bytes) ?: return false
        write { q.setThumbnail(path, id) }
        if (b.thumbnail_path != null && b.thumbnail_path != path) media.delete(b.thumbnail_path)
        onChanged(id)
        return true
    }

    /** Re-capture thumbnails of [ids] (background run with progress); [onDone] receives the number of renewed ones. */
    fun refreshThumbnails(ids: List<Long>, onDone: (Int) -> Unit = {}) {
        if (job?.isActive == true) return
        if (ids.isEmpty()) { onDone(0); return }
        pause.resume()
        _progress.value = EnrichProgress(running = true, total = ids.size)
        job = scope.launch {
            val done = AtomicInteger()
            val ok = AtomicInteger()
            val errors = AtomicInteger()
            val channel = Channel<Long>(Channel.UNLIMITED)
            ids.forEach { channel.trySend(it) }
            channel.close()
            coroutineScope {
                repeat(settings().browserWorkers.coerceIn(1, 6)) {
                    launch {
                        for (id in channel) {
                            if (!isActive) break
                            pause.await()
                            val r = runCatching { withTimeoutOrNull(120_000) { refreshThumbnail(id) } }
                                .onFailure { e -> _progress.update { it.copy(lastError = e.message) } }.getOrNull()
                            if (r == true) ok.incrementAndGet() else errors.incrementAndGet()
                            _progress.update { it.copy(done = done.incrementAndGet(), errors = errors.get()) }
                        }
                    }
                }
            }
            _progress.update { it.copy(running = false, current = null) }
            onDone(ok.get())
        }
    }

    /**
     * Own card for a GitHub repo: first sufficiently tall image from the first lines of the README
     * (screenshot, demo, banner) plus owner avatar, name, description, topics and stars.
     * Returns (path, image URL) or null if the README has no suitable image.
     */
    private fun renderReadmeCard(
        key: String, owner: String, repo: String, name: String, description: String?, topics: List<String>, stars: Long?, readme: String?,
    ): Pair<String, String>? {
        val cfg = settings()
        if (readme.isNullOrBlank() || cfg.readmeImageMinHeight <= 0) return null
        val (url, bytes) = findReadmeImage(readme, owner, repo, cfg.readmeImageLines, cfg.readmeImageMinHeight) ?: return null
        val avatar = http.get("https://github.com/$owner.png?size=200", maxBytes = 1 shl 20, accept = IMAGE_PLAIN).takeIf { it.ok }?.body
        val png = AppCardRenderer.render(
            AppCardRenderer.Card(
                // GitHub emoji shortcodes (":cherry_blossom: A …") cannot be rendered by the card
                name = name, tagline = description?.replace(Regex(""":[a-z0-9_+-]+:\s*"""), "")?.trim()?.ifEmpty { null },
                categories = topics.take(4), source = "GitHub",
                screenshot = bytes, icon = avatar, accent = java.awt.Color(0x3F, 0xB9, 0x50),
                badge = stars?.takeIf { it > 0 }?.let(AppCardRenderer::stars),
            ),
        )
        return media.save(MediaStore.Kind.Card, key, png)?.let { it to url }
    }

    /** Loads the candidates in order (at most 6) and takes the first readable image with the minimum height. */
    private fun findReadmeImage(readme: String, owner: String, repo: String, lines: Int, minHeight: Int): Pair<String, ByteArray>? {
        for (c in ReadmeImages.candidates(readme, owner, repo, lines).filter { (it.declaredHeight ?: Int.MAX_VALUE) >= minHeight }.take(6)) {
            val res = http.get(c.url, maxBytes = 12 shl 20, accept = IMAGE_PLAIN)
            val bytes = res.body?.takeIf { res.ok } ?: continue
            val img = AppCardRenderer.decode(bytes) ?: continue
            if (img.height >= minHeight && img.width >= minHeight / 2) return c.url to bytes
        }
        return null
    }

    /**
     * Regenerates the GitHub card from the stored README (without GitHub API). Without a suitable README image
     * the existing card remains unchanged. Returns true if a new card was created.
     */
    suspend fun rerenderGithubCard(id: Long): Boolean {
        val r = q.forReadmeCard(id).executeAsOneOrNull() ?: return false
        val (owner, repo) = Urls.githubRepo(r.url.trim()) ?: return false
        _progress.update { it.copy(current = "$owner/$repo") }
        val topics = q.tagNamesOf(id).executeAsList()
        val own = renderReadmeCard(
            "${r.uuid}-${System.currentTimeMillis()}", owner, repo, repo, r.meta_description, topics, r.gh_stars, r.readme,
        ) ?: return false
        write { q.setCardImage(own.first, own.second, id) }
        if (r.card_image_path != null && r.card_image_path != own.first) media.delete(r.card_image_path)
        onChanged(id)
        return true
    }

    /** Re-render all GitHub repos with README (background run with progress). */
    fun rerenderGithubCards(onDone: (Int) -> Unit = {}) {
        if (job?.isActive == true) return
        val ids = q.githubWithReadmeIds().executeAsList()
        if (ids.isEmpty()) { onDone(0); return }
        pause.resume()
        _progress.value = EnrichProgress(running = true, total = ids.size)
        job = scope.launch {
            val done = AtomicInteger()
            val made = AtomicInteger()
            val channel = Channel<Long>(Channel.UNLIMITED)
            ids.forEach { channel.trySend(it) }
            channel.close()
            coroutineScope {
                repeat(4) {
                    launch {
                        for (id in channel) {
                            if (!isActive) break
                            pause.await()
                            if (runCatching { rerenderGithubCard(id) }.getOrDefault(false)) made.incrementAndGet()
                            _progress.update { it.copy(done = done.incrementAndGet()) }
                        }
                    }
                }
            }
            _progress.update { it.copy(running = false, current = null) }
            onDone(made.get())
        }
    }

    private suspend fun fetchFavicon(id: Long, candidates: List<String>) {
        for (iconUrl in candidates.take(4)) {
            val res = http.get(iconUrl, maxBytes = 300_000, accept = Http.ACCEPT_IMAGE)
            val bytes = res.body?.takeIf { res.ok && it.size > 32 } ?: continue
            val mime = ImageSniffer.mime(bytes) ?: continue
            if (mime == "image/svg+xml" || mime == "image/avif") continue
            val hash = sha256Hex(bytes)
            write {
                db.bookmarkQueries.insertIcon(hash, mime, bytes)
                q.setFaviconIfMissing(hash, id)
            }
            return
        }
    }

    /** Store image as icon (deduplicated via the hash); null for SVG/AVIF or unreadable data. */
    private suspend fun storeIcon(bytes: ByteArray): String? {
        if (bytes.size <= 32) return null
        val mime = ImageSniffer.mime(bytes) ?: return null
        if (mime == "image/svg+xml" || mime == "image/avif") return null
        val hash = sha256Hex(bytes)
        write { db.bookmarkQueries.insertIcon(hash, mime, bytes) }
        return hash
    }

    /** GitHub avatar of a user or organization (stored separately from the favicon). */
    private suspend fun fetchAvatar(owner: String): String? {
        val res = http.get("https://github.com/$owner.png?size=128", maxBytes = 1 shl 20, accept = IMAGE_PLAIN)
        return res.body?.takeIf { res.ok }?.let { storeIcon(it) }
    }

    /** Favicon of a project website: icon links of the page, otherwise /favicon.ico. */
    private suspend fun fetchWebsiteIcon(website: String): String? {
        val host = Urls.domain(website) ?: return null
        val page = domainLocks.getOrPut(host) { Semaphore(1) }.withPermit { http.get(website) }
        val fromPage = if (page.ok && page.isHtml) {
            page.text()?.let { runCatching { PageScraper.parse(it, page.finalUrl).iconUrls }.getOrNull() }.orEmpty()
        } else emptyList()
        val root = runCatching { java.net.URI(page.finalUrl.ifBlank { website }).let { "${it.scheme}://${it.authority}/favicon.ico" } }.getOrNull()
        for (iconUrl in (fromPage + listOfNotNull(root)).distinct().take(5)) {
            val res = http.get(iconUrl, maxBytes = 300_000, accept = Http.ACCEPT_IMAGE)
            res.body?.takeIf { res.ok }?.let { storeIcon(it) }?.let { return it }
        }
        return null
    }

    /** Reload missing avatars or website icons of a project; true if something was added. */
    suspend fun refreshIcons(id: Long): Boolean {
        val r = q.forIcons(id).executeAsOneOrNull() ?: return false
        val ghRepo = Urls.githubRepo(r.url.trim())
        val owner = (r.repo_url?.let(Urls::githubRepo) ?: ghRepo)?.first
        _progress.update { it.copy(current = owner ?: Urls.domain(r.url)) }
        var changed = false
        if (r.avatar_hash == null && owner != null) fetchAvatar(owner)?.let { h -> write { q.setAvatar(h, id) }; changed = true }
        val site = r.website_url
        if (r.website_icon_hash == null && ghRepo != null && site != null) {
            fetchWebsiteIcon(site)?.let { h -> write { q.setWebsiteIcon(h, id) }; changed = true }
        }
        if (changed) onChanged(id)
        return changed
    }

    fun missingIconCount(): Int = q.missingIconIds().executeAsList().size

    /** Reload avatars and website icons of all GitHub projects (background run with progress). */
    fun loadMissingIcons(onDone: (Int) -> Unit = {}) {
        if (job?.isActive == true) return
        val ids = q.missingIconIds().executeAsList()
        if (ids.isEmpty()) { onDone(0); return }
        pause.resume()
        _progress.value = EnrichProgress(running = true, total = ids.size)
        job = scope.launch {
            val done = AtomicInteger()
            val loaded = AtomicInteger()
            val channel = Channel<Long>(Channel.UNLIMITED)
            ids.forEach { channel.trySend(it) }
            channel.close()
            coroutineScope {
                repeat(6) {
                    launch {
                        for (id in channel) {
                            if (!isActive) break
                            pause.await()
                            if (runCatching { withTimeoutOrNull(60_000) { refreshIcons(id) } }.getOrNull() == true) loaded.incrementAndGet()
                            _progress.update { it.copy(done = done.incrementAndGet()) }
                        }
                    }
                }
            }
            _progress.update { it.copy(running = false, current = null) }
            onDone(loaded.get())
        }
    }

    private companion object {
        /** Only formats that ImageIO can read (CDNs otherwise like to deliver WebP/AVIF). */
        const val IMAGE_PLAIN = "image/png,image/jpeg;q=0.9,image/gif;q=0.8,*/*;q=0.1"
    }

    private fun normalizeHomepage(url: String): String =
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) url else "https://$url"

    private var shooterReal = false

    @Synchronized
    private fun screenshotter(): Screenshotter {
        val workers = settings().browserWorkers.coerceIn(1, 6)
        val real = settings().realBrowser
        val s = shooter
        if (s != null && shooterWorkers == workers && shooterReal == real) return s
        s?.close()
        val profile = if (real) File(mediaRoot.parentFile ?: mediaRoot, "browser-profile") else null
        return Screenshotter(
            workers, dark = { settings().darkScreenshots }, profileDir = profile, visible = { settings().visibleBrowser },
        ).also { shooter = it; shooterWorkers = workers; shooterReal = real }
    }

    private suspend fun write(block: () -> Unit) = dbLock.withLock { db.transaction { block() } }

    val screenshotError: String? get() = shooter?.lastError

    fun missingReadmeCount(): Long = q.countMissingReadme().executeAsOne()

    /**
     * Load missing READMEs of all GitHub projects (README only, no images/metadata). Background run with progress;
     * [onDone] receives the number of loaded READMEs.
     */
    fun loadMissingReadmes(onDone: (Int) -> Unit = {}) {
        if (job?.isActive == true) return
        val ids = q.missingReadmeIds().executeAsList()
        if (ids.isEmpty()) { onDone(0); return }
        pause.resume()
        _progress.value = EnrichProgress(running = true, total = ids.size)
        job = scope.launch {
            val done = AtomicInteger()
            val loaded = AtomicInteger()
            val errors = AtomicInteger()
            val channel = Channel<Long>(Channel.UNLIMITED)
            ids.forEach { channel.trySend(it) }
            channel.close()
            coroutineScope {
                repeat(6) {
                    launch {
                        for (id in channel) {
                            if (!isActive) break
                            pause.await()
                            val ok = runCatching { loadReadme(id) }.onFailure { e -> _progress.update { it.copy(lastError = e.message) } }
                            if (ok.getOrDefault(false)) loaded.incrementAndGet() else if (ok.isFailure) errors.incrementAndGet()
                            _progress.update { it.copy(done = done.incrementAndGet(), errors = errors.get()) }
                        }
                    }
                }
            }
            _progress.update { it.copy(running = false, current = null) }
            onDone(loaded.get())
        }
    }

    /** Load README for a GitHub project; true if one was stored. */
    suspend fun loadReadme(id: Long): Boolean {
        val r = q.forReadme(id).executeAsOneOrNull() ?: return false
        val (owner, repo) = (r.repo_url?.let(Urls::githubRepo) ?: Urls.githubRepo(r.url.trim())) ?: return false
        _progress.update { it.copy(current = "$owner/$repo") }
        val text = try {
            if (System.currentTimeMillis() / 1000 < githubPausedUntil) readmeRawOnly(owner, repo) else github.readme(owner, repo)
        } catch (e: GitHubRateLimited) {
            githubPausedUntil = e.resetAt
            _progress.update { it.copy(githubPausedUntil = e.resetAt) }
            null
        } ?: return false
        write {
            q.setReadme(text, id)
            index.update(id)
        }
        runCatching { afterScrape(id) } // package IDs from the README
        onChanged(id)
        return true
    }

    /** During the API pause only try raw.githubusercontent.com. */
    private fun readmeRawOnly(owner: String, repo: String): String? = GitHubClient.README_NAMES.firstNotNullOfOrNull { n ->
        http.get("https://raw.githubusercontent.com/$owner/$repo/HEAD/$n", accept = "text/plain").takeIf { it.ok }?.text()?.takeIf { it.isNotBlank() }
    }?.take(GitHubClient.MAX_README)

    /** License of a GitHub repo (respects the API pause); null if unknown or paused. */
    fun githubLicense(owner: String, repo: String): String? {
        if (System.currentTimeMillis() / 1000 < githubPausedUntil) return null
        return try {
            github.license(owner, repo)
        } catch (e: GitHubRateLimited) {
            githubPausedUntil = e.resetAt
            _progress.update { it.copy(githubPausedUntil = e.resetAt) }
            null
        }
    }

    /** Rendered HTML via the browser (with persistent profile if "real browser" is on) – e.g. for AlternativeTo. */
    suspend fun browserHtml(url: String): Pair<Int, String>? = screenshotter().html(url)

    /** GitHub rejected the token – enrichment continues without token (60 requests/h). */
    val githubTokenRejected: Boolean get() = github.tokenRejected

    override fun close() {
        runBlocking { job?.cancelAndJoin() }
        shooter?.close()
    }
}
