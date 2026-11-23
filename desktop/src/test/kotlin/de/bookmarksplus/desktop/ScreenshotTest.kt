package de.bookmarksplus.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.Period
import de.bookmarksplus.data.TagRepository
import de.bookmarksplus.desktop.ui.App
import de.bookmarksplus.netscape.NetscapeParser
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing

/** Renders the UI offscreen with the real export file and stores PNGs under build/screenshots. */
class ScreenshotTest {

    @Test
    fun renderViews() = runBlocking(Dispatchers.Swing) { renderAll() }

    private suspend fun renderAll() {
        val sample = System.getProperty("bookmarks.sample")?.let(::File)?.takeIf { it.isFile } ?: return
        val outDir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
        val (driver, db) = DatabaseFactory.open(null)
        val state = AppState(driver, db, File(outDir, "media"))
        state.importFile(sample)
        state.message = null

        // a few example tags so that tag display and filter are visible
        val lib = state.library
        val tags = TagRepository(db)
        fun tag(name: String, match: (de.bookmarksplus.data.Item) -> Boolean) {
            val id = tags.getOrCreate(name, TagRepository.colorFor(name))
            tags.add(lib.items.filter(match).map { it.id }, id)
        }
        tag("design") { "design" in it.haystackPublic() }
        tag("3d") { "3d" in it.haystackPublic() || "blender" in it.haystackPublic() }
        tag("dev") { it.domain == "github.com" || "kotlin" in it.haystackPublic() }
        tag("ai") { " ai" in it.haystackPublic() || "gpt" in it.haystackPublic() }
        state.reload()

        val focus = FocusRequester()
        suspend fun shot(name: String, w: Int = 1480, h: Int = 920) {
            ImageComposeScene(w, h, Density(1f), coroutineContext = Dispatchers.Swing) { App(state, focus, {}, {}) }.use { scene ->
                var t = 0L
                repeat(6) { scene.render(t); t += 50_000_000; delay(30) }
                val png = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(outDir, "$name.png").writeBytes(png)
            }
        }

        state.changeView(ViewMode.Cards); shot("1-cards")
        state.changeView(ViewMode.List); shot("2-list")
        state.changeView(ViewMode.Tree)
        state.sidebarExpanded = state.sidebarExpanded + state.library.childrenOf(state.library.rootId).flatMap { state.library.childrenOf(it.id) }.map { it.id }
        state.treeExpanded = state.library.childrenOf(state.library.rootId).map { it.id }.toSet() +
            state.library.childrenOf(state.library.childrenOf(state.library.rootId).first().id).map { it.id }
        shot("3-tree")

        // filtered: search + tag, detail area open
        state.treeExpanded = emptySet()
        state.filter = state.filter.copy(text = "blender", added = Period.Any)
        shot("4-tree-filtered")
        state.changeView(ViewMode.Cards)
        state.filter = state.filter.copy(text = "", onlyRepos = true)
        state.results.firstOrNull()?.let { state.select(it.id) }
        shot("5-cards-repos-detail")
        state.showSettings = true
        shot("6-settings")
        state.showSettings = false

        // AI suggestions (simulated, without provider)
        state.filter = de.bookmarksplus.data.Filter()
        val sampleIds = state.results.take(6).map { it.id }
        val anyFolder = state.library.folders.keys.first { it != state.library.rootId && state.library.path(it).size >= 2 }
        sampleIds.forEachIndexed { i, id ->
            val it = state.library.itemsById[id]!!
            db.aiQueries.setAiResult(
                it.title.take(40) + " – KI-Titel", "Kurze KI-Zusammenfassung: worum es auf der Seite geht und wofür sie nützlich ist.",
                de.bookmarksplus.data.Library.encodeTagList(listOf("design", "werkzeug", "inspiration").take(1 + i % 3)),
                if (i % 2 == 0) state.library.pathString(anyFolder) else state.library.pathString(anyFolder) + " / Neu", 1, "article", id,
            )
        }
        state.reload()
        state.filter = state.filter.copy(onlySuggested = true)
        state.select(sampleIds.first())
        shot("11-ai-detail")
        state.select(sampleIds[1], toggle = true); state.select(sampleIds[2], toggle = true)
        shot("12-ai-multi")
        state.clearSelection()
        state.filter = de.bookmarksplus.data.Filter()

        // management: README rendered, editing, choose folder, trash
        val repoItem = state.library.items.first { it.domain == "github.com" }
        state.repo.edit(repoItem.id, readme = SAMPLE_README)
        state.reload()
        state.changeView(ViewMode.List)
        state.select(repoItem.id)
        shot("13-readme")
        state.startEdit(repoItem.id, focusTitle = true)
        shot("14-edit")
        state.editingId = null
        state.picker = PickerRequest(setOf(repoItem.id), link = false)
        shot("15-picker")
        state.picker = null

        // programs: app data with Flathub/AlternativeTo icons, editing with collapsed "Advanced"
        state.saveApp(
            repoItem.id,
            de.bookmarksplus.data.AppInfo(
                name = "FileZilla", platforms = listOf("pc"), systems = listOf("windows", "linux", "mac"), license = "GPL-2.0-or-later",
                winget = "FileZilla.FileZilla", brew = "--cask filezilla",
                flathubUrl = "https://flathub.org/apps/org.filezillaproject.Filezilla",
                alternativetoUrl = "https://alternativeto.net/software/filezilla/about/",
            ),
        )
        db.appQueries.setFlathubStats(790265, 14551111, 37200384, repoItem.id)
        state.reload()
        state.select(repoItem.id)
        shot("24-app-detail", h = 1400)
        state.startEdit(repoItem.id)
        shot("25-app-edit", h = 1400)
        state.editingId = null
        val trashIds = state.results.drop(1).take(3).map { it.id }
        state.trash(trashIds)
        state.filter = de.bookmarksplus.data.Filter(trash = true)
        state.select(trashIds.first())
        shot("16-trash")
        state.deleteForever(trashIds)
        shot("17-confirm")
        state.confirm = null
        state.clearSelection()
        state.filter = de.bookmarksplus.data.Filter()

        // phase 5: duplicates, redirects, new bookmark, saved search, drag & drop
        val now = System.currentTimeMillis() / 1000
        state.library.items.take(40).forEachIndexed { i, it ->
            when (i % 4) {
                0 -> db.bookmarkQueries.setLinkCheck(200, null, it.url.trimEnd('/') + "/neu-verschoben", now, it.id)
                1 -> db.bookmarkQueries.setLinkCheck(0, "dns", null, now, it.id)
                2 -> db.bookmarkQueries.setLinkCheck(403, null, null, now, it.id)
                else -> db.bookmarkQueries.setLinkCheck(200, null, null, now, it.id)
            }
        }
        state.filter = de.bookmarksplus.data.Filter(text = "kotlin", onlyRepos = true)
        state.saveSearch("Kotlin-Repos")
        state.message = null
        state.reload()
        state.changeView(ViewMode.List)
        state.filter = de.bookmarksplus.data.Filter(onlyDuplicates = true)
        state.results.firstOrNull()?.let { state.select(it.id) }
        shot("18-duplicates")
        state.filter = de.bookmarksplus.data.Filter(onlyRedirects = true)
        state.results.firstOrNull()?.let { state.select(it.id) }
        shot("19-redirects")
        state.clearSelection()
        state.filter = de.bookmarksplus.data.Filter()
        state.addRequest = AddRequest("https://kotlinlang.org/docs/home.html")
        shot("20-add")
        state.addRequest = null
        val folderId = state.library.childrenOf(state.library.childrenOf(state.library.rootId).first().id).first().id
        shot("21-tmp") // position sidebar so that drop targets are known
        state.select(state.results.first().id)
        state.drag = DragState(state.selection, 160f, 320f, state.results.first().displayTitle)
        state.dropHover = folderId
        shot("21-drag")
        state.drag = null; state.dropHover = null
        File(outDir, "21-tmp.png").delete()
        state.clearSelection()

        if (System.getProperty("net") == "true") {
            state.clearSelection()
            state.filter = de.bookmarksplus.data.Filter()
            val ids = state.results.take(12).map { it.id }
            kotlinx.coroutines.withContext(Dispatchers.IO) { ids.forEach { runCatching { state.enricher.enrich(it) }.onFailure { e -> println("enrich $it: $e") } } }
            state.reload()
            state.changeView(ViewMode.Cards)
            shot("7-cards-enriched")
            val enriched = ids.mapNotNull { state.library.itemsById[it] }
            enriched.firstOrNull { it.hasReadme }?.let { state.select(it.id); shot("8-detail-repo") }
            enriched.firstOrNull { it.domain != "github.com" && it.thumbnailPath != null }?.let { state.select(it.id); shot("9-detail-site") }
            state.clearSelection()
            state.changeView(ViewMode.List)
            shot("10-list-enriched")
        }
        // phase 7: sync settings (folder target) and first-sync dialog
        state.changeSyncTarget(SyncTarget.Folder)
        state.changeSyncFolder(File(outDir, "sync-ziel").absolutePath)
        ImageComposeScene(620, 760, Density(1f), coroutineContext = Dispatchers.Swing) {
            de.bookmarksplus.desktop.ui.AppTheme(state.accent) {
                androidx.compose.material3.Surface(color = de.bookmarksplus.desktop.ui.Palette.SurfaceHigh, contentColor = de.bookmarksplus.desktop.ui.Palette.Text) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(24.dp)) { de.bookmarksplus.desktop.ui.SyncSettings(state) }
                }
            }
        }.use { scene ->
            var t = 0L
            repeat(6) { scene.render(t); t += 50_000_000; delay(30) }
            File(outDir, "22-sync-settings.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        state.firstSyncChoice = true
        shot("23-first-sync")
        state.firstSyncChoice = false
        state.changeSyncTarget(SyncTarget.None)
        println("Screenshots in ${outDir.absolutePath}")
    }
}

private fun de.bookmarksplus.data.Item.haystackPublic() = (title + " " + url + " " + (description ?: "")).lowercase()

private val SAMPLE_README = """
<p align="center"><img src="https://example.invalid/logo.png" alt="Logo"></p>

# Beispielprojekt

Ein **schnelles** Werkzeug für *Lesezeichen* mit `Kotlin` und [Compose](https://www.jetbrains.com/compose-multiplatform/).

## Installation

```bash
./gradlew :desktop:run
```

- Import aus Chrome und Vivaldi
- Tags, Ordner und ~~Chaos~~ Ordnung
  1. verschachtelt
  2. nummeriert

> Hinweis: Alles bleibt lokal.

| Funktion | Status |
|----------|--------|
| Import   | fertig |
| Sync     | geplant |

---

Mehr in der [Dokumentation](docs/README.md).
""".trimIndent()
