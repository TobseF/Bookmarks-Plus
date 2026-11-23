package de.bookmarksplus.enrich.apps

import de.bookmarksplus.i18n.tr
import de.bookmarksplus.data.AppInfo
import de.bookmarksplus.data.SearchIndex
import de.bookmarksplus.db.BookmarksDb
import de.bookmarksplus.db.ForApp
import de.bookmarksplus.enrich.AppCardRenderer
import de.bookmarksplus.enrich.Http
import de.bookmarksplus.enrich.ImageSniffer
import de.bookmarksplus.enrich.MediaStore
import de.bookmarksplus.enrich.ShotStore
import de.bookmarksplus.enrich.ai.AiClient
import de.bookmarksplus.enrich.ai.AiConfig
import de.bookmarksplus.util.Urls
import de.bookmarksplus.util.sha256Hex
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

data class AppSettings(
    val flathub: Boolean = true,
    val alternativeTo: Boolean = true,
    /** Minimum interval between two AlternativeTo requests (seconds); a random share of up to +50% is added. */
    val alternativeToIntervalSec: Int = 20,
    val alternativeToDailyLimit: Int = 400,
    /** Load AlternativeTo via the browser extension if one is connected. */
    val viaExtension: Boolean = true,
    /** Load AlternativeTo via the own browser (Playwright profile) if directly blocked. */
    val viaBrowser: Boolean = true,
    /** Search again without a hit only after this many days. */
    val recheckDays: Int = 30,
    /** Fetch AlternativeTo directly first (off = extension/browser only). */
    val alternativeToDirect: Boolean = true,
)

data class AppProgress(
    val running: Boolean = false,
    /** "Names", "Flathub", "AlternativeTo" */
    val phase: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val named: Int = 0,
    val flathub: Int = 0,
    val alternativeTo: Int = 0,
    val errors: Int = 0,
    val current: String? = null,
    /** AlternativeTo paused (block/daily limit) until … (ms). */
    val alternativeToPausedUntil: Long? = null,
    /** Most recently used path to AlternativeTo: direct, extension, browser. */
    val alternativeToVia: String? = null,
    val cost: Double = 0.0,
    val lastError: String? = null,
)

/**
 * App data for programs and games: clean name, platform/system, license, package IDs, Flathub (downloads,
 * size, icon, screenshots) and AlternativeTo. Ranking for the name: user > Flathub > AlternativeTo >
 * SourceForge > AI > heuristic. Icon and screenshots from Flathub take precedence over all other sources.
 * Empty fields are filled in, set ones (except statistics) are not overwritten.
 */
