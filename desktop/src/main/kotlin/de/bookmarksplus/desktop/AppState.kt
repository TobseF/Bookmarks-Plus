package de.bookmarksplus.desktop

import de.bookmarksplus.i18n.tr
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.cash.sqldelight.db.SqlDriver
import de.bookmarksplus.data.BookmarkRepository
import de.bookmarksplus.data.Filter
import de.bookmarksplus.data.FilterEngine
import de.bookmarksplus.data.Item
import de.bookmarksplus.data.Library
import de.bookmarksplus.data.ParsedQuery
import de.bookmarksplus.data.SearchIndex
import de.bookmarksplus.data.SettingsRepository
import de.bookmarksplus.data.SortField
import de.bookmarksplus.data.TagRepository
import de.bookmarksplus.db.BookmarksDb
import de.bookmarksplus.desktop.ui.DEFAULT_ACCENT
import de.bookmarksplus.netscape.NetscapeParser
import de.bookmarksplus.netscape.NetscapeWriter
import de.bookmarksplus.util.Urls
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import de.bookmarksplus.enrich.EnrichProgress
import de.bookmarksplus.enrich.EnrichSettings
import de.bookmarksplus.enrich.Enricher
import de.bookmarksplus.enrich.ai.AiConfig
import de.bookmarksplus.enrich.ai.AiModel
import de.bookmarksplus.enrich.ai.AiProgress
import de.bookmarksplus.enrich.ai.AiProvider
import de.bookmarksplus.enrich.ai.AiService
import de.bookmarksplus.enrich.ai.AiSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Choose target folder for moving ([link] = false) or additional linking. */
data class PickerRequest(val ids: Set<Long>, val link: Boolean, val folderMove: Long? = null)

/** Input dialog for a name (create/rename folder). */
data class NameRequest(val title: String, val initial: String, val confirmLabel: String, val onConfirm: (String) -> Unit)

/** Ask for passphrase ([repeat] = enter twice for safety). */
class PassphraseRequest(val title: String, val text: String, val repeat: Boolean, val onConfirm: (CharArray) -> Unit)

/** Confirmation prompt for irreversible actions. */
data class ConfirmRequest(val title: String, val text: String, val confirmLabel: String, val onConfirm: () -> Unit)

/** Create a new bookmark (URL prefilled, e.g. from the clipboard). */
data class AddRequest(val url: String, val title: String = "")

/** Ongoing dragging of bookmarks onto a folder; position in window coordinates. */
data class DragState(val ids: Set<Long>, val x: Float, val y: Float, val label: String)

/** Image from the web (README); width/height in dp. */
class RemoteImage(val bitmap: ImageBitmap, val width: Float, val height: Float)

/** Open pairing request of the browser extension. */
class PairDialog(val client: String, val code: String, val result: java.util.concurrent.CompletableFuture<Boolean>)

@kotlinx.serialization.Serializable
data class ApiToken(val client: String, val hash: String, val created: Long)

enum class SyncTarget { None, Server, Ftp, Folder;

    val label: String get() = tr("syncTarget.${name.lowercase()}")
}

enum class ViewMode { List, Tree, Cards;

    val label: String get() = tr("view.${name.lowercase()}")
}

