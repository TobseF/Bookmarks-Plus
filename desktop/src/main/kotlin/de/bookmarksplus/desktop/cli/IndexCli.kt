package de.bookmarksplus.desktop.cli

import de.bookmarksplus.i18n.tr
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.SearchIndex
import de.bookmarksplus.data.SettingsRepository
import de.bookmarksplus.desktop.appDir
import de.bookmarksplus.enrich.EnrichSettings
import de.bookmarksplus.enrich.Enricher
import de.bookmarksplus.enrich.ai.AiConfig
import de.bookmarksplus.enrich.ai.AiProvider
import de.bookmarksplus.enrich.ai.AiService
import de.bookmarksplus.enrich.ai.AiSettings
import de.bookmarksplus.sync.Secrets
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.time.Duration
import java.time.LocalTime
import kotlin.system.exitProcess

/**
 * Full indexing via the command line (without UI):
 *
 *   gradlew :desktop:index --args="--key-file nano-gpt.txt --enrich --all"
 *
 * Options:
 *   --key-file <file>     take over API key from file (stored DPAPI-protected in the settings)
 *   --provider <name>      nanogpt (default) | openrouter | custom
 *   --text-model <id>      model for title/description/summary (DE+EN)
 *   --tag-model <id>       model for tags and folders
 *   --parallel <n>         simultaneous AI requests (default 8)
 *   --enrich               first load missing metadata, thumbnails and GitHub data
 *   --all                  re-evaluate all bookmarks (otherwise only open ones or those without DE/EN texts)
 *   --limit <n>            only the first n (for trying out)
 *   --db <file>            database other than ~/.bookmarks-plus/bookmarks.db
 */