class AppService(
    private val driver: SqlDriver,
    private val db: BookmarksDb,
    mediaRoot: File,
    private val settings: () -> AppSettings,
    /** AI configuration for name finding (the cheap tag model is enough); null = no AI. */
    private val aiConfig: () -> Pair<AiConfig, String?>? = { null },
    private val dbLock: Mutex = Mutex(),
    bridge: () -> BrowserBridge? = { null },
    browserHtml: (suspend (String) -> Pair<Int, String>?)? = null,
    /** Reload license of a GitHub repo (for older enrichments without license). */
    private val githubLicense: ((owner: String, repo: String) -> String?)? = null,
    private val onChanged: (Long) -> Unit = {},
    private val http: Http = Http(),
) : Closeable {

    private val q get() = db.appQueries
    private val eq get() = db.enrichQueries
    private val media = MediaStore(mediaRoot)
    private val index = SearchIndex(driver)
    private val flathubClient = FlathubClient(http)
    val alternativeTo = AlternativeToClient(
        http, bridge = bridge, browser = browserHtml,
        useBridge = { settings().viaExtension }, useBrowser = { settings().viaBrowser }, useDirect = { settings().alternativeToDirect },
    )
    private val itch = ItchClient(http)
    private val nameAi = AppNameAi(AiClient(http))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** Pause switch of the running batch. */
    val pause = de.bookmarksplus.enrich.PauseGate()

    private val _progress = MutableStateFlow(AppProgress())
    val progress: StateFlow<AppProgress> = _progress

    init {
        index.ensure()
    }

    fun candidateCount(): Int = q.appCandidateIds().executeAsList().size

    // ------------------------------------------------------------------------------------------ Run

    /**
     * Maintenance run: (1) local facts and names for all candidates, (2) AI names bundled, (3) Flathub, (4) AlternativeTo.
     * [ids] = only these bookmarks; [force] also searches already checked ones again.
     */
    fun start(ids: List<Long>? = null, force: Boolean = false, onDone: () -> Unit = {}) {
        if (job?.isActive == true) return
        val targets = ids ?: q.appCandidateIds().executeAsList()
        if (targets.isEmpty()) { onDone(); return }
        applyGateSettings()
        pause.resume()
        _progress.value = AppProgress(running = true, phase = tr("enrich.apps.phaseNames"), total = targets.size)
        job = scope.launch {
            try {
                // 1) local
                targets.forEachIndexed { i, id ->
                    if (!isActive) return@launch
                    pause.await()
                    runCatching { local(id) }
                    _progress.update { it.copy(done = i + 1) }
                }
                // 2) AI names
                aiConfig()?.let { (cfg, effort) -> aiNames(targets, cfg, effort, force) }
                // 2b) GitHub licenses for detected programs with repo
                githubLicenses(targets)
                // 3) Flathub
                if (settings().flathub) {
                    _progress.update { it.copy(phase = "Flathub", done = 0) }
                    targets.forEachIndexed { i, id ->
                        if (!isActive) return@launch
                        pause.await()
                        guard { if (flathub(id, force)) _progress.update { it.copy(flathub = it.flathub + 1) } }
                        _progress.update { it.copy(done = i + 1) }
                    }
                }
                // 3b) itch.io (only bookmarks on itch.io pages)
                _progress.update { it.copy(phase = "itch.io", done = 0) }
                targets.forEachIndexed { i, id ->
                    if (!isActive) return@launch
                    pause.await()
                    guard { itchio(id, force) }
                    _progress.update { it.copy(done = i + 1) }
                }
                // 4) AlternativeTo (throttled; skip pauses instead of waiting)
                if (settings().alternativeTo) {
                    _progress.update { it.copy(phase = "AlternativeTo", done = 0) }
                    targets.forEachIndexed { i, id ->
                        if (!isActive) return@launch
                        pause.await()
                        guard { if (alternativeTo(id, force)) _progress.update { it.copy(alternativeTo = it.alternativeTo + 1) } }
                        _progress.update { it.copy(done = i + 1, alternativeToVia = alternativeTo.lastVia) }
                    }
                }
            } finally {
                _progress.update { it.copy(running = false, current = null, phase = null) }
                onDone()
            }
        }
    }

    fun stop() {
        val j = job ?: return
        scope.launch { j.cancelAndJoin() }
    }

    /** One bookmark completely (e.g. "↻ App data" in the detail area). */
    suspend fun processOne(id: Long, force: Boolean = true) {
        applyGateSettings()
        local(id)
        aiConfig()?.let { (cfg, effort) -> runCatching { aiNames(listOf(id), cfg, effort, force) } }
        githubLicenses(listOf(id))
        if (settings().flathub) guard { flathub(id, force) }
        guard { itchio(id, force) }
        if (settings().alternativeTo) guard { alternativeTo(id, force) }
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimited) {
            _progress.update { it.copy(alternativeToPausedUntil = e.until) }
        } catch (e: Exception) {
            _progress.update { it.copy(errors = it.errors + 1, lastError = e.message ?: e::class.simpleName) }
        }
    }

    private fun applyGateSettings() {
        val s = settings()
        alternativeTo.gate.minIntervalMs = s.alternativeToIntervalSec.coerceAtLeast(1) * 1000L
        alternativeTo.gate.dailyLimit = s.alternativeToDailyLimit.coerceAtLeast(1)
    }

    // ------------------------------------------------------------------------------------------ Steps

    /**
     * Without network: facts from the URL (and website/repo), package IDs from README and page text, name via heuristic.
     * Also runs after every enrichment ([de.bookmarksplus.enrich.Enricher]).
     */
    suspend fun local(id: Long) {
        val r = q.forApp(id).executeAsOneOrNull() ?: return
        val cur = info(r)
        var add = listOfNotNull(r.url, r.website_url).map(AppUrls::facts).fold(AppInfo.EMPTY, ::union)
        val repoName = (r.repo_url ?: r.url).let(Urls::githubRepo)?.second

        // name: SourceForge project name, otherwise heuristic from the title
        if (cur.name == null && r.app_name_source == null) {
            val sf = Urls.sourceforgeProject(r.url) != null
            val name = when {
                sf -> r.meta_title?.let(AppNames::clean)?.takeIf { it.isNotBlank() && it.length <= 60 }
                else -> {
                    val hints = listOfNotNull(repoName, AppNames.domainHint(r.website_url ?: r.url))
                    listOfNotNull(r.meta_title, r.title).firstNotNullOfOrNull { AppNames.fromTitle(it, hints) }
                }
            }
            if (name != null) add = add.copy(name = name, nameSource = if (sf) "sourceforge" else "heuristic")
        }

        // package IDs from README / page text, matching the name or repo
        val text = listOfNotNull(r.readme, r.page_text).joinToString("\n")
        if (text.isNotBlank()) {
            val found = PackageIds.find(text, add.name ?: cur.name, repoName)
            add = add.copy(
                winget = add.winget ?: found.winget, choco = add.choco ?: found.choco, scoop = add.scoop ?: found.scoop,
                brew = add.brew ?: found.brew, npm = add.npm ?: found.npm,
                flathubUrl = add.flathubUrl ?: found.flatpak?.let(AppInfo::flathubUrl),
            )
        }
        save(r, cur, add)
    }

    /** Missing names via AI, 25 per call. */
    private suspend fun aiNames(ids: List<Long>, cfg: AiConfig, effort: String?, force: Boolean) {
        val rows = ids.mapNotNull { q.forApp(it).executeAsOneOrNull() }
            .filter { it.app_name_source == null || it.app_name_source == "heuristic" || (force && it.app_name_source == "none") }
        if (rows.isEmpty()) return
        _progress.update { it.copy(phase = "Namen (KI)", done = 0, total = rows.size) }
        var done = 0
        for (chunk in rows.chunked(25)) {
            if (!currentCoroutineContextActive()) return
            val inputs = chunk.map {
                AppNameInput(it.id, it.url, it.title, it.meta_title, it.description ?: it.meta_description, it.repo_url, it.website_url)
            }
            try {
                val (results, chat) = withContext(Dispatchers.IO) { nameAi.resolve(cfg, inputs, effort) }
                _progress.update { it.copy(cost = it.cost + (chat.cost ?: 0.0)) }
                for (res in results) {
                    val r = chunk.firstOrNull { it.id == res.id } ?: continue
                    if (res.name == null) {
                        // AI says "not a program": discard heuristic names, do not ask again
                        write {
                            if (r.app_name_source == "heuristic") q.updateApp(null, "none", r.app_platforms, r.app_systems, r.app_license,
                                r.pkg_winget, r.pkg_choco, r.pkg_brew, r.pkg_npm, r.pkg_scoop, r.flathub_url, r.alternativeto_url, r.id)
                            else q.markNoApp(r.id)
                        }
                    } else {
                        save(r, info(r), AppInfo(name = res.name, nameSource = "ai", platforms = res.platforms, systems = res.systems))
                        _progress.update { it.copy(named = it.named + 1) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _progress.update { it.copy(errors = it.errors + 1, lastError = e.message) }
            }
            done += chunk.size
            _progress.update { it.copy(done = done) }
        }
        _progress.update { it.copy(total = ids.size) }
    }

    /** License from GitHub for programs with repo but without license. */
    private suspend fun githubLicenses(ids: List<Long>) {
        val fetch = githubLicense ?: return
        val rows = ids.mapNotNull { q.forApp(it).executeAsOneOrNull() }
            .filter { it.app_license == null && it.app_name != null && it.repo_url != null }
        if (rows.isEmpty()) return
        _progress.update { it.copy(phase = "Lizenzen (GitHub)", done = 0, total = rows.size) }
        rows.forEachIndexed { i, r ->
            if (!currentCoroutineContextActive()) return
            val (owner, repo) = Urls.githubRepo(r.repo_url!!) ?: return@forEachIndexed
            val lic = runCatching { withContext(Dispatchers.IO) { fetch(owner, repo) } }.getOrNull()
            if (lic != null) { write { q.setLicenseIfMissing(lic, r.id) }; onChanged(r.id) }
            _progress.update { it.copy(done = i + 1) }
        }
    }

    private suspend fun currentCoroutineContextActive(): Boolean = kotlinx.coroutines.currentCoroutineContext().isActive

    private fun due(checkedAt: Long?, force: Boolean): Boolean =
        force || checkedAt == null || System.currentTimeMillis() / 1000 - checkedAt > settings().recheckDays * 86_400L

    /** Flathub: query existing ID or search by name. Returns true if data was adopted. */
    suspend fun flathub(id: Long, force: Boolean = false): Boolean {
        val r = q.forApp(id).executeAsOneOrNull() ?: return false
        val cur = info(r)
        val known = cur.flathubId
        if (known == null && (cur.name == null || r.app_name_source == "none" || !due(r.flathub_checked_at, force))) return false
        _progress.update { it.copy(current = cur.name ?: r.url) }
        val now = System.currentTimeMillis() / 1000
        val appId = known ?: pickFlathub(cur.name!!, r)
        val app = appId?.let { flathubClient.app(it) }
        if (app == null) {
            write { q.markFlathubChecked(now, id) }
            return false
        }
        applyFlathub(r, cur, app, now)
        return true
    }

    /** Hit only for the same name; if the website does not match, it is discarded (namesakes). */
    private suspend fun pickFlathub(name: String, r: ForApp): String? {
        val hits = flathubClient.search(name).filter { AppNames.same(it.name, name) }
        if (hits.isEmpty()) return null
        val site = (r.website_url ?: r.url.takeUnless { Urls.githubRepo(it) != null })?.let(Urls::domain)?.removePrefix("www.")
        if (hits.size == 1 && site == null) return hits.single().id
        for (h in hits) {
            val app = flathubClient.app(h.id) ?: continue
            val home = app.homepage?.let(Urls::domain)?.removePrefix("www.")
            val repoOk = r.repo_url?.let { repo -> app.vcs?.trimEnd('/')?.equals(repo.trimEnd('/'), true) } == true
            if (repoOk || (site != null && home != null && sameSite(site, home))) return h.id
            if (site == null && home == null) return h.id
        }
        // bookmark itself is a directory entry (Flathub/AlternativeTo) → name alone suffices
        return hits.singleOrNull()?.id?.takeIf { site == null || site in DIRECTORY_SITES }
    }

    private suspend fun applyFlathub(r: ForApp, cur: AppInfo, app: FlathubApp, now: Long) {
        val add = AppInfo(
            name = app.name, nameSource = "flathub", platforms = listOf("pc"), systems = listOf("linux"),
            license = app.license, flathubUrl = app.url,
        )
        save(r, cur, add)
        write {
            q.setFlathubStats(app.installs, app.downloadSize, app.installedSize, r.id)
            q.markFlathubChecked(now, r.id)
            app.homepage?.takeIf { it.startsWith("http") }?.let { eq.setWebsiteUrlIfMissing(it, r.id) }
            app.vcs?.let { v -> Urls.githubRepo(v)?.let { (o, n) -> eq.setRepoUrlIfMissing("https://github.com/$o/$n", r.id) } }
            app.description?.let { d -> if (r.page_text.isNullOrBlank()) db.aiQueries.setPageText(d, r.id) }
        }
        // icon and screenshots from Flathub take precedence
        val icon = app.iconUrl?.let { flathubClient.image(it, maxBytes = 1 shl 20) }
        icon?.let { setIcon(it) { h -> eq.setFlathubIcon(h, r.id) } }
        val shots = app.screenshots.mapNotNull { flathubClient.image(it) }
        if (shots.isNotEmpty()) setScreenshots(r, shots, de.bookmarksplus.data.Shots.FLATHUB)
        // bookmark is itself the Flathub page → render thumbnail from screenshot
        if (Urls.domain(r.url)?.removePrefix("www.") == "flathub.org" && shots.isNotEmpty()) {
            val png = AppCardRenderer.render(
                AppCardRenderer.Card(
                    name = app.name, tagline = app.summary, categories = app.categories.take(3), source = "Flathub",
                    screenshot = shots.first(), icon = icon, accent = java.awt.Color(0x4A, 0x90, 0xD9),
                    badge = app.installs?.let { "↓ ${AppCardRenderer.stars(it).removePrefix("★ ")}" },
                ),
            )
            media.save(MediaStore.Kind.Thumbnail, "${r.uuid}-${System.currentTimeMillis()}", png)?.let { p ->
                write { eq.setThumbnail(p, r.id) }
                if (r.thumbnail_path != null && r.thumbnail_path != p) media.delete(r.thumbnail_path)
            }
        }
        write { index.update(r.id) }
        onChanged(r.id)
    }

    /**
     * itch.io: bookmark (or its website) is a project page → name, platform/operating systems from the downloads,
     * screenshots. Without a dedicated check column a page counts as done as soon as itch.io screenshots or the name from itch.io are present.
     */
    suspend fun itchio(id: Long, force: Boolean = false): Boolean {
        val r = q.forApp(id).executeAsOneOrNull() ?: return false
        val page = listOfNotNull(r.url, r.website_url).firstNotNullOfOrNull(ItchParser::pageUrl) ?: return false
        val done = r.app_name_source == "itchio" || de.bookmarksplus.data.Shots.parse(r.shots_json).any { it.source == de.bookmarksplus.data.Shots.ITCHIO }
        if (done && !force) return false
        // block/daily limit: skip (the AlternativeTo pause notice stays unaffected)
        if (itch.gate.paused || itch.gate.exhausted) return false
        _progress.update { it.copy(current = "itch.io: $page") }
        val app = when (val res = itch.fetch(page)) {
            is ItchResult.Found -> res.app
            else -> return false
        }
        val cur = info(r)
        save(r, cur, AppInfo(name = app.name, nameSource = "itchio", platforms = app.platforms, systems = app.systems))
        write {
            app.authorUrl?.let { eq.setWebsiteUrlIfMissing(it, id) }
            if (r.page_text.isNullOrBlank()) app.description?.let { db.aiQueries.setPageText(it, id) }
        }
        val shots = app.screenshots.mapNotNull { itch.image(it) }
        if (shots.isNotEmpty()) setScreenshots(r, shots, de.bookmarksplus.data.Shots.ITCHIO)
        write { index.update(id) }
        onChanged(id)
        return true
    }

    /** AlternativeTo: known page or slug from the name. Returns true if data was adopted. */
    suspend fun alternativeTo(id: Long, force: Boolean = false): Boolean {
        val r = q.forApp(id).executeAsOneOrNull() ?: return false
        val cur = info(r)
        val knownSlug = cur.alternativetoUrl?.let(AppInfo::alternativetoSlugOf)
        if (!due(r.alternativeto_checked_at, force) && !(knownSlug != null && r.alternativeto_checked_at == null)) return false
        val slug = knownSlug ?: cur.name?.takeIf { r.app_name_source != "none" }?.let(AppNames::slug)?.takeIf { it.length >= 2 } ?: return false
        if (alternativeTo.gate.paused) throw RateLimited("AlternativeTo", alternativeTo.gate.pausedUntil)
        _progress.update { it.copy(current = "AlternativeTo: ${cur.name ?: slug}") }
        val now = System.currentTimeMillis() / 1000
        // guessed slug: name must match, website must not contradict
        fun fits(at: AlternativeToApp): Boolean {
            if (knownSlug != null) return true
            // same repo is unambiguous, even if the name differs
            val repo = r.repo_url ?: r.url
            if (at.repoUrl != null && AppNames.sameRepo(at.repoUrl, repo)) return true
            val site = r.website_url?.let(Urls::domain)?.removePrefix("www.")
            val home = at.website?.let(Urls::domain)?.removePrefix("www.")
            return AppNames.same(at.name, cur.name) && !(site != null && home != null && !sameSite(site, home))
        }
        var res = alternativeTo.fetch(slug)
        // slug does not match (program renamed, e.g. Hoppscotch under /software/postwoman/) → search by name
        val curName = cur.name
        if (knownSlug == null && curName != null && (res == AtResult.NotFound || (res is AtResult.Found && !fits(res.app)))) {
            when (val s = alternativeTo.search(curName)) {
                is AtSearch.Blocked -> res = AtResult.Blocked(s.until)
                is AtSearch.Hits -> {
                    val hit = s.hits.firstOrNull { (hs, hn) -> hs != slug && AppNames.same(hn, curName) }
                    if (hit != null) res = alternativeTo.fetch(hit.first)
                }
            }
        }
        when (res) {
            is AtResult.Blocked -> {
                _progress.update { it.copy(alternativeToPausedUntil = res.until) }
                return false
            }
            AtResult.NotFound -> { write { q.markAlternativeToChecked(now, id) }; return false }
            is AtResult.Found -> {
                if (!fits(res.app)) { write { q.markAlternativeToChecked(now, id) }; return false }
                return applyAlternativeTo(r, res.app, now)
            }
        }
    }

    /**
     * Adopt page from the browser (extension: "Adopt data"). The user chose the bookmark,
     * hence without name check; the name from AlternativeTo then counts like a directory entry.
     */
    suspend fun applyAlternativeToPage(id: Long, html: String, url: String): AlternativeToApp? {
        val at = AlternativeToParser.parse(html, url) ?: return null
        val r = q.forApp(id).executeAsOneOrNull() ?: return null
        applyAlternativeTo(r, at, System.currentTimeMillis() / 1000, replaceUrl = true)
        return at
    }

    /** What an opened Flathub or AlternativeTo page describes (for "Adopt data" in the extension). */
    data class PageApp(
        val source: String, val name: String, val website: String?, val repo: String?,
        val flathubUrl: String?, val alternativetoUrl: String?, val summary: String?,
    )

    /** Evaluate page: Flathub via the API (the ID is in the URL), AlternativeTo from the submitted HTML. */
    suspend fun describePage(url: String, html: String?): PageApp? {
        AppInfo.flathubIdOf(url)?.let { appId ->
            val app = flathubClient.app(appId) ?: return null
            return PageApp("flathub", app.name, app.homepage, app.vcs?.takeIf { Urls.githubRepo(it) != null }, app.url, null, app.summary)
        }
        if (AppInfo.alternativetoSlugOf(url) != null && html != null) {
            val at = AlternativeToParser.parse(html, url) ?: return null
            return PageApp("alternativeto", at.name, at.website, at.repoUrl, at.flathubUrl, at.url, at.description)
        }
        return null
    }

    /** Adopt Flathub page: set Flathub URL (replaces a differing one) and query immediately. */
    suspend fun applyFlathubPage(id: Long, appId: String): Boolean {
        val r = q.forApp(id).executeAsOneOrNull() ?: return false
        val url = AppInfo.flathubUrl(appId)
        if (r.flathub_url != url) write {
            q.updateApp(r.app_name, r.app_name_source, r.app_platforms, r.app_systems, r.app_license,
                r.pkg_winget, r.pkg_choco, r.pkg_brew, r.pkg_npm, r.pkg_scoop, url, r.alternativeto_url, id)
        }
        return flathub(id, force = true)
    }

    private suspend fun applyAlternativeTo(r0: ForApp, at: AlternativeToApp, now: Long, replaceUrl: Boolean = false): Boolean {
        val id = r0.id
        // the page chosen by the user replaces a differing AlternativeTo URL
        val r = if (replaceUrl && r0.alternativeto_url != at.url) r0.copy(alternativeto_url = null) else r0
        val cur = info(r)
        run {
            run {
                val add = AppInfo(
                    name = at.name, nameSource = "alternativeto", platforms = at.platforms, systems = at.systems,
                    license = at.license, winget = at.packages.winget, choco = at.packages.choco, scoop = at.packages.scoop,
                    brew = at.packages.brew, npm = at.packages.npm, flathubUrl = at.flathubUrl, alternativetoUrl = at.url,
                )
                save(r, cur, add)
                write {
                    q.markAlternativeToChecked(now, id)
                    at.website?.let { eq.setWebsiteUrlIfMissing(it, id) }
                    at.repoUrl?.let { v -> Urls.githubRepo(v)?.let { (o, n) -> eq.setRepoUrlIfMissing("https://github.com/$o/$n", id) } }
                }
                // icon from AlternativeTo (ranks behind Flathub, but before all website icons)
                if (at.iconUrl != null && (r.alternativeto_icon_hash == null || replaceUrl)) {
                    alternativeTo.image(at.iconUrl)?.let { bytes -> setIcon(bytes) { h -> eq.setAlternativetoIcon(h, id) } }
                }
                // append screenshots; already existing ones (same content) are not stored twice
                if (at.screenshots.isNotEmpty()) {
                    val shots = at.screenshots.mapNotNull { alternativeTo.image(it) }
                    if (shots.isNotEmpty()) setScreenshots(r, shots, de.bookmarksplus.data.Shots.ALTERNATIVETO)
                }
                write { index.update(id) }
                onChanged(id)
                // query newly discovered Flathub page right away
                if (cur.flathubUrl == null && at.flathubUrl != null && settings().flathub) runCatching { flathub(id, force = true) }
                return true
            }
        }
    }

    /**
     * Adopt the result of AI stage 1 for a single bookmark. Not a program → only discard a heuristic name.
     * New app names trigger the search at Flathub (and, throttled, at AlternativeTo).
     */
    suspend fun applyAi(id: Long, app: de.bookmarksplus.enrich.ai.AiApp?, lookup: Boolean = true) {
        val r = q.forApp(id).executeAsOneOrNull() ?: return
        if (app == null) {
            if (r.app_name_source == "heuristic" || r.app_name_source == null) write {
                if (r.app_name_source == "heuristic") q.updateApp(null, "none", r.app_platforms, r.app_systems, r.app_license,
                    r.pkg_winget, r.pkg_choco, r.pkg_brew, r.pkg_npm, r.pkg_scoop, r.flathub_url, r.alternativeto_url, r.id)
                else q.markNoApp(id)
            }
            return
        }
        val before = r.app_name
        save(r, info(r), AppInfo(name = app.name, nameSource = "ai", platforms = app.platforms, systems = app.systems))
        if (lookup && (before == null || r.flathub_checked_at == null)) scope.launch {
            if (settings().flathub) guard { flathub(id) }
            if (settings().alternativeTo && !alternativeTo.gate.paused) guard { alternativeTo(id) }
        }
    }

    // ------------------------------------------------------------------------------------------ Writing

    /** Ranking of the name sources (higher wins). */
    private fun rank(source: String?): Int = when (source) {
        "user" -> 100; "flathub" -> 60; "alternativeto" -> 50; "itchio" -> 45; "sourceforge" -> 40; "ai" -> 30; "heuristic" -> 10
        else -> 0
    }

    /** Merge new facts [add] with the state [cur] and store them. */
    private suspend fun save(r: ForApp, cur: AppInfo, add: AppInfo) {
        val merged = merge(cur, add)
        if (merged == cur) return
        write {
            q.updateApp(
                merged.name, merged.nameSource, AppInfo.join(merged.platforms), AppInfo.join(merged.systems), merged.license,
                merged.winget, merged.choco, merged.brew, merged.npm, merged.scoop, merged.flathubUrl, merged.alternativetoUrl, r.id,
            )
        }
        onChanged(r.id)
    }

    fun merge(cur: AppInfo, add: AppInfo): AppInfo {
        val takeName = add.name != null && rank(add.nameSource) > rank(cur.nameSource) && !(cur.nameSource == "user")
        return cur.copy(
            name = if (takeName) add.name else cur.name,
            nameSource = if (takeName) add.nameSource else cur.nameSource,
            platforms = (cur.platforms + add.platforms).distinct(),
            systems = (cur.systems + add.systems).distinct(),
            license = cur.license ?: add.license,
            winget = cur.winget ?: add.winget, choco = cur.choco ?: add.choco, scoop = cur.scoop ?: add.scoop,
            brew = cur.brew ?: add.brew, npm = cur.npm ?: add.npm,
            flathubUrl = cur.flathubUrl ?: add.flathubUrl, alternativetoUrl = cur.alternativetoUrl ?: add.alternativetoUrl,
        )
    }

    private fun info(r: ForApp) = AppInfo(
        name = r.app_name, nameSource = r.app_name_source, platforms = AppInfo.split(r.app_platforms), systems = AppInfo.split(r.app_systems),
        license = r.app_license, winget = r.pkg_winget, choco = r.pkg_choco, brew = r.pkg_brew, npm = r.pkg_npm, scoop = r.pkg_scoop,
        flathubUrl = r.flathub_url, alternativetoUrl = r.alternativeto_url,
    )

    private fun union(a: AppInfo, b: AppInfo) = merge(a, b)

    /** Store the icon of a directory source in its own column; the favicon of the page stays untouched. */
    private suspend fun setIcon(bytes: ByteArray, store: (String) -> Unit) {
        val mime = ImageSniffer.mime(bytes) ?: return
        if (mime == "image/svg+xml" || mime == "image/avif") return
        val hash = sha256Hex(bytes)
        write {
            db.bookmarkQueries.insertIcon(hash, mime, bytes)
            store(hash)
        }
    }

    private suspend fun setScreenshots(r: ForApp, shots: List<ByteArray>, source: String) {
        ShotStore.add(db, media, r.id, r.uuid, source, shots) { block -> write { block() } }
    }

    private suspend fun write(block: () -> Unit) = dbLock.withLock { db.transaction { block() } }

    override fun close() {
        runBlocking { job?.cancelAndJoin() }
        scope.cancel()
    }

    private fun sameSite(a: String, b: String) = AppNames.sameSite(a, b)

    private companion object {
        val DIRECTORY_SITES = setOf("flathub.org", "alternativeto.net")
    }
}
