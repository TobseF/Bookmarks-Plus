package de.bookmarksplus.enrich.ai

import de.bookmarksplus.i18n.tr
import app.cash.sqldelight.db.SqlDriver
import de.bookmarksplus.data.FolderMatcher
import de.bookmarksplus.data.Library
import de.bookmarksplus.data.SearchIndex
import de.bookmarksplus.data.TagRepository
import de.bookmarksplus.db.BookmarksDb
import de.bookmarksplus.enrich.Http
import de.bookmarksplus.enrich.PageScraper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class AiSettings(
    /** Provider, key and model for stage 1 (title, description, summary in DE and EN). */
    val config: AiConfig,
    /** Model for stage 2 (tags and folders); empty = same as for texts. */
    val tagModel: String = "",
    /** Assign suggested tags directly instead of only suggesting them. */
    val autoApplyTags: Boolean = false,
    val parallel: Int = 4,
)

data class AiProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val errors: Int = 0,
    val current: String? = null,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    /** Cost in USD (reported by the provider or computed from the price list). */
    val cost: Double = 0.0,
    /** Start time (ms) for the remaining-time estimate. */
    val startedAt: Long = 0,
    val lastError: String? = null,
    /** Error that aborted the run (invalid key, balance empty …). */
    val fatal: String? = null,
)

/**
 * Generates titles, summaries, tag and folder suggestions via AI. Runs in the background with limited
 * parallelism; folder candidates are preselected locally ([FolderMatcher]).
 */