fun main(args: Array<String>) {
    val opts = parse(args)
    val dbFile = File(opts["db"] ?: File(appDir, "bookmarks.db").path)
    if (!dbFile.isFile) fail(tr("cli.dbNotFound", dbFile.absolutePath))
    val log = File(appDir, "index.log").apply { parentFile.mkdirs() }
    fun out(msg: String) {
        val line = "${LocalTime.now().withNano(0)}  $msg"
        println(line)
        log.appendText(line + "\n")
    }

    // backup before the first open (migration) and before changes
    val backup = File(appDir, "backups/before-index-${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.db")
    de.bookmarksplus.sync.Backups.copyDb(dbFile, backup)
    out(tr("cli.backup", backup.absolutePath))
    val (driver, db) = DatabaseFactory.open(dbFile)
    val settings = SettingsRepository(db)
    val secrets = Secrets(File(appDir, "secret.key"))
    val provider = when (opts["provider"]?.lowercase()) {
        null, "nanogpt", "nano-gpt" -> AiProvider.NanoGpt
        "openrouter" -> AiProvider.OpenRouter
        "custom" -> AiProvider.Custom
        else -> fail(tr("cli.unknownProvider", opts["provider"]))
    }
    settings.put("ai.provider", provider.name)
    opts["key-file"]?.let { f ->
        val key = File(f).takeIf { it.isFile }?.readText()?.trim() ?: fail(tr("cli.keyFileNotFound", f))
        settings.put("ai.key.${provider.name}", secrets.protect(key))
        out(tr("cli.keyApplied", provider.label))
    }
    val key = secrets.reveal(settings.get("ai.key.${provider.name}"))
    if (key.isBlank() && provider != AiProvider.Custom) fail(tr("cli.noKey"))
    opts["text-model"]?.let { settings.put("ai.model.${provider.name}", it) }
    opts["tag-model"]?.let { settings.put("ai.tagModel.${provider.name}", it) }
    val textModel = settings.get("ai.model.${provider.name}") ?: provider.defaultModel
    val tagModel = settings.get("ai.tagModel.${provider.name}") ?: provider.defaultTagModel
    val parallel = opts["parallel"]?.toIntOrNull() ?: 8
    val cfg = AiConfig(provider, key, textModel, settings.get("ai.baseUrl"))
    val lock = Mutex()

    // 1) enrichment (optional)
    if ("enrich" in opts) {
        val enricher = Enricher(driver, db, File(appDir, "media"), {
            EnrichSettings(
                githubToken = secrets.reveal(settings.get("githubToken")).ifBlank { null },
                thumbnails = settings.get("thumbnails") != "false",
                browserWorkers = settings.get("browserWorkers")?.toIntOrNull() ?: 2,
                darkScreenshots = settings.get("darkScreenshots") != "false",
                realBrowser = settings.get("realBrowser") != "false",
                visibleBrowser = settings.get("visibleBrowser") == "true",
            )
        }, dbLock = lock)
        val pending = enricher.pendingCount()
        out(tr("cli.enrichStart", pending))
        if (pending > 0) {
            enricher.start()
            var last = 0L
            while (true) {
                Thread.sleep(2000)
                val p = enricher.progress.value
                if (!p.running && p.done >= p.total) break
                if (System.currentTimeMillis() - last > 15_000) {
                    out(tr("cli.enrichProgress", p.done, p.total, p.errors) + (p.githubPausedUntil?.let { " (GitHub-Limit)" } ?: "") +
                        (p.lastError?.let { tr("cli.last", it.take(160)) } ?: "") +
                        (if (enricher.githubTokenRejected) tr("cli.tokenRejected") else ""))
                    last = System.currentTimeMillis()
                }
            }
            out(tr("cli.enrichDone", enricher.progress.value.done, enricher.progress.value.errors) +
                (enricher.progress.value.lastError?.let { tr("cli.last", it.take(200)) } ?: ""))
            if ("enrich-only" in opts) exitProcess(0)
        }
        enricher.close()
    }

    // 2) AI in two stages
    val ai = AiService(driver, db, { AiSettings(cfg, tagModel = tagModel, autoApplyTags = settings.get("ai.autoTags") == "true", parallel = parallel) }, dbLock = lock)
    val all = "all" in opts
    val ids = (if (all) db.aiQueries.allActiveIds().executeAsList() else db.aiQueries.pendingAiIds().executeAsList())
        .let { l -> opts["limit"]?.toIntOrNull()?.let { l.take(it) } ?: l }
    val models = runCatching { ai.models(cfg).associateBy { it.id } }.getOrDefault(emptyMap())
    if (models.isNotEmpty()) {
        listOf(textModel, tagModel).filter { it !in models }.forEach { fail(tr("cli.noModel", it, provider.label)) }
    }
    val estimate = AiService.estimateCost(ids.size.toLong(), models[textModel], models[tagModel])
    out(tr("cli.aiStart", ids.size, textModel, tagModel, parallel) +
        (estimate?.let { tr("cli.estimate").format(it) } ?: ""))
    if (ids.isEmpty()) { out(tr("cli.nothing")); exitProcess(0) }

    ai.start(ids)
    var last = 0L
    while (true) {
        Thread.sleep(2000)
        val p = ai.progress.value
        if (p.fatal != null) { out(tr("cli.aborted", p.fatal)); break }
        if (!p.running && p.done >= p.total) break
        if (System.currentTimeMillis() - last > 20_000) {
            val elapsed = (System.currentTimeMillis() - p.startedAt).coerceAtLeast(1)
            val eta = if (p.done > 0) Duration.ofMillis(elapsed * (p.total - p.done) / p.done) else null
            out(
                tr("cli.aiProgress", p.done, p.total, p.errors).format(p.promptTokens + p.completionTokens, p.cost) +
                    (eta?.let { tr("cli.eta", it.toMinutes()) } ?: "") + (p.lastError?.let { tr("cli.last", it.take(80)) } ?: ""),
            )
            last = System.currentTimeMillis()
        }
    }
    val p = ai.progress.value
    out(tr("cli.aiDone", p.done - p.errors, p.errors).format(p.promptTokens + p.completionTokens, p.cost))
    ai.close()
    SearchIndex(driver).rebuild()
    out(tr("cli.indexRebuilt", log.absolutePath))
    driver.close()
    exitProcess(if (p.fatal != null) 1 else 0)
}

private fun parse(args: Array<String>): Map<String, String> {
    val m = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        require(a.startsWith("--")) { tr("cli.unknownArg", a) }
        val k = a.removePrefix("--")
        if (i + 1 < args.size && !args[i + 1].startsWith("--")) { m[k] = args[i + 1]; i += 2 } else { m[k] = "true"; i++ }
    }
    return m
}

private fun fail(msg: String): Nothing {
    System.err.println(msg)
    exitProcess(2)
}
