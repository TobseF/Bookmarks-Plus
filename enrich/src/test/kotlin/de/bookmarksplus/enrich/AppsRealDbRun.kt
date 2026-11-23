package de.bookmarksplus.enrich

import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.Library
import de.bookmarksplus.enrich.ai.AiConfig
import de.bookmarksplus.enrich.ai.AiProvider
import de.bookmarksplus.enrich.ai.NANO_TAG_MODEL
import de.bookmarksplus.enrich.apps.AppService
import de.bookmarksplus.enrich.apps.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * Trial run on a COPY of the real database (manual only):
 * ./gradlew :enrich:test --tests "*AppsRealDbRun*" -Dappsdb=<copy.db> -Dkeyfile=nano-gpt.txt -Dn=20
 */
class AppsRealDbRun {
    /** Missing READMEs: -Dappsdb=<copy.db> -Dn=30 -Dreadmes=true */
    @Test
    fun readmes(): Unit = runBlocking {
        val path = System.getProperty("appsdb")?.takeIf { System.getProperty("readmes") == "true" } ?: return@runBlocking println("readmes nicht gesetzt")
        val n = System.getProperty("n")?.toIntOrNull() ?: 30
        val (driver, db) = DatabaseFactory.open(File(path))
        val enricher = Enricher(driver, db, File(File(path).parentFile, "media"), { EnrichSettings(thumbnails = false) })
        val before = enricher.missingReadmeCount()
        val ids = db.enrichQueries.missingReadmeIds().executeAsList().shuffled(java.util.Random(7)).take(n)
        var ok = 0
        val t0 = System.currentTimeMillis()
        ids.forEach { id ->
            val got = enricher.loadReadme(id)
            if (got) ok++
            val r = db.enrichQueries.forReadme(id).executeAsOne()
            println("README ${if (got) "✓" else "✗"} ${r.repo_url ?: r.url}")
        }
        println("READMES $ok/${ids.size} in ${(System.currentTimeMillis() - t0) / 1000}s; fehlend vorher $before, nachher ${enricher.missingReadmeCount()}")
        enricher.close()
        driver.close()
    }

    @Test
    fun run(): Unit = runBlocking {
        val path = System.getProperty("appsdb")?.takeIf { it.isNotBlank() && System.getProperty("readmes") != "true" } ?: return@runBlocking println("appsdb nicht gesetzt")
        val dbFile = File(path)
        val n = System.getProperty("n")?.toIntOrNull() ?: 20
        val key = System.getProperty("keyfile")?.let { File(it).readText().trim() }
        val (driver, db) = DatabaseFactory.open(dbFile)
        val media = File(dbFile.parentFile, "media")
        val enricher = Enricher(driver, db, media, { EnrichSettings(thumbnails = false) })
        val apps = AppService(
            driver, db, media,
            settings = { AppSettings(alternativeToIntervalSec = 20) },
            aiConfig = { key?.let { AiConfig(AiProvider.NanoGpt, it, NANO_TAG_MODEL) to "none" } },
            browserHtml = { enricher.browserHtml(it) },
            githubLicense = { o, r -> enricher.githubLicense(o, r) },
        )
        // mixed sample: repos, SourceForge, tool pages
        val lib = Library.load(db)
        val cands = db.appQueries.appCandidateIds().executeAsList().mapNotNull { lib.itemsById[it] }
        val rnd = java.util.Random(42)
        val repos = cands.filter { it.domain == "github.com" }.shuffled(rnd).take(n * 3 / 10)
        val sf = cands.filter { it.isSourceforge }.shuffled(rnd).take(n / 10)
        val rest = cands.filter { it.domain != "github.com" && !it.isSourceforge }.shuffled(rnd).take(n - repos.size - sf.size)
        val ids = (repos + sf + rest).map { it.id }
        println("Stichprobe: ${ids.size} von ${cands.size} Kandidaten")
        apps.start(ids)
        while (apps.progress.value.running) {
            delay(5000)
            val p = apps.progress.value
            println("… ${p.phase} ${p.done}/${p.total} named=${p.named} fh=${p.flathub} at=${p.alternativeTo} via=${p.alternativeToVia} pause=${p.alternativeToPausedUntil} err=${p.lastError}")
        }
        val p = apps.progress.value
        println("FERTIG named=${p.named} flathub=${p.flathub} at=${p.alternativeTo} errors=${p.errors} cost=${p.cost} lastError=${p.lastError}")
        val after = Library.load(db)
        ids.mapNotNull { after.itemsById[it] }.forEach { i ->
            val a = i.app
            println("RES ${i.title.take(60)} | ${i.url.take(70)}")
            println("    name=${a.name} (${a.nameSource}) plat=${a.platforms} sys=${a.systems} lic=${a.license} pkgs=${a.packages.map { it.second }}" +
                " fh=${a.flathubId} inst=${a.flathubInstalls} at=${a.alternativetoUrl != null}")
        }
        apps.close()
        enricher.close()
        driver.close()
    }
}