/** State and actions of the desktop app. All data lives in memory as a [Library] snapshot. */
class AppState(
    private val driver: SqlDriver,
    internal val db: BookmarksDb,
    private val mediaRoot: File = File(appDir, "media"),
    /** Database file (for sync and backups); null in tests with in-memory DB. */
    val dbFile: File? = null,
) : de.bookmarksplus.desktop.api.ApiBackend {
    val repo = BookmarkRepository(db)
    private val tagRepo = TagRepository(db)
    private val settings = SettingsRepository(db)

    // ------------------------------------------------------------ Language
    /** UI language (default: English). */
    var uiLang by mutableStateOf(de.bookmarksplus.i18n.UiLang.of(settings.get("uiLang")).also { de.bookmarksplus.i18n.I18n.lang = it })
        private set

    fun changeUiLang(lang: de.bookmarksplus.i18n.UiLang) {
        uiLang = lang; de.bookmarksplus.i18n.I18n.lang = lang; settings.put("uiLang", lang.code)
    }
    private val secrets = de.bookmarksplus.sync.Secrets(File(appDir, "secret.key"))

    /** Read secret (stored DPAPI-protected). */
    private fun secret(key: String): String = secrets.reveal(settings.get(key))
    private fun putSecret(key: String, value: String) = settings.put(key, secrets.protect(value))

    init {
        // protect plaintext secrets from older versions
        (listOf("githubToken") + de.bookmarksplus.enrich.ai.AiProvider.entries.map { "ai.key.${it.name}" }).forEach { k ->
            settings.get(k)?.takeIf { it.isNotEmpty() && !secrets.isProtected(it) }?.let { settings.put(k, secrets.protect(it)) }
        }
    }
    private val index = SearchIndex(driver)

    var library by mutableStateOf(Library.EMPTY)
        private set
    var filter by mutableStateOf(Filter())
    var viewMode by mutableStateOf(ViewMode.Cards)
        private set
    var accent by mutableStateOf(DEFAULT_ACCENT)
        private set
    var selection by mutableStateOf<Set<Long>>(emptySet())
    private var anchor: Long? = null
    var sidebarExpanded by mutableStateOf<Set<Long>>(emptySet())
    var treeExpanded by mutableStateOf<Set<Long>>(emptySet())
    /** Tag list in the sidebar: collapsed? / sorted by frequency instead of name? */
    var tagsCollapsed by mutableStateOf(settings.get("tagsCollapsed") == "true")
        private set
    var tagsByUsage by mutableStateOf(settings.get("tagsByUsage") == "true")
        private set
    fun toggleTagsCollapsed() { tagsCollapsed = !tagsCollapsed; settings.put("tagsCollapsed", tagsCollapsed.toString()) }
    fun toggleTagsByUsage() { tagsByUsage = !tagsByUsage; settings.put("tagsByUsage", tagsByUsage.toString()) }
    var message by mutableStateOf<String?>(null)
    /** Optional action for the message (e.g. "Undo"). */
    var messageAction by mutableStateOf<Pair<String, () -> Unit>?>(null)
    var editingId by mutableStateOf<Long?>(null)
    var editFocusTitle by mutableStateOf(false)
    var picker by mutableStateOf<PickerRequest?>(null)
    /** Bookmark for which the "Choose thumbnail" dialog is open, and sources currently loading (flathub, alternativeto, readme). */
    var thumbPicker by mutableStateOf<Long?>(null)
    var thumbLoading by mutableStateOf<Set<String>>(emptySet())
    internal val uploads = de.bookmarksplus.enrich.MediaStore(mediaRoot)
    var nameDialog by mutableStateOf<NameRequest?>(null)
    var confirm by mutableStateOf<ConfirmRequest?>(null)
    var addRequest by mutableStateOf<AddRequest?>(null)
    var drag by mutableStateOf<DragState?>(null)
    /** Folder drop targets for drag & drop (window coordinates). */
    val dropTargets = HashMap<Long, androidx.compose.ui.geometry.Rect>()
    var dropHover by mutableStateOf<Long?>(null)
    private val savedRepo = de.bookmarksplus.data.SavedSearchRepository(settings)
    var savedSearches by mutableStateOf(savedRepo.list())
        private set
    /** README in large view (dialog). */
    var readmeDialogId by mutableStateOf<Long?>(null)
    var showSettings by mutableStateOf(false)

    private var ftsCache: Pair<String, Map<String, Set<Long>>> = "" to emptyMap()

    // ------------------------------------------------------------ Enrichment
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var githubToken by mutableStateOf(secret("githubToken"))
        private set
    var thumbnails by mutableStateOf(settings.get("thumbnails") != "false")
        private set
    var darkScreenshots by mutableStateOf(settings.get("darkScreenshots") != "false")
    var realBrowser by mutableStateOf(settings.get("realBrowser") != "false")
    var visibleBrowser by mutableStateOf(settings.get("visibleBrowser") == "true")
    var browserWorkers by mutableStateOf(settings.get("browserWorkers")?.toIntOrNull() ?: 2)
        private set
    /** README thumbnail for GitHub cards: search range (lines) and minimum height (px, 0 = off). */
    var readmeImageLines by mutableStateOf(settings.get("readmeImageLines")?.toIntOrNull() ?: 60)
        private set
    var readmeImageMinHeight by mutableStateOf(settings.get("readmeImageMinHeight")?.toIntOrNull() ?: 200)
        private set
    var enrichProgress by mutableStateOf(EnrichProgress())
        private set
    /** Running or most recently finished batch; at most one runs at any time. */
    var batch by mutableStateOf<BatchStatus?>(null)
        internal set
    private var batchCancelRequested = false
    internal val dirty = AtomicBoolean(false)
    internal val dbLock = Mutex()
    val enricher: Enricher = Enricher(driver, db, mediaRoot, {
        EnrichSettings(githubToken = githubToken.ifBlank { null }, thumbnails = thumbnails, browserWorkers = browserWorkers, darkScreenshots = darkScreenshots, realBrowser = realBrowser, visibleBrowser = visibleBrowser,
            readmeImageLines = readmeImageLines, readmeImageMinHeight = readmeImageMinHeight)
    }, onChanged = { dirty.set(true) }, dbLock = dbLock, afterScrape = { id -> apps.local(id) })

    // ------------------------------------------------------------ AI
    var aiProvider by mutableStateOf(settings.get("ai.provider")?.let { v -> AiProvider.entries.firstOrNull { it.name == v } } ?: AiProvider.OpenRouter)
        private set
    var aiKey by mutableStateOf(secret("ai.key.${aiProvider.name}"))
        private set
    var aiModel by mutableStateOf(settings.get("ai.model.${aiProvider.name}") ?: aiProvider.defaultModel)
        private set
    var aiBaseUrl by mutableStateOf(settings.get("ai.baseUrl") ?: AiProvider.Custom.baseUrl)
        private set
    var aiTagModel by mutableStateOf(settings.get("ai.tagModel.${aiProvider.name}") ?: aiProvider.defaultTagModel)
        private set
    /** Display language for AI titles, descriptions, summaries and tags. */
    var contentLang by mutableStateOf(settings.get("contentLang")?.let { v -> de.bookmarksplus.data.ContentLang.entries.firstOrNull { it.name == v } } ?: if (uiLang == de.bookmarksplus.i18n.UiLang.DE) de.bookmarksplus.data.ContentLang.DE else de.bookmarksplus.data.ContentLang.EN)
        private set
    var aiAutoTags by mutableStateOf(settings.get("ai.autoTags") == "true")
        private set
    var aiParallel by mutableStateOf(settings.get("ai.parallel")?.toIntOrNull() ?: 4)
        private set
    var aiProgress by mutableStateOf(AiProgress())
        private set
    var aiModels by mutableStateOf<List<AiModel>>(emptyList())
        private set
    var aiTestMessage by mutableStateOf<String?>(null)
    /** IDs currently being evaluated individually (for the loading indicator in the detail area). */
    var aiBusy by mutableStateOf<Set<Long>>(emptySet())
        private set

    val aiConfig: AiConfig get() = AiConfig(aiProvider, aiKey, aiModel, aiBaseUrl)
    val ai: AiService = AiService(driver, db, {
        AiSettings(aiConfig, tagModel = aiTagModel, autoApplyTags = aiAutoTags, parallel = aiParallel)
    }, dbLock = dbLock, onChanged = { dirty.set(true) }, onApp = { id, app -> apps.applyAi(id, app) })
    val aiPendingCount: Int by derivedStateOf { library.items.count { it.aiAt == null || it.titleEn == null } }

    // ------------------------------------------------------------ Programs and games (Flathub, AlternativeTo)
    /** Page jobs for the browser extension (AlternativeTo via the real browser). */
    val extensionBridge = de.bookmarksplus.desktop.api.ExtensionBridge()
    override val bridge: de.bookmarksplus.desktop.api.ExtensionBridge get() = extensionBridge
    var appFlathub by mutableStateOf(settings.get("apps.flathub") != "false"); private set
    var appAlternativeTo by mutableStateOf(settings.get("apps.alternativeto") != "false"); private set
    var appAtInterval by mutableStateOf(settings.get("apps.atInterval")?.toIntOrNull() ?: 20); private set
    var appAtDailyLimit by mutableStateOf(settings.get("apps.atDaily")?.toIntOrNull() ?: 400); private set
    var appViaExtension by mutableStateOf(settings.get("apps.viaExtension") != "false"); private set
    var appViaBrowser by mutableStateOf(settings.get("apps.viaBrowser") != "false"); private set
    var appProgress by mutableStateOf(de.bookmarksplus.enrich.apps.AppProgress()); private set
    /** IDs whose app data is currently being loaded individually. */
    var appBusy by mutableStateOf<Set<Long>>(emptySet()); private set
    val apps: de.bookmarksplus.enrich.apps.AppService = de.bookmarksplus.enrich.apps.AppService(
        driver, db, mediaRoot,
        settings = {
            de.bookmarksplus.enrich.apps.AppSettings(
                flathub = appFlathub, alternativeTo = appAlternativeTo, alternativeToIntervalSec = appAtInterval,
                alternativeToDailyLimit = appAtDailyLimit, viaExtension = appViaExtension, viaBrowser = appViaBrowser,
            )
        },
        // name finding with the cheap tag model
        aiConfig = { aiConfig.takeIf { it.usable }?.let { it.copy(model = aiTagModel.ifBlank { aiModel }) to null } },
        dbLock = dbLock, bridge = { extensionBridge }, browserHtml = { url -> enricher.browserHtml(url) },
        githubLicense = { o, r -> enricher.githubLicense(o, r) },
        onChanged = { dirty.set(true) },
    )
    val appCount: Int by derivedStateOf { library.items.count { it.app.isApp } }

    fun changeAppSettings(
        flathub: Boolean? = null, alternativeTo: Boolean? = null, interval: Int? = null, daily: Int? = null,
        viaExtension: Boolean? = null, viaBrowser: Boolean? = null,
    ) {
        flathub?.let { appFlathub = it; settings.put("apps.flathub", it.toString()) }
        alternativeTo?.let { appAlternativeTo = it; settings.put("apps.alternativeto", it.toString()) }
        interval?.let { appAtInterval = it.coerceIn(5, 600); settings.put("apps.atInterval", appAtInterval.toString()) }
        daily?.let { appAtDailyLimit = it.coerceIn(1, 5000); settings.put("apps.atDaily", appAtDailyLimit.toString()) }
        viaExtension?.let { appViaExtension = it; settings.put("apps.viaExtension", it.toString()) }
        viaBrowser?.let { appViaBrowser = it; settings.put("apps.viaBrowser", it.toString()) }
    }

    /** Maintenance: app names (heuristic + AI), Flathub and AlternativeTo for all candidates or [ids]. */
    fun updateApps(ids: Collection<Long>? = null, force: Boolean = false) {
        if (ids != null && ids.size == 1) return appsFor(ids.first())
        val started = runBatch(BatchKind.Apps, tr("batch.appData"), tr("apps.noneFound")) {
            apps.start(ids?.toList(), force) { dirty.set(true) }
            apps.progress.value.running
        }
        if (started && !aiConfig.usable) notify(tr("apps.heuristicOnly"))
    }

    fun stopApps() = cancelBatch(BatchKind.Apps)

    /** One bookmark: reload app data immediately. */
    fun appsFor(id: Long) {
        appBusy = appBusy + id
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { apps.processOne(id) } }.onFailure { message = tr("apps.errorPrefix", it.message) }
            appBusy = appBusy - id
            reload()
            val a = library.itemsById[id]?.app
            notify(
                when {
                    a == null || !a.isApp -> tr("apps.noData")
                    else -> listOfNotNull(
                        a.name, a.flathubUrl?.let { "Flathub ✓" }, a.alternativetoUrl?.let { "AlternativeTo ✓" },
                        appProgress.alternativeToPausedUntil?.takeIf { it > System.currentTimeMillis() }?.let { tr("apps.alternativeToPaused") },
                    ).joinToString(" · ")
                },
            )
        }
    }

    /** Save app fields edited by the user. A changed name counts as user input (takes precedence over all sources). */
    fun saveApp(id: Long, a: de.bookmarksplus.data.AppInfo) {
        val old = library.anyItem(id)?.app ?: de.bookmarksplus.data.AppInfo.EMPTY
        fun t(v: String?) = v?.trim()?.ifEmpty { null }
        val name = t(a.name)
        val source = when {
            name == old.name -> old.nameSource
            name == null -> "none"
            else -> "user"
        }
        val flathub = t(a.flathubUrl)?.let { u -> de.bookmarksplus.data.AppInfo.flathubIdOf(u)?.let(de.bookmarksplus.data.AppInfo::flathubUrl) ?: u }
        val at = t(a.alternativetoUrl)?.let { u -> de.bookmarksplus.data.AppInfo.alternativetoSlugOf(u)?.let(de.bookmarksplus.data.AppInfo::alternativetoUrl) ?: u }
        db.appQueries.updateApp(
            name, source, de.bookmarksplus.data.AppInfo.join(a.platforms), de.bookmarksplus.data.AppInfo.join(a.systems), t(a.license),
            t(a.winget), t(a.choco), t(a.brew), t(a.npm), t(a.scoop), flathub, at, id,
        )
        // GitHub repo: normalize the address, store it and load GitHub data (stars, README, avatar …)
        val repo = t(a.repoUrl)?.let { u -> Urls.githubRepo(u)?.let { (o, r) -> "https://github.com/$o/$r" } ?: u }
        if (repo != old.repoUrl) {
            db.enrichQueries.setRepoUrl(repo, id)
            if (repo != null) scope.launch(Dispatchers.IO) { runCatching { enricher.enrich(id, force = true) }; dirty.set(true) }
        }
        // query new directory links immediately
        if ((flathub != null && flathub != old.flathubUrl) || (at != null && at != old.alternativetoUrl)) {
            scope.launch(Dispatchers.IO) {
                if (flathub != null && flathub != old.flathubUrl) runCatching { apps.flathub(id, force = true) }
                if (at != null && at != old.alternativetoUrl) runCatching { apps.alternativeTo(id, force = true) }
                dirty.set(true)
            }
        }
    }

    // ------------------------------------------------------------ Link check
    val linkChecker = de.bookmarksplus.enrich.LinkChecker(db, dbLock, onChanged = { dirty.set(true) })
    var linkProgress by mutableStateOf(de.bookmarksplus.enrich.LinkCheckProgress())
        private set
    val pendingCount: Int by derivedStateOf { library.items.count { it.scrapedAt == null } }

    /** Bookmarks and – for a free-text search – folders, ranked by relevance. */
    val searchResult: de.bookmarksplus.data.SearchResult by derivedStateOf {
        FilterEngine.search(library, filter, System.currentTimeMillis() / 1000, ftsHits(filter.text))
    }

    val results: List<Item> by derivedStateOf { searchResult.items }

    val folderResults: List<de.bookmarksplus.data.FolderHit> by derivedStateOf { searchResult.folders }

    val resultIds: Set<Long> by derivedStateOf { results.mapTo(HashSet()) { it.id } }

    private val iconBytes = HashMap<String, ByteArray>()
    private val iconCache = HashMap<String, ImageBitmap?>()
    private val imageCache = ConcurrentHashMap<String, ImageBitmap>()
    private val missingImages = ConcurrentHashMap.newKeySet<String>()

    init {
        index.ensure()
        settings.get("accent")?.toLongOrNull(16)?.let { accent = Color(it.toInt()) }
        settings.get("view")?.let { v -> ViewMode.entries.firstOrNull { it.name == v } }?.let { viewMode = it }
        settings.get("sort")?.let { v -> SortField.entries.firstOrNull { it.name == v } }?.let { filter = filter.copy(sort = it) }
        settings.get("sortDesc")?.let { filter = filter.copy(descending = it == "true") }
        if (settings.get("normVersion") != de.bookmarksplus.util.Urls.NORM_VERSION.toString()) {
            repo.renormalize()
            settings.put("normVersion", de.bookmarksplus.util.Urls.NORM_VERSION.toString())
        }
        reload()
        if (index.count() != library.items.size.toLong()) index.rebuild()
        // only after the constructor (sync fields are declared further below)
        scope.launch { delay(100); dailyLocalBackup(); startSyncLoop() }
        scope.launch {
            linkChecker.progress.collect { p ->
                val was = linkProgress.running
                linkProgress = p
                if (was && !p.running) reload()
                batchProgress(BatchKind.Links, p.running, p.done, p.total, p.current) { "${p.dead} tot · ${p.redirects} Weiterleitungen" }
            }
        }
        scope.launch {
            enricher.progress.collect { p ->
                val was = enrichProgress.running
                enrichProgress = p
                if (was && !p.running) reload()
                batchProgress(BatchKind.Enrich, p.running, p.done, p.total, p.current, p.errors)
            }
        }
        scope.launch {
            apps.progress.collect { p ->
                val was = appProgress.running
                appProgress = p
                if (was && !p.running) reload()
                batchProgress(BatchKind.Apps, p.running, p.done, p.total, p.current, step = p.phase) {
                    "${p.named} Namen · ${p.flathub} × Flathub · ${p.alternativeTo} × AlternativeTo"
                }
            }
        }
        scope.launch {
            ai.progress.collect { p ->
                val wasRunning = aiProgress.running
                aiProgress = p
                if (wasRunning && !p.running) {
                    reload()
                    p.fatal?.let { message = tr("ai.cancelled", it) }
                }
                batchProgress(BatchKind.Ai, p.running, p.done, p.total, p.current, p.errors) { p.fatal?.let { tr("batch.cancelledWith", it) } }
            }
        }
        // during enrichment refresh the view regularly (not for every single entry)
        scope.launch {
            while (true) {
                delay(1500)
                if (dirty.getAndSet(false)) reload()
            }
        }
    }

    // ------------------------------------------------------------ Batches (status display bottom left)
    val batchRunning: Boolean get() = batch?.running == true

    /**
     * Starts a batch if none is running – otherwise only a notice. [start] kicks off the service and
     * returns whether it really started (otherwise there was nothing to do → [empty] as notice).
     */
    private fun runBatch(kind: BatchKind, label: String, empty: String? = null, start: () -> Boolean): Boolean {
        batch?.takeIf { it.running }?.let {
            notify(tr("batch.stillRunning", it.label, it.done, it.total))
            return false
        }
        batchCancelRequested = false
        if (!start()) {
            empty?.let { notify(it) }
            return false
        }
        val total = when (kind) {
            BatchKind.Enrich -> enricher.progress.value.total
            BatchKind.Links -> linkChecker.progress.value.total
            BatchKind.Ai -> ai.progress.value.total
            BatchKind.Apps -> apps.progress.value.total
            BatchKind.Sync -> 0
        }
        batch = BatchStatus(kind, label, total = total)
        return true
    }

    /** Take over progress of a service into the batch; [summary] delivers the result at the end. */
    private fun batchProgress(
        kind: BatchKind, running: Boolean, done: Int, total: Int, current: String?, errors: Int = 0, step: String? = null,
        summary: () -> String? = { null },
    ) {
        val b = batch?.takeIf { it.kind == kind && it.running } ?: return
        batch = if (running) b.copy(done = done, total = total, current = current, errors = errors, step = step)
        else b.copy(
            done = done, total = total, current = null, errors = errors, step = null, paused = false,
            phase = if (batchCancelRequested) BatchPhase.Cancelled else BatchPhase.Done,
            summary = summary() ?: b.summary, endedAt = System.currentTimeMillis(),
        )
    }

    /** Result that a service reports only after the run (e.g. number of loaded READMEs). */
    private fun batchSummary(kind: BatchKind, text: String) {
        batch?.takeIf { it.kind == kind }?.let { batch = it.copy(summary = text) }
    }

    private fun gateOf(kind: BatchKind) = when (kind) {
        BatchKind.Enrich -> enricher.pause
        BatchKind.Links -> linkChecker.pause
        BatchKind.Ai -> ai.pause
        BatchKind.Apps -> apps.pause
        BatchKind.Sync -> null
    }

    fun pauseBatch() {
        val b = batch?.takeIf { it.running && !it.paused } ?: return
        if (b.kind == BatchKind.Sync) syncControl?.pause() else gateOf(b.kind)?.pause()
        batch = b.copy(paused = true)
    }

    fun resumeBatch() {
        val b = batch?.takeIf { it.running && it.paused } ?: return
        if (b.kind == BatchKind.Sync) syncControl?.resume() else gateOf(b.kind)?.resume()
        batch = b.copy(paused = false)
    }

    /** Cancel running batch (only if it belongs to [kind], if given). */
    fun cancelBatch(kind: BatchKind? = null) {
        val b = batch?.takeIf { it.running && (kind == null || it.kind == kind) }
        val k = b?.kind ?: kind ?: return
        if (b != null) batchCancelRequested = true
        when (k) {
            BatchKind.Enrich -> enricher.stop()
            BatchKind.Links -> linkChecker.stop()
            BatchKind.Ai -> ai.stop()
            BatchKind.Apps -> apps.stop()
            BatchKind.Sync -> syncControl?.cancel()
        }
    }

    /** Remove finished batch from the status display. */
    fun dismissBatch() {
        if (batch?.running == false) batch = null
    }

    fun enrichAll() {
        runBatch(BatchKind.Enrich, tr("batch.enrich"), tr("enrich.allDone")) {
            enricher.start()
            enricher.progress.value.running
        }
    }

    fun enrich(ids: Collection<Long>, force: Boolean = true) {
        if (ids.size == 1 && batchRunning) {
            // load a single bookmark directly alongside the running batch
            val id = ids.first()
            notify(tr("reload.running"))
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { enricher.enrich(id, force) } }.onFailure { message = tr("reload.failed", it.message) }
                reload()
            }
            return
        }
        runBatch(BatchKind.Enrich, tr("reload.label")) {
            enricher.start(ids.toList(), force)
            enricher.progress.value.running
        }
    }

    /** Only re-capture the thumbnail (cookie banners are hidden). */
    fun refreshThumbnails(ids: Collection<Long>) {
        val list = ids.toList()
        if (list.size == 1 && batchRunning) {
            notify(tr("thumb.recreating"))
            scope.launch {
                val ok = runCatching { withContext(Dispatchers.IO) { enricher.refreshThumbnail(list.first()) } }.getOrDefault(false)
                reload()
                notify(if (ok) tr("thumb.updated") else tr("thumb.notUpdated"))
            }
            return
        }
        runBatch(BatchKind.Enrich, tr("batch.thumbnails")) {
            enricher.refreshThumbnails(list) { ok ->
                dirty.set(true)
                scope.launch {
                    reload()
                    batchSummary(BatchKind.Enrich, if (ok == list.size) tr("thumb.nUpdated", ok) else tr("thumb.nOfUpdated", ok, list.size))
                }
            }
            enricher.progress.value.running
        }
    }

    fun stopEnrich() = cancelBatch(BatchKind.Enrich)

    fun changeGithubToken(token: String) {
        githubToken = token.trim()
        putSecret("githubToken", githubToken)
    }

    fun changeThumbnails(on: Boolean) {
        thumbnails = on
        settings.put("thumbnails", on.toString())
    }

    fun changeDarkScreenshots(on: Boolean) {
        darkScreenshots = on
        settings.put("darkScreenshots", on.toString())
    }

    fun changeRealBrowser(on: Boolean) {
        realBrowser = on
        settings.put("realBrowser", on.toString())
    }

    fun changeVisibleBrowser(on: Boolean) {
        visibleBrowser = on
        settings.put("visibleBrowser", on.toString())
    }

    fun changeReadmeImage(lines: Int? = null, minHeight: Int? = null) {
        lines?.let { readmeImageLines = it; settings.put("readmeImageLines", it.toString()) }
        minHeight?.let { readmeImageMinHeight = it; settings.put("readmeImageMinHeight", it.toString()) }
    }

    /** Load missing READMEs of all GitHub projects. */
    fun loadMissingReadmes() {
        runBatch(BatchKind.Enrich, tr("batch.readmes"), tr("readme.allPresent")) {
            val n = enricher.missingReadmeCount()
            if (n > 0L) enricher.loadMissingReadmes { loaded ->
                dirty.set(true)
                scope.launch {
                    reload()
                    batchSummary(BatchKind.Enrich, tr("readme.loaded", loaded, n) + if (loaded < n) tr("readme.loadedRest") else "")
                }
            }
            enricher.progress.value.running
        }
    }

    /** Reload avatars (GitHub) and website icons of the GitHub projects. */
    fun loadMissingIcons() {
        runBatch(BatchKind.Enrich, tr("batch.icons"), tr("icons.allPresent")) {
            val n = enricher.missingIconCount()
            if (n > 0) enricher.loadMissingIcons { loaded ->
                dirty.set(true)
                scope.launch { reload(); batchSummary(BatchKind.Enrich, tr("icons.loaded", loaded, n)) }
            }
            enricher.progress.value.running
        }
    }

    /** Regenerate GitHub cards of all repos from the stored README. */
    fun rerenderGithubCards() {
        runBatch(BatchKind.Enrich, tr("batch.githubPreviews"), tr("previews.noReadme")) {
            enricher.rerenderGithubCards { made ->
                dirty.set(true)
                scope.launch { reload(); batchSummary(BatchKind.Enrich, if (made == 0) tr("previews.noImages") else tr("previews.created", made)) }
            }
            enricher.progress.value.running
        }
    }

    fun changeBrowserWorkers(n: Int) {
        browserWorkers = n
        settings.put("browserWorkers", n.toString())
    }

    // ------------------------------------------------------------ AI actions

    fun aiAll() {
        if (!aiConfig.usable) { showSettings = true; message = tr("ai.setupFirst"); return }
        runBatch(BatchKind.Ai, "KI-Auswertung", tr("ai.nothingToDo")) {
            ai.start()
            ai.progress.value.running
        }
    }

    /** Evaluates [ids] (again). Single entries immediately, several as a background run. */
    fun aiFor(ids: Collection<Long>) {
        if (!aiConfig.usable) { showSettings = true; message = tr("ai.setupFirst"); return }
        if (ids.size > 1) {
            runBatch(BatchKind.Ai, "KI-Auswertung") {
                ai.start(ids.toList())
                ai.progress.value.running
            }
        } else {
            aiBusy = aiBusy + ids
            scope.launch {
                ids.forEach { id ->
                    runCatching { withContext(Dispatchers.IO) { ai.processOne(id) } }
                        .onFailure { message = "KI: ${it.message}" }
                }
                aiBusy = aiBusy - ids.toSet()
                reload()
            }
        }
    }

    fun stopAi() = cancelBatch(BatchKind.Ai)

    fun changeAiProvider(p: AiProvider) {
        aiProvider = p
        settings.put("ai.provider", p.name)
        aiKey = secret("ai.key.${p.name}")
        aiModel = settings.get("ai.model.${p.name}") ?: p.defaultModel
        aiTagModel = settings.get("ai.tagModel.${p.name}") ?: p.defaultTagModel
        aiModels = emptyList()
        aiTestMessage = null
    }

    fun changeAiKey(key: String) { aiKey = key.trim(); putSecret("ai.key.${aiProvider.name}", aiKey) }
    fun changeAiModel(model: String) { aiModel = model.trim(); settings.put("ai.model.${aiProvider.name}", aiModel) }
    fun changeAiBaseUrl(url: String) { aiBaseUrl = url.trim(); settings.put("ai.baseUrl", aiBaseUrl) }
    fun changeAiTagModel(model: String) { aiTagModel = model.trim(); settings.put("ai.tagModel.${aiProvider.name}", aiTagModel) }
    fun changeContentLang(lang: de.bookmarksplus.data.ContentLang) { contentLang = lang; settings.put("contentLang", lang.name); reload() }
    fun changeAiAutoTags(on: Boolean) { aiAutoTags = on; settings.put("ai.autoTags", on.toString()) }
    fun changeAiParallel(n: Int) { aiParallel = n; settings.put("ai.parallel", n.toString()) }

    fun loadAiModels() {
        val cfg = aiConfig
        aiTestMessage = tr("ai.loadingModels")
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ai.models(cfg) } }
                .onSuccess { aiModels = it; aiTestMessage = tr("ai.modelsLoaded", it.size) }
                .onFailure { aiTestMessage = tr("common.errorWith", it.message) }
        }
    }

    fun testAi() {
        val cfg = aiConfig
        aiTestMessage = tr("ai.testing")
        scope.launch {
            aiTestMessage = runCatching { withContext(Dispatchers.IO) { ai.test(cfg) } }.getOrElse { tr("common.errorWith", it.message) }
        }
    }

    /** Assign suggested tags to the bookmarks (creates missing tags). */
    fun acceptTags(itemIds: Collection<Long>, only: String? = null) {
        val byName = HashMap<String, Long>()
        db.transaction {
            itemIds.forEach { id ->
                val item = library.itemsById[id] ?: return@forEach
                library.openTagSuggestions(item).filter { only == null || it.equals(only, ignoreCase = true) }.forEach { name ->
                    val tid = byName.getOrPut(name.lowercase()) { tagRepo.getOrCreate(name, TagRepository.colorFor(name)) }
                    tagRepo.add(listOf(id), tid)
                }
            }
        }
        reload()
    }

    /** Discard a tag suggestion. */
    fun dismissTag(itemId: Long, name: String) {
        val item = library.itemsById[itemId] ?: return
        val rest = item.aiTags.filterNot { it.equals(name, ignoreCase = true) }
        db.aiQueries.setAiTags(Library.encodeTagList(rest), itemId)
        reload()
    }

    /** Move bookmark to the suggested folder ([link] = additionally link there). */
    fun acceptFolder(itemIds: Collection<Long>, link: Boolean = false) {
        var n = 0
        val created = HashMap<String, Long>()
        itemIds.forEach { id ->
            val item = library.itemsById[id] ?: return@forEach
            val path = library.openFolderSuggestion(item) ?: return@forEach
            val fid = library.folderByPath(path) ?: created.getOrPut(de.bookmarksplus.data.Library.normPath(path)) { repo.ensureFolderPath(path) }
            if (link) repo.addPlacement(id, fid) else repo.moveTo(id, fid)
            n++
        }
        reload()
        message = if (link) tr("move.linked", n) else tr("move.moved", n)
    }

    fun dismissFolder(itemIds: Collection<Long>) {
        db.transaction { itemIds.forEach { db.aiQueries.setAiFolder(null, it) } }
        reload()
    }

    /** Discard AI title → show original title again. */
    fun dismissAiTitle(itemId: Long) {
        db.aiQueries.setAiTitle(null, itemId)
        reload()
    }

    fun readme(id: Long): String? = db.bookmarkQueries.readmeOf(id).executeAsOneOrNull()?.readme

    fun close() {
        api?.stop()
        syncLoop?.cancel()
        linkChecker.close()
        ai.close()
        apps.close()
        enricher.close()
    }

    fun reload() {
        library = Library.load(db, contentLang)
        iconBytes.clear()
        db.bookmarkQueries.allIcons().executeAsList().forEach { iconBytes[it.hash] = it.data_ }
        ftsCache = "" to emptyMap()
        selection = selection.filterTo(HashSet()) { it in library.itemsById || it in library.trashById }
        if (editingId != null && editingId !in selection) editingId = null
        // expand top level (Vivaldi/Chrome put everything into one root folder like "Bookmarks")
        if (sidebarExpanded.isEmpty()) library.rootId?.let { root ->
            val top = library.childrenOf(root)
            sidebarExpanded = top.map { it.id }.toSet()
            if (treeExpanded.isEmpty()) treeExpanded = sidebarExpanded
        }
    }

    private fun ftsHits(text: String): Map<String, Set<Long>> {
        if (ftsCache.first == text) return ftsCache.second
        val terms = ParsedQuery.parse(text).terms
        val hits = if (terms.isEmpty()) emptyMap() else index.searchEach(terms)
        ftsCache = text to hits
        return hits
    }

    // ------------------------------------------------------------ Images

    fun favicon(hash: String?): ImageBitmap? {
        if (hash == null) return null
        return iconCache.getOrPut(hash) { iconBytes[hash]?.let(::decode) }
    }

    /** Already loaded image (key contains the scrape time so that reloaded images are replaced). */
    fun cachedImage(key: String): ImageBitmap? = imageCache[key]

    /** Loads and decodes an image from disk (for background threads). */
    fun loadImage(key: String, path: String): ImageBitmap? {
        imageCache[key]?.let { return it }
        if (key in missingImages) return null
        val bmp = File(path).takeIf { it.isFile }?.readBytes()?.let(::decode)
        if (bmp == null) missingImages += key else imageCache[key] = bmp
        return bmp
    }

    private val http by lazy { de.bookmarksplus.enrich.Http() }
    private val remoteImages = ConcurrentHashMap<String, RemoteImage>()
    private val remoteMissing = ConcurrentHashMap.newKeySet<String>()

    fun cachedRemoteImage(url: String): RemoteImage? = remoteImages[url]

    /** Loads a README image (PNG/JPEG/GIF/WebP or SVG badge) – for background threads. */
    fun loadRemoteImage(url: String): RemoteImage? {
        remoteImages[url]?.let { return it }
        if (url in remoteMissing || remoteImages.size > 400) return null
        val res = http.get(url, maxBytes = 6 shl 20, accept = de.bookmarksplus.enrich.Http.ACCEPT_IMAGE)
        val bytes = res.body?.takeIf { res.ok }
        val img = bytes?.let { b ->
            val head = String(b, 0, minOf(b.size, 300)).trimStart()
            if (head.startsWith("<svg") || head.startsWith("<?xml")) rasterizeSvg(b)
            else decode(b)?.let { RemoteImage(it, it.width.toFloat(), it.height.toFloat()) }
        }
        if (img == null) remoteMissing += url else remoteImages[url] = img
        return img
    }

    private fun rasterizeSvg(bytes: ByteArray): RemoteImage? = runCatching {
        val dom = org.jetbrains.skia.svg.SVGDOM(org.jetbrains.skia.Data.makeFromBytes(bytes))
        val root = dom.root ?: return null
        var w = root.width.value
        var h = root.height.value
        if (w <= 1f || h <= 1f || root.width.unit == org.jetbrains.skia.svg.SVGLengthUnit.PERCENTAGE) {
            val vb = root.viewBox
            w = vb?.width ?: 120f; h = vb?.height ?: 20f
        }
        w = w.coerceIn(1f, 1600f); h = h.coerceIn(1f, 1600f)
        dom.setContainerSize(w, h)
        val scale = 2f
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul((w * scale).toInt(), (h * scale).toInt())
        surface.canvas.scale(scale, scale)
        dom.render(surface.canvas)
        RemoteImage(surface.makeImageSnapshot().toComposeImageBitmap(), w, h)
    }.getOrNull()

    private fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

    // ------------------------------------------------------------ Selection

    fun select(id: Long, toggle: Boolean = false, range: Boolean = false, ordered: List<Long> = emptyList()) {
        val a = anchor
        selection = when {
            range && a != null && ordered.isNotEmpty() -> {
                val i = ordered.indexOf(a)
                val j = ordered.indexOf(id)
                if (i < 0 || j < 0) setOf(id) else ordered.subList(minOf(i, j), maxOf(i, j) + 1).toSet()
            }
            toggle -> if (id in selection) selection - id else selection + id
            else -> setOf(id)
        }
        if (!range) anchor = id
    }

    fun clearSelection() {
        selection = emptySet()
        anchor = null
    }

    // ------------------------------------------------------------ Settings

    fun changeView(mode: ViewMode) {
        viewMode = mode
        settings.put("view", mode.name)
    }

    fun changeAccent(color: Color) {
        accent = color
        settings.put("accent", Integer.toHexString(color.toArgb()))
    }

    fun changeSort(field: SortField, descending: Boolean) {
        // while searching, the search order changes (not stored, relevance is the default)
        if (filter.isTextSearch) { filter = filter.copy(searchSort = field, searchDescending = descending); return }
        filter = filter.copy(sort = field, descending = descending)
        settings.put("sort", field.name)
        settings.put("sortDesc", descending.toString())
    }

    fun resetFilters() {
        filter = Filter(folderId = filter.folderId, includeSubfolders = filter.includeSubfolders, sort = filter.sort, descending = filter.descending, scope = filter.scope)
    }

    /** Opens a folder found by the search (search text is cleared, sidebar shows the folder). */
    fun openFolder(folderId: Long) {
        val ancestors = generateSequence(library.folders[folderId]?.parentId) { library.folders[it]?.parentId }.toSet()
        sidebarExpanded = sidebarExpanded + ancestors
        filter = filter.copy(folderId = folderId, text = "", trash = false)
    }

    // ------------------------------------------------------------ Tags

    fun toggleTag(itemIds: Collection<Long>, tagId: Long) {
        val all = itemIds.all { tagId in library.tagsOf[it].orEmpty() }
        if (all) tagRepo.remove(itemIds, tagId) else tagRepo.add(itemIds, tagId)
        reload()
    }

    fun addNewTag(itemIds: Collection<Long>, name: String) {
        val clean = name.trim().removePrefix("#")
        if (clean.isEmpty()) return
        val id = tagRepo.getOrCreate(clean, TagRepository.colorFor(clean))
        if (itemIds.isNotEmpty()) tagRepo.add(itemIds, id)
        reload()
    }

    fun recolorTag(tagId: Long, color: Long) {
        val t = library.tagsById[tagId] ?: return
        tagRepo.update(tagId, t.name, color)
        reload()
    }

    /** Renames a tag; if the new name already exists, both tags are merged. */
    fun renameTag(tagId: Long, newName: String) {
        val t = library.tagsById[tagId] ?: return
        val clean = newName.trim().removePrefix("#").trim()
        if (clean.isEmpty() || clean == t.name) return
        val other = library.tags.firstOrNull { it.id != tagId && it.name.equals(clean, ignoreCase = true) }
        if (other == null) {
            tagRepo.update(tagId, clean, t.color)
            notify(tr("tag.renamed", clean))
        } else {
            val items = library.tagsOf.filterValues { tagId in it }.keys
            tagRepo.add(items, other.id)
            tagRepo.delete(tagId)
            if (tagId in filter.tagIds) filter = filter.copy(tagIds = filter.tagIds - tagId + other.id)
            notify(tr("tag.merged", t.name, other.name, items.size))
        }
        reload()
    }

    fun deleteTag(tagId: Long) {
        tagRepo.delete(tagId)
        filter = filter.copy(tagIds = filter.tagIds - tagId)
        reload()
    }

    // ------------------------------------------------------------ Import / Export / Open

    // ------------------------------------------------------------ Manage

    fun notify(text: String, action: Pair<String, () -> Unit>? = null) {
        message = text
        messageAction = action
    }

    /** Target of an action from the context menu: the whole selection if the entry belongs to it. */
    fun targetsFor(id: Long): Set<Long> = if (id in selection) selection else setOf(id)

    fun trash(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val list = ids.toList()
        repo.trash(list)
        editingId = null
        reload()
        notify(tr("trash.moved", list.size), tr("common.undo") to { restore(list) })
    }

    fun restore(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        repo.restore(ids)
        reload()
        notify("${ids.size} wiederhergestellt")
    }

    fun deleteForever(ids: Collection<Long>) {
        val list = ids.toList()
        if (list.isEmpty()) return
        confirm = ConfirmRequest(
            tr("delete.confirmTitle"),
            tr("delete.confirmText", list.size),
            tr("delete.confirm"),
        ) {
            list.forEach { id -> library.anyItem(id)?.let(::deleteMedia) }
            repo.deleteForever(list)
            index.rebuild()
            reload()
            notify(tr("delete.done", list.size))
        }
    }

    fun emptyTrash() {
        val n = library.trash.size
        if (n == 0) return
        confirm = ConfirmRequest(
            tr("trash.emptyTitle"),
            tr("trash.emptyText", n),
            tr("trash.empty"),
        ) {
            library.trash.forEach(::deleteMedia)
            val c = repo.emptyTrash()
            index.rebuild()
            reload()
            notify(tr("trash.emptied", c))
        }
    }

    private fun deleteMedia(item: Item) {
        (listOfNotNull(item.thumbnailPath, item.cardImagePath, item.customThumbPath) + item.shots.map { it.path })
            .forEach { runCatching { File(it).delete() } }
    }

    /** Del key: to the trash, or permanently delete when in the trash. */
    fun deleteSelection() {
        if (selection.isEmpty()) return
        if (filter.trash) deleteForever(selection) else trash(selection)
    }

    /** Only remove the link in [folderId]; the bookmark stays in the other folders. */
    fun removeFromFolder(itemId: Long, folderId: Long) {
        val p = library.placementsByItem[itemId]?.firstOrNull { it.folderId == folderId } ?: return
        repo.deletePlacement(p.id)
        reload()
        notify(tr("folder.removedFrom", library.folders[folderId]?.title))
    }

    fun startEdit(id: Long, focusTitle: Boolean = false) {
        selection = setOf(id)
        editingId = id
        editFocusTitle = focusTitle
    }

    /** F2: rename selection. */
    fun renameSelection() {
        selection.singleOrNull()?.takeIf { it in library.itemsById }?.let { startEdit(it, focusTitle = true) }
    }

    fun saveEdit(id: Long, title: String, url: String, description: String, summary: String, readme: String?, app: de.bookmarksplus.data.AppInfo? = null) {
        val old = library.anyItem(id) ?: return
        if (app != null && app != old.app) saveApp(id, app)
        repo.edit(
            id,
            title = title.takeIf { it.trim() != old.title },
            url = url.takeIf { it.trim() != old.url },
            description = description.takeIf { it.trim() != (old.description ?: "") },
            summary = summary.takeIf { it.trim() != (old.summary ?: "") },
            readme = readme,
        )
        index.update(id)
        editingId = null
        reload()
        notify(tr("common.saved"))
    }

    fun moveItems(ids: Collection<Long>, folderId: Long, link: Boolean) {
        ids.forEach { if (link) repo.addPlacement(it, folderId) else repo.moveTo(it, folderId) }
        reload()
        val name = library.pathString(folderId).ifEmpty { tr("folder.root") }
        notify(if (link) tr("move.linkedTo", ids.size, name) else tr("move.movedTo", ids.size, name))
    }

    fun newFolder(parentId: Long?) {
        val parent = parentId ?: library.rootId ?: repo.rootFolderId()
        nameDialog = NameRequest(tr("folder.new"), "", tr("common.create")) { name ->
            if (name.isNotBlank()) {
                val id = repo.createFolder(parent, name)
                sidebarExpanded = sidebarExpanded + parent
                reload()
                filter = filter.copy(folderId = id, trash = false)
            }
        }
    }

    fun renameFolder(id: Long) {
        val f = library.folders[id] ?: return
        nameDialog = NameRequest(tr("folder.rename"), f.title, tr("common.rename")) { name ->
            if (name.isNotBlank()) { repo.renameFolder(id, name); reload() }
        }
    }

    fun deleteFolder(id: Long) {
        val f = library.folders[id] ?: return
        val n = library.recursiveCounts[id] ?: 0
        val subs = library.descendants(id).size - 1
        confirm = ConfirmRequest(
            tr("folder.deleteTitle", f.title),
            buildString {
                append(tr("folder.deleteText1"))
                if (subs > 0) append(tr("folder.deleteText2", subs))
                append(tr("folder.deleteText3"))
                if (n > 0) append(tr("folder.deleteText4", n))
            },
            tr("folder.delete"),
        ) {
            if (filter.folderId in library.descendants(id)) filter = filter.copy(folderId = null)
            val trashed = repo.deleteFolder(id)
            reload()
            notify(tr("folder.deleted", trashed))
        }
    }

    fun moveFolder(id: Long, newParent: Long) {
        repo.moveFolder(id, newParent)
        sidebarExpanded = sidebarExpanded + newParent
        reload()
    }

    // ------------------------------------------------------------ Link check, duplicates, inbox

    fun checkLinks(ids: Collection<Long>? = null) {
        runBatch(BatchKind.Links, if (ids == null) tr("batch.checkAllLinks") else tr("batch.checkLinks"), tr("links.nothing")) {
            linkChecker.start(ids?.toList())
            linkChecker.progress.value.running
        }
    }

    fun stopLinkCheck() = cancelBatch(BatchKind.Links)

    /** Set or clear "Don't check anymore"; when setting, the old status also disappears. */
    fun setLinkIgnore(ids: Collection<Long>, ignore: Boolean) {
        val list = ids.toList()
        if (list.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            dbLock.withLock { db.transaction { list.forEach { db.bookmarkQueries.setLinkIgnore(if (ignore) 1L else 0L, it) } } }
            dirty.set(true)
            reload()
            notify(
                if (ignore) (if (list.size == 1) tr("links.ignoreOne") else tr("links.ignoreMany", list.size))
                else (if (list.size == 1) tr("links.unignoreOne") else tr("links.unignoreMany", list.size)),
            )
        }
    }

    /** Discard link status (e.g. "blocked" by bot protection): the entry counts as unchecked again. */
    fun clearLinkStatus(ids: Collection<Long>) {
        val list = ids.toList()
        if (list.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            dbLock.withLock { db.transaction { list.forEach { db.bookmarkQueries.clearLinkStatus(it) } } }
            dirty.set(true)
            reload()
            notify(if (list.size == 1) tr("links.clearedOne") else tr("links.clearedMany", list.size))
        }
    }

    /** Adopt redirect target as the new URL. */
    fun applyRedirects(ids: Collection<Long>) {
        var n = 0
        ids.mapNotNull { library.itemsById[it] }.filter { it.health == de.bookmarksplus.data.LinkHealth.Redirect && it.finalUrl != null }.forEach {
            repo.edit(it.id, url = it.finalUrl)
            db.enrichQueries.setFetchResult(200, System.currentTimeMillis() / 1000, null, it.id)
            index.update(it.id)
            n++
        }
        reload()
        notify(tr("links.urlsUpdated", n))
    }

    /** Merge duplicates into [keepId] (others → trash, folders become links). */
    fun mergeInto(keepId: Long, others: Collection<Long>) {
        val list = others.filter { it != keepId }
        if (list.isEmpty()) return
        repo.merge(keepId, list)
        index.update(keepId)
        selection = setOf(keepId)
        reload()
        notify(tr("dupes.merged", list.size + 1), tr("common.undo") to { restore(list) })
    }

    /** Merge all (or the selected) duplicate groups; in each case the entry with the most data is kept. */
    fun mergeDuplicates(onlyIds: Set<Long>? = null) {
        val groups = library.duplicateGroups.values.filter { g -> onlyIds == null || g.any { it.id in onlyIds } }
        if (groups.isEmpty()) return
        val total = groups.sumOf { it.size }
        confirm = ConfirmRequest(
            tr("dupes.mergeTitle", groups.size),
            tr("dupes.mergeText", total, groups.size),
            tr("dupes.merge"),
        ) {
            groups.forEach { g -> val keep = library.bestOf(g); repo.merge(keep.id, g.map { it.id } - keep.id) }
            index.rebuild()
            clearSelection()
            reload()
            notify(tr("dupes.mergedGroups", groups.size, total - groups.size))
        }
    }

    /** Folder "Inbox" for newly saved bookmarks (created on demand). */
    fun inboxFolderId(create: Boolean = true): Long? {
        settings.get("inboxFolder")?.toLongOrNull()?.takeIf { it in library.folders }?.let { return it }
        if (!create) return null
        val root = library.rootId ?: repo.rootFolderId()
        val id = library.childrenOf(root).firstOrNull { it.title.equals("Inbox", true) }?.id ?: repo.createFolder(root, "Inbox")
        settings.put("inboxFolder", id.toString())
        return id
    }

    /** Dialog "New bookmark", with URL from the clipboard if one is there. */
    fun startAdd(url: String? = null) {
        val clip = url ?: runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }.getOrNull()?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        addRequest = AddRequest(clip.orEmpty())
    }

    fun addBookmark(url: String, title: String, folderId: Long?) {
        val u = url.trim().let { if (it.contains("://")) it else "https://$it" }
        val target = folderId ?: inboxFolderId()!!
        val (id, created) = repo.add(u, title, target)
        reload()
        index.update(id)
        selection = setOf(id)
        if (!created) { notify(tr("save.exists")); return }
        notify(tr("save.savedIn", library.folders[target]?.title))
        val noTitle = title.isBlank()
        scope.launch(Dispatchers.IO) {
            runCatching { enricher.enrich(id) }
            if (noTitle) db.bookmarkQueries.byId(id).executeAsOneOrNull()?.meta_title?.takeIf { it.isNotBlank() }?.let { repo.edit(id, title = it) }
            if (aiConfig.usable) runCatching { ai.processOne(id) }
            dirty.set(true)
        }
    }

    // ------------------------------------------------------------ Saved searches

    fun saveSearch(name: String) {
        val clean = filter.copy(trash = false)
        if (!clean.isNarrowing && clean.folderId == null) { notify(tr("search.saveNeedsQuery")); return }
        savedSearches = savedSearches + de.bookmarksplus.data.SavedSearch(de.bookmarksplus.util.newUuid(), name.trim(), clean)
        savedRepo.save(savedSearches)
        notify(tr("search.saved", name.trim()))
    }

    fun renameSavedSearch(id: String, name: String) {
        savedSearches = savedSearches.map { if (it.id == id) it.copy(name = name.trim()) else it }
        savedRepo.save(savedSearches)
    }

    fun deleteSavedSearch(id: String) {
        savedSearches = savedSearches.filterNot { it.id == id }
        savedRepo.save(savedSearches)
    }

    /** Store current filter in a saved search (overwrites its filter). */
    fun updateSavedSearch(id: String) {
        savedSearches = savedSearches.map { if (it.id == id) it.copy(filter = filter.copy(trash = false)) else it }
        savedRepo.save(savedSearches)
        notify(tr("search.updated"))
    }

    fun selectAll() {
        selection = resultIds
    }

    // ------------------------------------------------------------ Drag & Drop

    fun startDrag(itemId: Long, x: Float, y: Float) {
        val ids = targetsFor(itemId).filter { it in library.itemsById }.toSet().ifEmpty { return }
        if (itemId !in selection) selection = setOf(itemId)
        val label = if (ids.size == 1) library.itemsById[ids.first()]?.displayTitle.orEmpty() else tr("common.nBookmarks", ids.size)
        drag = DragState(ids, x, y, label)
    }

    fun dragTo(x: Float, y: Float) {
        val d = drag ?: return
        drag = d.copy(x = x, y = y)
        dropHover = dropTargets.entries.firstOrNull { it.value.contains(androidx.compose.ui.geometry.Offset(x, y)) }?.key
    }

    /** Release: move, or link with [link] (Ctrl pressed). */
    fun endDrag(link: Boolean) {
        val d = drag ?: return
        val target = dropHover
        drag = null
        dropHover = null
        if (target != null) moveItems(d.ids, target, link)
    }

    // ------------------------------------------------------------ Sync & backup

    var syncTarget by mutableStateOf(settings.get("syncCfg.target")?.let { v -> SyncTarget.entries.firstOrNull { it.name == v } } ?: SyncTarget.None)
        private set
    var syncServerUrl by mutableStateOf(settings.get("syncCfg.server.url").orEmpty()); private set
    var syncServerUser by mutableStateOf(settings.get("syncCfg.server.user").orEmpty()); private set
    var syncServerPassword by mutableStateOf(secret("syncCfg.server.password")); private set
    var ftpHost by mutableStateOf(settings.get("syncCfg.ftp.host").orEmpty()); private set
    var ftpPort by mutableStateOf(settings.get("syncCfg.ftp.port") ?: "21"); private set
    var ftpUser by mutableStateOf(settings.get("syncCfg.ftp.user").orEmpty()); private set
    var ftpPassword by mutableStateOf(secret("syncCfg.ftp.password")); private set
    var ftpDir by mutableStateOf(settings.get("syncCfg.ftp.dir") ?: "/bookmarks-plus"); private set
    var ftpTls by mutableStateOf(settings.get("syncCfg.ftp.tls") != "false"); private set
    var syncFolder by mutableStateOf(settings.get("syncCfg.folder").orEmpty()); private set
    var syncMedia by mutableStateOf(settings.get("syncCfg.media") != "false"); private set
    var syncInterval by mutableStateOf(settings.get("syncCfg.interval")?.toIntOrNull() ?: 15); private set
    /** Derived key is stored locally DPAPI-protected; the passphrase itself is not stored. */
    var syncKeyReady by mutableStateOf(secret("syncCfg.key").isNotEmpty()); private set
    var syncRunning by mutableStateOf(false); private set
    var syncStatus by mutableStateOf<String?>(settings.get("syncCfg.status")); private set
    var syncFailed by mutableStateOf(settings.get("syncCfg.failed") == "1"); private set
    var firstSyncChoice by mutableStateOf(false)
    var remoteBackups by mutableStateOf<List<String>>(emptyList()); private set
    private var syncLoop: kotlinx.coroutines.Job? = null
    private val backupDir = File(appDir, "backups")

    fun changeSyncTarget(t: SyncTarget) { syncTarget = t; settings.put("syncCfg.target", t.name); resetSyncKeyIfTargetChanged() }
    fun changeSyncServer(url: String = syncServerUrl, user: String = syncServerUser, password: String = syncServerPassword) {
        syncServerUrl = url.trim(); syncServerUser = user.trim(); syncServerPassword = password
        settings.put("syncCfg.server.url", syncServerUrl); settings.put("syncCfg.server.user", syncServerUser); putSecret("syncCfg.server.password", password)
    }
    fun changeFtp(host: String = ftpHost, port: String = ftpPort, user: String = ftpUser, password: String = ftpPassword, dir: String = ftpDir, tls: Boolean = ftpTls) {
        ftpHost = host.trim(); ftpPort = port.trim(); ftpUser = user.trim(); ftpPassword = password; ftpDir = dir.trim(); ftpTls = tls
        settings.put("syncCfg.ftp.host", ftpHost); settings.put("syncCfg.ftp.port", ftpPort); settings.put("syncCfg.ftp.user", ftpUser)
        putSecret("syncCfg.ftp.password", password); settings.put("syncCfg.ftp.dir", ftpDir); settings.put("syncCfg.ftp.tls", tls.toString())
    }
    fun changeSyncFolder(path: String) { syncFolder = path.trim(); settings.put("syncCfg.folder", syncFolder) }
    fun changeSyncMedia(on: Boolean) { syncMedia = on; settings.put("syncCfg.media", on.toString()) }
    fun changeSyncInterval(min: Int) { syncInterval = min; settings.put("syncCfg.interval", min.toString()); startSyncLoop() }

    private fun resetSyncKeyIfTargetChanged() {
        // key belongs to the target (salt lives there) → re-enter when changing target
        putSecret("syncCfg.key", ""); syncKeyReady = false
    }

    private fun openStore(): de.bookmarksplus.sync.BlobStore = when (syncTarget) {
        SyncTarget.Server -> de.bookmarksplus.sync.ServerStore(syncServerUrl, syncServerUser, syncServerPassword)
        SyncTarget.Ftp -> de.bookmarksplus.sync.FtpStore(ftpHost, ftpPort.toIntOrNull() ?: 21, ftpUser, ftpPassword, ftpDir, ftpTls)
        SyncTarget.Folder -> de.bookmarksplus.sync.FolderStore(File(syncFolder).also { it.mkdirs() })
        SyncTarget.None -> error(tr("sync.noTarget"))
    }

    /** Sync target at record level: own server (API v2) or record files on FTP/in a folder. */
    private fun openRecordStore(): de.bookmarksplus.sync.RecordStore = when (syncTarget) {
        SyncTarget.Server -> de.bookmarksplus.sync.ServerRecordStore(syncServerUrl, syncServerUser, syncServerPassword)
        else -> de.bookmarksplus.sync.FileRecordStore(openStore())
    }

    private fun syncKey(): ByteArray? = secret("syncCfg.key").takeIf { it.isNotEmpty() }?.let { java.util.Base64.getDecoder().decode(it) }

    private fun setStatus(text: String, failed: Boolean = false) { syncStatus = text; syncFailed = failed; settings.put("syncCfg.status", text); settings.put("syncCfg.failed", if (failed) "1" else "0") }

    private fun syncJob(label: String, block: suspend () -> Unit) {
        if (syncRunning) return
        syncRunning = true
        scope.launch {
            try { block() } catch (e: Exception) {
                val msg = (e as? de.bookmarksplus.sync.SyncTargetException)?.message ?: e.message ?: e::class.simpleName
                setStatus(tr("common.failed", label, msg), failed = true)
                notify(tr("common.failed", label, msg))
            } finally { syncRunning = false }
        }
    }

    /** Test connection (fetch file list at the target). */
    fun testSyncTarget() = syncJob(tr("sync.connectionTest")) {
        val n = withContext(Dispatchers.IO) {
            openRecordStore().use { rs -> if (rs is de.bookmarksplus.sync.ServerRecordStore) rs.count() else rs.changes(null).records.count { !it.deleted }.toLong() }
        }
        // show the real state: a successful test replaces an earlier error message
        setStatus(tr("sync.connectionOk", n))
        notify(tr("sync.connectionOk", n))
    }

    /**
     * Set or enter passphrase. If a `keyinfo.json` already exists at the target, the passphrase must match it;
     * otherwise a new key is created.
     */
    fun setPassphrase(pass: String) = syncJob(tr("sync.setupKey")) {
        val key = withContext(Dispatchers.IO) {
            openStore().use { store ->
                val existing = store.get("keyinfo.json")
                if (existing != null) de.bookmarksplus.sync.Crypto.unlock(pass.toCharArray(), de.bookmarksplus.sync.Crypto.decodeKeyInfo(existing))
                else {
                    val (info, k) = de.bookmarksplus.sync.Crypto.newKeyInfo(pass.toCharArray())
                    store.put("keyinfo.json", de.bookmarksplus.sync.Crypto.encodeKeyInfo(info))
                    k
                }
            }
        }
        putSecret("syncCfg.key", java.util.Base64.getEncoder().encodeToString(key))
        syncKeyReady = true
        notify(tr("sync.keySetUp"))
        startSyncLoop()
    }

    /** Pause/stop switch of the running sync (also driven by the task panel at the bottom left). */
    private var syncControl: de.bookmarksplus.sync.SyncControl? = null

    /** Called by the engine (IO thread): updates the sync task in the status display. */
    private fun syncProgress(p: de.bookmarksplus.sync.SyncProgress) {
        val step = when (p.phase) {
            de.bookmarksplus.sync.SyncPhase.Prepare -> tr("sync.phase.prepare")
            de.bookmarksplus.sync.SyncPhase.Fetch -> tr("sync.phase.fetch")
            de.bookmarksplus.sync.SyncPhase.Send -> tr("sync.phase.send")
            de.bookmarksplus.sync.SyncPhase.ImagesUp -> tr("sync.phase.imagesUp")
            de.bookmarksplus.sync.SyncPhase.ImagesDown -> tr("sync.phase.imagesDown")
        }
        batch?.takeIf { it.kind == BatchKind.Sync && it.running }?.let { batch = it.copy(done = p.done, total = p.total, step = step) }
    }

    /** [auto] = started by the timer: if nothing had to be transferred, the task disappears again. */
    fun syncNow(mode: de.bookmarksplus.sync.FirstSyncMode = de.bookmarksplus.sync.FirstSyncMode.Ask, auto: Boolean = false) {
        val file = dbFile ?: return
        val key = syncKey() ?: run { showSettings = true; notify(tr("sync.setupFirst")); return }
        if (syncRunning) return
        val control = de.bookmarksplus.sync.SyncControl().also { syncControl = it }
        // as a task in the status display – unless another batch occupies it (then it only runs in the background)
        if (batch?.running != true) {
            batchCancelRequested = false
            batch = BatchStatus(BatchKind.Sync, tr("batch.sync"), step = tr("sync.phase.prepare"))
        }
        fun endTask(summary: String?, dismiss: Boolean = false) {
            val b = batch?.takeIf { it.kind == BatchKind.Sync && it.running } ?: return
            if (dismiss) batch = null else batchProgress(BatchKind.Sync, false, b.done, b.total, null) { summary }
        }
        syncJob("Sync") {
            val r = try {
                withContext(Dispatchers.IO) {
                    openRecordStore().use {
                        de.bookmarksplus.sync.SyncEngine(file, mediaRoot, it, key, syncMedia, control = control, onProgress = ::syncProgress).sync(mode)
                    }
                }
            } catch (e: de.bookmarksplus.sync.FirstSyncDecisionNeeded) {
                batch = batch?.takeUnless { it.kind == BatchKind.Sync }
                firstSyncChoice = true
                return@syncJob
            } catch (e: de.bookmarksplus.sync.SyncCancelledException) {
                // everything transferred so far is kept; the next run continues
                batchCancelRequested = true
                endTask(tr("sync.stoppedSummary"))
                setStatus(tr("sync.stopped"))
                missingImages.clear()
                withContext(Dispatchers.IO) { index.rebuild() }
                reload()
                return@syncJob
            } catch (e: Exception) {
                batchCancelRequested = true
                val msg = (e as? de.bookmarksplus.sync.SyncTargetException)?.message ?: e.message ?: e::class.simpleName
                endTask(tr("common.failed", "Sync", msg))
                throw e
            }
            if (r.pulled > 0) withContext(Dispatchers.IO) { index.rebuild() }
            missingImages.clear() // images that just arrived may have been reported missing before
            reload()
            val time = java.time.LocalTime.now().withNano(0)
            val status = tr("sync.lastSynced", time, r.pulled, r.pushed) + (if (r.mediaUp + r.mediaDown > 0) tr("sync.images", r.mediaUp + r.mediaDown) else "") +
                (if (r.conflicts > 0) tr("sync.conflicts", r.conflicts) else "")
            setStatus(status)
            endTask(status, dismiss = auto && !r.firstSync && r.pulled == 0 && r.pushed == 0 && r.mediaUp + r.mediaDown == 0)
            if (r.firstSync || r.pulled > 0) notify(tr("sync.result", r.pulled, r.pushed))
            if (r.pulled > 0) enrichSyncedInbox()
            // merge duplicate "Inbox" (created on two devices before the first sync) and pass it on right away
            if (r.pulled > 0 && withContext(Dispatchers.IO) { repo.mergeRootFolders("Inbox") } > 0) {
                reload()
                scope.launch { delay(300); syncNow() }
            }
        }
    }

    /**
     * Links that another device (e.g. the Android app via "Share") put into the inbox are not yet
     * enriched: catch up in the background (metadata, thumbnail, AI). On the next sync the data goes back.
     */
    private fun enrichSyncedInbox() {
        val lib = library
        val inboxes = lib.childrenOf(lib.rootId).filter { it.title.equals("Inbox", true) }.map { it.id }.toSet() +
            listOfNotNull(inboxFolderId(create = false))
        val ids = inboxes.flatMap { lib.placementsByFolder[it].orEmpty() }.map { it.bookmarkId }.distinct()
            .filter { id -> lib.itemsById[id]?.let { it.scrapedAt == null && (it.url.startsWith("http://") || it.url.startsWith("https://")) } == true }
        if (ids.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            ids.forEach { id ->
                runCatching { enricher.enrich(id) }
                // links shared without a title carry the domain as title → adopt page title
                db.bookmarkQueries.byId(id).executeAsOneOrNull()?.let { b ->
                    if (b.title == Urls.domain(b.url)) b.meta_title?.takeIf { it.isNotBlank() }?.let { repo.edit(id, title = it) }
                }
                if (aiConfig.usable) runCatching { ai.processOne(id) }
            }
            dirty.set(true)
        }
    }

    /** Periodic sync (and once shortly after start). */
    fun startSyncLoop() {
        syncLoop?.cancel()
        if (syncTarget == SyncTarget.None || !syncKeyReady || syncInterval <= 0 || dbFile == null) return
        syncLoop = scope.launch {
            delay(8_000)
            while (true) {
                if (!syncRunning && !firstSyncChoice) syncNow(auto = true)
                delay(syncInterval * 60_000L)
            }
        }
    }

    fun backupNow() {
        val file = dbFile ?: return
        val key = syncKey() ?: run { notify(tr("backup.needsPassphrase")); return }
        syncJob(tr("backup.label")) {
            val name = withContext(Dispatchers.IO) { openStore().use { de.bookmarksplus.sync.Backups.upload(file, it, key) } }
            notify(tr("backup.saved", name.substringAfterLast('/')))
            loadRemoteBackups()
        }
    }

    fun loadRemoteBackups() {
        if (syncTarget == SyncTarget.None) return
        scope.launch {
            remoteBackups = runCatching { withContext(Dispatchers.IO) { openStore().use { de.bookmarksplus.sync.Backups.listRemote(it) } } }.getOrDefault(emptyList())
        }
    }

    fun localBackups(): List<File> = de.bookmarksplus.sync.Backups.listLocal(backupDir)

    /** Prepare backup for restoring on the next start. */
    fun restoreBackup(remoteName: String? = null, local: File? = null) {
        val file = dbFile ?: return
        confirm = ConfirmRequest(
            tr("backup.restoreTitle"),
            tr("backup.restoreText1", remoteName?.substringAfterLast('/') ?: local?.name) +
                tr("backup.restoreText2", backupDir.absolutePath),
            tr("backup.restore"),
        ) {
            syncJob(tr("backup.restore")) {
                withContext(Dispatchers.IO) {
                    if (local != null) de.bookmarksplus.sync.Backups.prepareLocalRestore(local, file)
                    else openStore().use { de.bookmarksplus.sync.Backups.prepareRemoteRestore(it, syncKey()!!, remoteName!!, file) }
                }
                notify(tr("backup.restoreReady"))
                setStatus(tr("backup.restorePending"))
            }
        }
    }

    /** Daily local backup in the background. */
    private fun dailyLocalBackup() {
        val file = dbFile ?: return
        scope.launch(Dispatchers.IO) { runCatching { de.bookmarksplus.sync.Backups.localDaily(file, backupDir) } }
    }

    // ------------------------------------------------------------ Browser extension (local interface)

    private var api: de.bookmarksplus.desktop.api.LocalApi? = null
    var apiPort by mutableStateOf<Int?>(null); private set
    var apiError by mutableStateOf<String?>(null); private set
    var pairDialog by mutableStateOf<PairDialog?>(null)
    private val tokenJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    var apiTokens by mutableStateOf(loadTokens()); private set

    private fun loadTokens(): List<ApiToken> = settings.get("api.tokens")?.let {
        runCatching { tokenJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ApiToken.serializer()), it) }.getOrNull()
    }.orEmpty()

    private fun saveTokens(list: List<ApiToken>) {
        apiTokens = list
        settings.put("api.tokens", tokenJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ApiToken.serializer()), list))
    }

    fun startApi(port: Int = de.bookmarksplus.desktop.api.LocalApi.DEFAULT_PORT) {
        if (api != null) return
        runCatching { de.bookmarksplus.desktop.api.LocalApi(this, port).also { apiPort = it.start(); api = it } }
            .onFailure { apiError = it.message }
    }

    fun revokeToken(hash: String) {
        pendingTokens.remove(hash)
        saveTokens(apiTokens.filterNot { it.hash == hash })
    }

    override fun requestPairing(client: String, code: String): java.util.concurrent.CompletableFuture<Boolean> {
        val f = java.util.concurrent.CompletableFuture<Boolean>()
        scope.launch { pairDialog = PairDialog(client, code, f) }
        f.orTimeout(110, java.util.concurrent.TimeUnit.SECONDS).whenComplete { _, _ -> scope.launch { if (pairDialog?.result === f) pairDialog = null } }
        return f
    }

    override fun tokenHashes(): Set<String> = apiTokens.mapTo(HashSet()) { it.hash } + pendingTokens

    override fun addToken(client: String, hash: String) {
        scope.launch {
            saveTokens(apiTokens + ApiToken(client, hash, System.currentTimeMillis() / 1000))
            pendingTokens.remove(hash)
            notify(tr("api.connected", client))
        }
        // valid immediately, even before the UI thread saves
        pendingTokens += hash
    }
    private val pendingTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun apiItem(lib: Library, item: Item, placement: de.bookmarksplus.data.PlacementInfo? = null): de.bookmarksplus.desktop.api.ApiItem =
        de.bookmarksplus.desktop.api.ApiItem(
            id = item.id, url = item.url, title = placement?.title?.takeIf { it.isNotBlank() } ?: item.displayTitle, domain = item.domain,
            folders = (lib.placementsByItem[item.id] ?: lib.trashPlacementsByItem[item.id]).orEmpty()
                .map { de.bookmarksplus.desktop.api.ApiFolder(it.folderId, lib.pathString(it.folderId).ifEmpty { "(Wurzel)" }) },
            tags = lib.tagsOf[item.id].orEmpty().mapNotNull { lib.tagsById[it]?.name }.sorted(),
            description = item.anyDescription, summary = item.summary, stars = item.stars, trashed = item.deletedAt != null,
            originalTitle = item.title.takeIf { it.isNotBlank() && it != item.displayTitle },
            hasIcon = item.iconHash != null,
            hasThumbnail = item.thumbnailPath != null,
            hasCard = item.cardImagePath != null,
            repoUrl = item.repoUrl, websiteUrl = item.websiteUrl, lastCommit = item.lastCommit, lastRelease = item.lastRelease,
            addDate = item.addDate, contentType = item.contentType, health = item.health.name.lowercase(),
            placementId = placement?.id, linked = placement != null && !placement.primary,
            appName = item.app.name, flathubUrl = item.app.flathubUrl, alternativetoUrl = item.app.alternativetoUrl,
        )

    override fun appPage(req: de.bookmarksplus.desktop.api.AppPageRequest): de.bookmarksplus.desktop.api.AppPageResponse? = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
        val page = apps.describePage(req.url, req.html) ?: return@runBlocking null
        suspend fun apply(id: Long): String? = when (page.source) {
            "flathub" -> de.bookmarksplus.data.AppInfo.flathubIdOf(req.url)?.let { if (apps.applyFlathubPage(id, it)) null else tr("apps.flathubUnavailable") }
            else -> if (req.html != null && apps.applyAlternativeToPage(id, req.html, req.url) != null) null else tr("apps.pageUnreadable")
        }
        val sourceLabel = if (page.source == "flathub") "Flathub" else "AlternativeTo"
        fun resp(applied: Long?, message: String?, appliedCount: Int = 0): de.bookmarksplus.desktop.api.AppPageResponse {
            val lib = Library.load(db, contentLang)
            scope.launch { reload() }
            val m = if (applied == null && appliedCount == 0) appPageMatches(lib, page, req.url) else null
            return de.bookmarksplus.desktop.api.AppPageResponse(
                page.source, page.name, page.website, page.repo, page.summary,
                matches = m?.list.orEmpty(),
                applied = applied?.let { lib.anyItem(it) }?.let { apiItem(lib, it) }, message = message,
                exact = m?.exact == true, domain = m?.domain, domainTotal = m?.domainIds?.size ?: 0, appliedCount = appliedCount,
            )
        }
        when {
            req.apply != null -> {
                if (library.anyItem(req.apply) == null) return@runBlocking resp(null, tr("bookmark.notFound"))
                val err = apply(req.apply)
                scope.launch { notify(err ?: tr("apps.dataApplied", page.name, sourceLabel)) }
                resp(req.apply, err)
            }
            req.applyDomain != null -> {
                val m = appPageMatches(Library.load(db, contentLang), page, req.url)
                // only if exactly as many bookmarks are still affected as were shown to the user
                if (m.exact || m.domainIds.size != req.applyDomain)
                    return@runBlocking resp(null, tr("apps.countChanged", m.domainIds.size, req.applyDomain))
                val failed = m.domainIds.count { apply(it) != null }
                val ok = m.domainIds.size - failed
                scope.launch { notify(tr("apps.dataAppliedMany", page.name, sourceLabel, ok)) }
                resp(null, if (failed > 0) tr("apps.someFailed", failed, m.domainIds.size) else null, appliedCount = ok)
            }
            req.create -> {
                val saved = save(de.bookmarksplus.desktop.api.SaveRequest(url = page.website ?: page.repo ?: req.url, title = page.name))
                val err = apply(saved.item.id)
                resp(saved.item.id, err)
            }
            else -> resp(null, null)
        }
    }

    private class PageMatches(val list: List<de.bookmarksplus.desktop.api.AppPageMatch>, val exact: Boolean, val domain: String?, val domainIds: List<Long>)

    /**
     * Bookmarks that a program page matches – best first. If there is no exact match (stored page,
     * same URL, same repo), up to three bookmarks of the same domain as the program website are
     * offered in addition; [PageMatches.domainIds] counts all others for "adopt for all".
     */
    private fun appPageMatches(lib: Library, page: de.bookmarksplus.enrich.apps.AppService.PageApp, pageUrl: String): PageMatches {
        val names = de.bookmarksplus.enrich.apps.AppNames
        val pageNorm = de.bookmarksplus.util.Urls.normalize(pageUrl)
        val siteNorm = page.website?.let(de.bookmarksplus.util.Urls::normalize)
        val site = page.website?.let(de.bookmarksplus.util.Urls::domain)
        val generic = setOf("github.com", "gitlab.com", "sourceforge.net", "flathub.org", "alternativeto.net", "codeberg.org")
        val scored = lib.items.mapNotNull { i ->
            val a = i.app
            val (score, reason) = when {
                page.flathubUrl != null && a.flathubUrl == page.flathubUrl -> 100 to tr("apps.flathubSet")
                page.alternativetoUrl != null && a.alternativetoUrl == page.alternativetoUrl -> 100 to tr("apps.alternativeToSet")
                i.urlNorm == pageNorm -> 95 to tr("apps.match.thisPage")
                siteNorm != null && i.urlNorm == siteNorm -> 92 to tr("apps.match.sameUrl")
                page.repo?.let { pr -> listOfNotNull(i.repoUrl, i.url).any { names.sameRepo(it, pr) } } == true -> 90 to tr("apps.match.sameRepo")
                names.same(a.name, page.name) -> 75 to tr("apps.match.sameName")
                site != null && listOfNotNull(i.websiteUrl, i.url).mapNotNull(de.bookmarksplus.util.Urls::domain)
                    .any { it !in generic && names.sameSite(it, site) } -> 65 to tr("apps.match.sameDomain")
                names.same(i.title, page.name) || names.same(i.displayTitle, page.name) -> 60 to tr("apps.match.sameTitle")
                names.key(page.name).length >= 5 && names.key(i.displayTitle).startsWith(names.key(page.name)) -> 30 to tr("apps.match.similarTitle")
                else -> return@mapNotNull null
            }
            Triple(score, reason, i)
        }.sortedByDescending { it.first }
        val exact = scored.any { it.first >= 90 }
        fun match(t: Triple<Int, String, de.bookmarksplus.data.Item>, group: String) =
            de.bookmarksplus.desktop.api.AppPageMatch(apiItem(lib, t.third), t.second, linked = t.first == 100, group = group)
        val byDomain = scored.filter { it.first == 65 }
        val list = if (exact) scored.take(6).map { match(it, "exact") }
        else scored.filter { it.first != 65 }.take(6).map { match(it, "other") } + byDomain.take(3).map { match(it, "domain") }
        return PageMatches(list, exact, site.takeIf { !exact && byDomain.isNotEmpty() }, if (exact) emptyList() else byDomain.map { it.third.id })
    }

    override fun tree(): de.bookmarksplus.desktop.api.ApiTree {
        val lib = library
        val out = ArrayList<de.bookmarksplus.desktop.api.ApiTreeFolder>()
        fun walk(id: Long) {
            lib.childrenOf(id).forEach { f ->
                out += de.bookmarksplus.desktop.api.ApiTreeFolder(
                    f.id, f.parentId, f.title, lib.recursiveCounts[f.id] ?: 0, lib.placementsByFolder[f.id].orEmpty().size,
                )
                walk(f.id)
            }
        }
        lib.rootId?.let(::walk)
        return de.bookmarksplus.desktop.api.ApiTree(lib.rootId, inboxFolderId(create = false), out)
    }

    override fun folderItems(folderId: Long): List<de.bookmarksplus.desktop.api.ApiItem>? {
        val lib = library
        if (folderId !in lib.folders) return null
        return lib.placementsByFolder[folderId].orEmpty().sortedBy { it.position }
            .mapNotNull { p -> lib.itemsById[p.bookmarkId]?.let { apiItem(lib, it, p) } }
    }

    override fun media(id: Long, kind: String): Pair<ByteArray, String>? {
        val item = library.anyItem(id) ?: return null
        fun file(path: String?): Pair<ByteArray, String>? {
            val f = path?.let(::File)?.takeIf { it.isFile && it.absolutePath.startsWith(mediaRoot.absolutePath) } ?: return null
            val bytes = f.readBytes()
            return bytes to (de.bookmarksplus.enrich.ImageSniffer.mime(bytes) ?: "application/octet-stream")
        }
        return when (kind) {
            "icon" -> item.iconHash?.let { db.bookmarkQueries.iconByHash(it).executeAsOneOrNull() }?.let { it.data_ to it.mime }
            "thumbnail" -> file(item.thumbnailPath)
            "card" -> file(item.cardImagePath)
            else -> file(item.cardImagePath) ?: file(item.thumbnailPath)
        }
    }

    override fun link(id: Long, folderId: Long): de.bookmarksplus.desktop.api.ApiItem? {
        if (library.itemsById[id] == null || folderId !in library.folders) return null
        repo.addPlacement(id, folderId)
        val lib = Library.load(db, contentLang)
        scope.launch { reload() }
        return lib.anyItem(id)?.let { apiItem(lib, it) }
    }

    override fun trash(id: Long): Boolean {
        if (library.itemsById[id] == null) return false
        repo.trash(listOf(id))
        scope.launch {
            reload()
            notify(tr("browser.trashed"), tr("common.undo") to { restore(listOf(id)) })
        }
        return true
    }

    override fun restore(id: Long): Boolean {
        if (library.anyItem(id) == null) return false
        repo.restore(listOf(id))
        scope.launch { reload() }
        return true
    }

    override fun lookup(url: String): de.bookmarksplus.desktop.api.ApiItem? {
        val lib = library
        val norm = de.bookmarksplus.util.Urls.normalize(url)
        val item = lib.items.firstOrNull { it.url == url } ?: lib.items.firstOrNull { it.urlNorm == norm } ?: return null
        return apiItem(lib, item)
    }

    override fun byDomain(url: String): List<de.bookmarksplus.desktop.api.ApiItem> {
        val lib = library
        val domain = de.bookmarksplus.util.Urls.domain(url) ?: return emptyList()
        return lib.items.filter { de.bookmarksplus.util.Urls.domain(it.url) == domain }
            .sortedByDescending { it.addDate ?: 0L }.map { apiItem(lib, it) }
    }

    override fun save(req: de.bookmarksplus.desktop.api.SaveRequest): de.bookmarksplus.desktop.api.SaveResponse {
        val target = req.folderId?.takeIf { it in library.folders } ?: inboxFolderId()!!
        val (id, created) = repo.add(req.url, req.title, target)
        if (created && !req.description.isNullOrBlank()) repo.edit(id, description = req.description)
        req.tags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.forEach { name ->
            tagRepo.add(listOf(id), tagRepo.getOrCreate(name, TagRepository.colorFor(name)))
        }
        index.update(id)
        val lib = Library.load(db, contentLang)
        val item = lib.anyItem(id)!!
        scope.launch {
            reload()
            notify(if (created) tr("browser.saved", item.displayTitle) else tr("browser.exists", item.displayTitle))
        }
        if (created) scope.launch(Dispatchers.IO) {
            runCatching { enricher.enrich(id) }
            if (aiConfig.usable) runCatching { ai.processOne(id) }
            dirty.set(true)
        }
        return de.bookmarksplus.desktop.api.SaveResponse(created, apiItem(lib, item))
    }

    override fun update(id: Long, req: de.bookmarksplus.desktop.api.UpdateRequest): de.bookmarksplus.desktop.api.ApiItem? {
        if (Library.load(db, contentLang).anyItem(id) == null) return null
        val newUrl = req.url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val urlChanged = newUrl != null && newUrl != library.anyItem(id)?.url
        repo.edit(id, title = req.title?.takeIf { it.isNotBlank() }, url = newUrl, description = req.description)
        // new address → reload metadata, favicon and preview
        if (urlChanged) scope.launch(Dispatchers.IO) { runCatching { enricher.enrich(id, force = false) }; dirty.set(true) }
        req.folderId?.takeIf { it in library.folders }?.let { repo.moveTo(id, it) }
        req.tags?.let { wanted ->
            val names = wanted.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
            val lib = Library.load(db, contentLang)
            lib.tagsOf[id].orEmpty().filter { t -> names.none { it.equals(lib.tagsById[t]?.name, ignoreCase = true) } }.forEach { tagRepo.remove(listOf(id), it) }
            names.forEach { tagRepo.add(listOf(id), tagRepo.getOrCreate(it, TagRepository.colorFor(it))) }
        }
        index.update(id)
        val lib = Library.load(db, contentLang)
        scope.launch { reload() }
        return lib.anyItem(id)?.let { apiItem(lib, it) }
    }

    override fun setThumbnail(id: Long, image: ByteArray): Boolean {
        val b = db.enrichQueries.forEnrich(id).executeAsOneOrNull() ?: return false
        val store = de.bookmarksplus.enrich.MediaStore(mediaRoot)
        // new file name so that image caches (key = path) show the new image immediately
        val path = store.save(de.bookmarksplus.enrich.MediaStore.Kind.Thumbnail, "${b.uuid}-${System.currentTimeMillis()}", image) ?: return false
        val old = b.thumbnail_path
        db.enrichQueries.setThumbnail(path, id)
        // explicitly adopted from the browser → should also be displayed, no matter what was chosen before
        db.enrichQueries.setThumbChoice("website", id)
        if (old != null && old != path) store.delete(old)
        dirty.set(true)
        scope.launch { reload() }
        return true
    }

    override fun search(query: String, limit: Int, folderId: Long?, sort: String?): List<de.bookmarksplus.desktop.api.ApiItem> {
        val lib = library
        val terms = ParsedQuery.parse(query).terms
        val hits = if (terms.isEmpty()) emptyMap() else index.searchEach(terms)
        val field = sort?.let { s -> SortField.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
        val filter = Filter(
            text = query,
            folderId = folderId?.takeIf { it in lib.folders },
            sort = field?.takeIf { it != SortField.Relevance } ?: SortField.Added,
            descending = field != SortField.Title && field != SortField.Domain,
            searchSort = field ?: SortField.Relevance,
            searchDescending = field != SortField.Title && field != SortField.Domain,
        )
        return FilterEngine.apply(lib, filter, System.currentTimeMillis() / 1000, hits).take(limit).map { apiItem(lib, it) }
    }

    override fun folders(): List<de.bookmarksplus.desktop.api.ApiFolder> {
        val lib = library
        val out = ArrayList<de.bookmarksplus.desktop.api.ApiFolder>()
        lib.rootId?.let { walkInner(lib, it, out) }
        return out
    }

    private fun walkInner(lib: Library, id: Long, out: MutableList<de.bookmarksplus.desktop.api.ApiFolder>) {
        lib.childrenOf(id).forEach { f -> out += de.bookmarksplus.desktop.api.ApiFolder(f.id, lib.pathString(f.id)); walkInner(lib, f.id, out) }
    }

    override fun tags(): List<de.bookmarksplus.desktop.api.ApiTag> =
        library.tags.map { de.bookmarksplus.desktop.api.ApiTag(it.name, String.format("#%06X", it.color and 0xFFFFFF)) }

    /** Adopt bookmarks of the browser (chrome.bookmarks) into a new folder; existing URLs are linked. */
    override fun importBrowser(req: de.bookmarksplus.desktop.api.ImportRequest): de.bookmarksplus.desktop.api.ImportResponse {
        fun convert(n: de.bookmarksplus.desktop.api.BrowserNode): de.bookmarksplus.netscape.NsNode {
            val attrs = LinkedHashMap<String, String>()
            n.url?.let { attrs["HREF"] = it }
            n.dateAdded?.let { attrs["ADD_DATE"] = (it / 1000).toLong().toString() }
            return if (n.url != null) de.bookmarksplus.netscape.NsLink(n.title, attrs)
            else de.bookmarksplus.netscape.NsFolder(n.title.ifBlank { tr("folder.defaultName") }, attrs, n.children.orEmpty().map(::convert).toMutableList())
        }
        val tree = de.bookmarksplus.netscape.NsFolder("root", LinkedHashMap(), req.root.children.orEmpty().map(::convert).toMutableList())
        val root = library.rootId ?: repo.rootFolderId()
        val stamp = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        val target = repo.createFolder(root, tr("import.folderName", req.name.take(30), stamp))
        val r = repo.import(tree, target)
        index.rebuild()
        scope.launch { reload(); notify(tr("import.fromBrowser", r.links, req.name, r.newBookmarks)) }
        return de.bookmarksplus.desktop.api.ImportResponse(r.links, r.newBookmarks, r.linkedDuplicates, target)
    }

    fun importFile(file: File) {
        val r = repo.import(NetscapeParser.parse(file.readText()))
        index.rebuild()
        reload()
        message = tr("import.done", r.links, r.newBookmarks, r.linkedDuplicates)
    }

    /** Bookmarks with an http:// address that can be switched to https (local addresses excluded). */
    fun httpItems(): List<Item> = library.items.filter { it.url.startsWith("http://", ignoreCase = true) && !isLocalHost(it.url) }

    private fun isLocalHost(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase() ?: return true
        return host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".localhost") ||
            host.endsWith(".internal") || host.endsWith(".home") || !host.contains('.') ||
            Regex("""^(127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.)""").containsMatchIn(host)
    }

    /** Switches all http:// addresses to https://; "Undo" restores the old addresses. */
    fun upgradeToHttps() {
        val changed = httpItems().map { it.id to it.url }
        if (changed.isEmpty()) { notify(tr("https.none")); return }
        changed.forEach { (id, url) -> repo.edit(id, url = "https://" + url.trim().substring("http://".length)) }
        changed.forEach { (id, _) -> index.update(id) }
        reload()
        notify(tr("https.done", changed.size), tr("common.undo") to {
            changed.forEach { (id, url) -> repo.edit(id, url = url) }
            changed.forEach { (id, _) -> index.update(id) }
            reload()
            notify(tr("https.undone"))
        })
    }

    /** Passphrase prompt for encrypted export/import. */
    var passphraseRequest by mutableStateOf<PassphraseRequest?>(null)

    /** Export the whole database encrypted: as one file ([folder] = false) or one file per entry. */
    fun exportEncrypted(target: File, folder: Boolean) {
        val file = dbFile ?: return
        passphraseRequest = PassphraseRequest(
            if (folder) tr("export.encryptedFiles") else tr("export.encrypted"),
            if (folder) tr("export.filesText1", target.absolutePath) +
                tr("export.filesText2")
            else tr("export.singleText"),
            repeat = true,
        ) { pass ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        if (folder) de.bookmarksplus.sync.Transfer.exportFolder(file, target, pass)
                        else de.bookmarksplus.sync.Transfer.exportBlob(file, target, pass)
                    }
                }.onSuccess { r ->
                    notify(tr("export.done", r.bookmarks) + if (folder) tr("export.doneFiles", r.entries - r.skipped, r.removed) else "")
                }.onFailure { notify(if (it is de.bookmarksplus.sync.WrongPassphraseException) tr("export.wrongPassphrase") else tr("export.failed", it.message)) }
                pass.fill(' ')
            }
        }
    }

    /** Import encrypted export (file or folder/keyinfo.json) and merge with the existing data. */
    fun importEncrypted(source: File) {
        val file = dbFile ?: return
        passphraseRequest = PassphraseRequest(
            tr("import.encryptedTitle"),
            tr("import.encryptedText"),
            repeat = false,
        ) { pass ->
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { de.bookmarksplus.sync.Transfer.import(file, source, pass) } }
                    .onSuccess { r ->
                        withContext(Dispatchers.IO) { index.rebuild() }
                        reload()
                        notify(tr("import.encryptedDone", r.entries, r.bookmarks, r.skipped))
                    }
                    .onFailure { notify(if (it is de.bookmarksplus.sync.WrongPassphraseException) tr("import.wrongPassphrase") else tr("import.failed", it.message)) }
                pass.fill(' ')
            }
        }
    }

    fun exportFile(file: File) {
        file.writeText(NetscapeWriter.write(repo.export()))
        message = tr("export.exportedTo", file.absolutePath)
    }

    fun open(item: Item) {
        runCatching { Desktop.getDesktop().browse(URI(item.url)) }
            .onFailure { message = tr("open.failed", item.url) }
    }

    fun copyUrl(item: Item) = copyText(item.url, tr("clip.urlCopied"))

    fun copyText(text: String, done: String = tr("clip.copied")) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        message = done
    }
}