class AiService(
    private val driver: SqlDriver,
    private val db: BookmarksDb,
    private val settings: () -> AiSettings,
    private val dbLock: Mutex = Mutex(),
    private val onChanged: (Long) -> Unit = {},
    /** Pass on app detection from stage 1 (name, platforms, systems; the recipient decides the ranking). */
    private val onApp: suspend (id: Long, app: AiApp?) -> Unit = { _, _ -> },
) : Closeable {

    private val http = Http()
    private val client = AiClient(http)
    private val index = SearchIndex(driver)
    private val tags = TagRepository(db)
    private val q get() = db.aiQueries
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** Pause switch of the running batch. */
    val pause = de.bookmarksplus.enrich.PauseGate()
    private val listJson = ListSerializer(String.serializer())

    private val _progress = MutableStateFlow(AiProgress())
    val progress: StateFlow<AiProgress> = _progress

    init {
        index.ensure()
    }

    fun pendingCount(): Long = q.countPendingAi().executeAsOne()

    /** Starts the evaluation for [ids] or all still open ones ([all] = really all anew). */
    fun start(ids: List<Long>? = null, all: Boolean = false) {
        if (job?.isActive == true) return
        val s = settings()
        if (!s.config.usable) {
            _progress.value = AiProgress(fatal = tr("enrich.ai.noProvider"))
            return
        }
        val targets = ids ?: if (all) q.allActiveIds().executeAsList() else q.pendingAiIds().executeAsList()
        if (targets.isEmpty()) return
        pause.resume()
        _progress.value = AiProgress(running = true, total = targets.size, startedAt = System.currentTimeMillis())
        job = scope.launch {
            val ctx = RunContext.load(db)
            val channel = Channel<Long>(Channel.UNLIMITED)
            targets.forEach { channel.trySend(it) }
            channel.close()
            val done = AtomicInteger()
            val errors = AtomicInteger()
            coroutineScope {
                repeat(s.parallel.coerceIn(1, 12)) {
                    launch {
                        for (id in channel) {
                            if (!isActive) break
                            pause.await()
                            try {
                                process(id, ctx)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: AiException) {
                                errors.incrementAndGet()
                                if (!e.retryable) {
                                    _progress.update { it.copy(fatal = e.message, lastError = e.message) }
                                    this@coroutineScope.cancel()
                                } else _progress.update { it.copy(lastError = e.message) }
                            } catch (e: Exception) {
                                errors.incrementAndGet()
                                _progress.update { it.copy(lastError = e.message ?: e::class.simpleName) }
                            }
                            val d = done.incrementAndGet()
                            _progress.update { it.copy(done = d, errors = errors.get()) }
                        }
                    }
                }
            }
        }.also { j -> j.invokeOnCompletion { _progress.update { it.copy(running = false, current = null) } } }
    }

    fun stop() {
        val j = job ?: return
        scope.launch { j.cancelAndJoin() }
    }

    /** Evaluates a single bookmark (also outside a run, e.g. "✨ regenerate"). */
    suspend fun processOne(id: Long) {
        val s = settings()
        if (!s.config.usable) throw AiException(tr("enrich.ai.noProvider"), 0, false)
        process(id, RunContext.load(db))
    }

    /** Checks key and model with a mini request. Returns a readable message. */
    fun test(cfg: AiConfig): String {
        val r = client.chat(cfg, "Antworte nur mit OK.", "Test", maxTokens = 5)
        return tr("enrich.ai.connectionOk", cfg.model, r.text.trim().take(30))
    }

    fun models(cfg: AiConfig): List<AiModel> = client.models(cfg)

    @Volatile private var modelCache: Pair<String, Map<String, AiModel>>? = null

    /** Model metadata of the provider (prices, reasoning levels), loaded once. */
    private fun modelInfo(cfg: AiConfig): Map<String, AiModel> {
        modelCache?.takeIf { it.first == cfg.baseUrl }?.let { return it.second }
        val m = runCatching { client.models(cfg).associateBy { it.id } }.getOrDefault(emptyMap())
        modelCache = cfg.baseUrl to m
        return m
    }

    // ------------------------------------------------------------------------------------------------

    /** Data that a run loads once: library, folder matcher and tag vocabulary. */
    private class RunContext(val lib: Library, val matcher: FolderMatcher, val vocab: ConcurrentHashMap<String, Int>) {
        fun topVocabulary(n: Int = 120): List<String> = vocab.entries.sortedByDescending { it.value }.take(n).map { it.key }

        companion object {
            fun load(db: BookmarksDb): RunContext {
                val lib = Library.load(db)
                val vocab = ConcurrentHashMap<String, Int>()
                // real tags count more than previous suggestions
                lib.tags.forEach { vocab.merge(it.name.lowercase(), (it.usage * 3).toInt().coerceAtLeast(3), Int::plus) }
                db.aiQueries.allAiTags().executeAsList().forEach { json ->
                    Library.parseTagList(json).forEach { vocab.merge(it.lowercase(), 1, Int::plus) }
                }
                return RunContext(lib, FolderMatcher(lib), vocab)
            }
        }
    }

    private suspend fun process(id: Long, ctx: RunContext) {
        val row = q.forAi(id).executeAsOneOrNull() ?: return
        val s = settings()
        _progress.update { it.copy(current = row.domain ?: row.url) }
        val st = row.link_status
        // dead only on a clear finding; 401/403/429 and connection errors mean "content unknown"
        val dead = st == 404L || st == 410L || st == 451L || (st == 0L && (row.link_error == "dns" || row.link_error == "parked"))
        val blocked = !dead && st != null && (st == 0L || st in 400L..599L)

        // reload page text if enrichment has not (yet) run
        var text = row.page_text
        if (text == null && row.readme == null && !dead && (row.url.startsWith("http://") || row.url.startsWith("https://"))) {
            val page = http.get(row.url)
            if (page.ok && page.isHtml) {
                text = page.text()?.let { runCatching { PageScraper.parse(it, page.finalUrl).text }.getOrNull() }
                text?.let { t -> write { q.setPageText(t, id) } }
            }
        }

        val lib = ctx.lib
        val item = lib.itemsById[id]
        val candidates = item?.let { ctx.matcher.candidates(it, 15) }.orEmpty().map { lib.pathString(it) }
        val current = item?.let { lib.primaryFolder(it.id) }?.let { lib.pathString(it) }
        val topLevel = lib.rootId?.let { root -> lib.childrenOf(root).flatMap { top -> listOf(top) + lib.childrenOf(top.id) } }
            .orEmpty().take(60).map { lib.pathString(it.id) }
        val candidateList = (listOfNotNull(current) + candidates).distinct()

        val input = AiInput(
            url = row.url, title = row.title, description = row.description?.let(de.bookmarksplus.data.Text::cleanDescription),
            metaTitle = row.meta_title, metaDescription = row.meta_description, text = text, readme = row.readme,
            stars = row.gh_stars, currentFolder = current, dead = dead, blocked = blocked && text == null && row.readme == null,
        )
        val textCfg = s.config
        val tagCfg = s.config.copy(model = s.tagModel.ifBlank { s.config.model })
        val info = modelInfo(textCfg)

        // stage 1: texts in DE and EN
        val r1 = chatWithRetry(textCfg, AiPrompt.SYSTEM_TEXTS, AiPrompt.userTexts(input), 1200, info[textCfg.model]?.cheapestEffort)
        account(r1, info[textCfg.model])
        val parsed = AiPrompt.parseTexts(r1.text) ?: throw IllegalStateException(tr("enrich.ai.textsUnreadable", r1.text.take(120)))
        // "parked" only with evidence from the page content
        val texts = (if (parsed.parked && text == null && row.meta_description.isNullOrBlank()) parsed.copy(parked = false) else parsed)
            .let { t -> t.copy(titleDe = t.titleDe ?: t.titleEn ?: row.title, titleEn = t.titleEn ?: t.titleDe ?: row.title) }

        // stage 2: tags (EN + DE) and folders – only with the short texts
        val allPaths = lib.folders.keys.filter { it != lib.rootId }.map { lib.pathString(it) }
        val r2 = chatWithRetry(
            tagCfg, AiPrompt.SYSTEM_CLASSIFY, AiPrompt.userClassify(input, texts, candidateList, topLevel, ctx.topVocabulary()),
            400, info[tagCfg.model]?.cheapestEffort,
        )
        account(r2, info[tagCfg.model])
        val cls = AiPrompt.parseClassification(r2.text, candidateList + allPaths) ?: throw IllegalStateException(tr("enrich.ai.tagsUnreadable", r2.text.take(120)))
        cls.tags.forEach { ctx.vocab.merge(it.en, 1, Int::plus) }

        if (!texts.parked && !dead) runCatching { onApp(id, texts.app) }
        val now = System.currentTimeMillis() / 1000
        write {
            q.setAiTexts(
                texts.titleDe, texts.titleEn, texts.descDe, texts.descEn,
                texts.summaryDe?.takeIf { it.isNotBlank() }, texts.summaryEn?.takeIf { it.isNotBlank() },
                texts.type, "text=${textCfg.model};tags=${tagCfg.model}", id,
            )
            // do not suggest a folder for dead or parked pages – they rather belong in the trash
            q.setAiClassification(Json.encodeToString(listJson, cls.tags.map { it.en }), cls.folder.takeUnless { texts.parked || dead }, now, id)
            if (texts.parked) q.markParked(id)
            cls.tags.forEach { t ->
                // do not overwrite existing translation (vocabulary stays stable)
                if (q.allTagI18n().executeAsList().none { it.name.equals(t.en, ignoreCase = true) }) q.putTagI18n(t.en, t.de, t.en)
            }
            if (s.autoApplyTags) cls.tags.forEach { t -> tags.add(listOf(id), tags.getOrCreate(t.en, TagRepository.colorFor(t.en))) }
            index.update(id)
        }
        onChanged(id)
    }

    private fun account(r: ChatResult, model: AiModel?) {
        val cost = r.cost ?: model?.let { m -> r.promptTokens * (m.promptPrice ?: 0.0) + r.completionTokens * (m.completionPrice ?: m.promptPrice ?: 0.0) } ?: 0.0
        _progress.update {
            it.copy(promptTokens = it.promptTokens + r.promptTokens, completionTokens = it.completionTokens + r.completionTokens, cost = it.cost + cost)
        }
    }

    private suspend fun chatWithRetry(cfg: AiConfig, system: String, user: String, maxTokens: Int = 900, effort: String? = null): ChatResult {
        var wait = 2_000L
        repeat(3) {
            try {
                return client.chat(cfg, system, user, maxTokens = maxTokens, reasoningEffort = effort)
            } catch (e: AiException) {
                if (!e.retryable) throw e
                delay(wait)
                wait *= 3
            }
        }
        return client.chat(cfg, system, user, maxTokens = maxTokens, reasoningEffort = effort)
    }

    private suspend fun write(block: () -> Unit) = dbLock.withLock { db.transaction { block() } }

    override fun close() {
        runBlocking { job?.cancelAndJoin() }
        scope.cancel()
    }

    companion object {
        /** Rough token counts per bookmark: stage 1 (texts DE/EN) and stage 2 (tags/folders), each on/off. */
        const val TEXT_IN = 1600L
        const val TEXT_OUT = 380L
        const val TAG_IN = 650L
        const val TAG_OUT = 90L

        /** Estimated cost in USD for [count] bookmarks, if prices are known. */
        fun estimateCost(count: Long, text: AiModel?, tag: AiModel?): Double? {
            val a = text?.promptPrice ?: return null
            val b = tag?.promptPrice ?: return null
            return count * (TEXT_IN * a + TEXT_OUT * (text.completionPrice ?: a) + TAG_IN * b + TAG_OUT * (tag.completionPrice ?: b))
        }
    }
}
