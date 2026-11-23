package de.bookmarksplus.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.Filter
import de.bookmarksplus.data.TagRepository
import de.bookmarksplus.desktop.ui.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/**
 * Generates the screenshots for the README from the public demo collection `docs/demo/demo-bookmarks-en.html`
 * (English, default) or `docs/demo/demo-bookmarks.html` (German) – no private bookmarks.
 * Actually loads the pages for this (network, Chrome/Edge for preview images).
 *
 *   gradlew :desktop:test --tests "*DemoScreenshots*" -Ddemo.out=docs/screenshots
 *   gradlew :desktop:test --tests "*DemoScreenshots*" -Ddemo.out=docs/screenshots/de -Ddemo.lang=de
 *
 * The enriched demo database remains under `build/demo-<lang>` (e.g. for Android screenshots).
 */
class DemoScreenshots {

    @Test
    fun render() {
        val out = System.getProperty("demo.out")?.let(::File) ?: return
        runBlocking(Dispatchers.Swing) { renderAll(out.apply { mkdirs() }) }
    }

    private suspend fun renderAll(out: File) {
        val root = File(System.getProperty("demo.root") ?: "..")
        val lang = de.bookmarksplus.i18n.UiLang.of(System.getProperty("demo.lang"))
        // -Ddemo.reuse=true: only re-photograph the existing, already enriched demo database
        val reuse = System.getProperty("demo.reuse") == "true" && File(root, "build/demo-${lang.code}/bookmarks.db").isFile
        val demoDir = File(root, "build/demo-${lang.code}").apply { if (!reuse) deleteRecursively(); mkdirs() }
        val dbFile = File(demoDir, "bookmarks.db")
        val (driver, db) = DatabaseFactory.open(dbFile)
        val state = AppState(driver, db, File(demoDir, "media"), dbFile)
        state.changeUiLang(lang)
        // optional: GitHub token from file (-Ddemo.githubTokenFile=…), bypasses the limit of 60 requests/h; removed again afterwards
        System.getProperty("demo.githubTokenFile")?.let(::File)?.takeIf { it.isFile }?.let { state.changeGithubToken(it.readText().trim()) }
        state.changeContentLang(if (lang == de.bookmarksplus.i18n.UiLang.DE) de.bookmarksplus.data.ContentLang.DE else de.bookmarksplus.data.ContentLang.EN)
        if (!reuse) prepare(state, db, root, lang)
        state.changeGithubToken("")
        renderShots(state, out)
    }

    private suspend fun prepare(state: AppState, db: de.bookmarksplus.db.BookmarksDb, root: File, lang: de.bookmarksplus.i18n.UiLang) {
        state.importFile(File(root, if (lang == de.bookmarksplus.i18n.UiLang.DE) "docs/demo/demo-bookmarks.html" else "docs/demo/demo-bookmarks-en.html"))
        state.message = null

        // colored tags
        val tags = TagRepository(db)
        fun tag(name: String, color: Long, vararg urls: String) {
            val id = tags.getOrCreate(name, color)
            tags.add(state.library.items.filter { i -> urls.any { it in i.url } }.map { it.id }, id)
        }
        tag("kotlin", 0xFF8E4EC6, "kotlinlang", "compose-multiplatform", "ktor", "sqldelight", "okhttp")
        tag("open-source", 0xFF30A46C, "github.com", "inkscape", "blender", "keepassxc", "obsproject", "videolan", "lucide")
        tag("cli", 0xFF12A594, "ripgrep", "fzf", "lazygit", "neovim", "git-scm")
        tag("self-hosted", 0xFFF76B15, "immich", "jellyfin", "syncthing", "home-assistant", "nextcloud")
        tag("design", 0xFFD6409F, "penpot", "inkscape", "blender", "fonts.google", "lucide")
        tag("news", 0xFF3E63DD, "ycombinator", "lwn", "heise", "arstechnica")
        state.reload()

        // enrich: metadata, thumbnails, GitHub data, READMEs
        val ids = state.library.items.map { it.id }
        withContext(Dispatchers.IO) {
            ids.forEach { id -> runCatching { state.enricher.enrich(id) }.onFailure { println("Anreichern $id: $it") } }
        }
        state.reload()
        println("Demo: ${state.library.items.size} Lesezeichen, ${state.library.items.count { it.thumbnailPath != null || it.cardImagePath != null }} mit Bild")
    }

    private suspend fun renderShots(state: AppState, out: File) {

        val focus = FocusRequester()
        suspend fun shot(name: String, w: Int = 1440, h: Int = 900) {
            ImageComposeScene(w, h, Density(1f), coroutineContext = Dispatchers.Swing) { App(state, focus, {}, {}) }.use { scene ->
                var t = 0L
                // images load asynchronously → render a few rounds
                repeat(30) { scene.render(t); t += 50_000_000; delay(60) }
                File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }

        val lib = state.library
        state.sidebarExpanded = lib.childrenOf(lib.rootId).map { it.id }.toSet()

        state.changeView(ViewMode.Cards)
        state.filter = Filter()
        shot("desktop-cards")

        val repo = state.results.firstOrNull { it.url.contains("compose-multiplatform") } ?: state.results.first { it.hasReadme }
        state.changeView(ViewMode.List)
        state.select(repo.id)
        shot("desktop-list-detail")
        state.clearSelection()

        state.changeView(ViewMode.Tree)
        state.treeExpanded = lib.childrenOf(lib.rootId).map { it.id }.toSet() +
            lib.childrenOf(lib.childrenOf(lib.rootId).first().id).map { it.id }
        shot("desktop-tree")

        state.changeView(ViewMode.Cards)
        state.filter = Filter(text = "#self-hosted stars:>1k")
        shot("desktop-search")
        state.filter = Filter()

        // sync settings (own server, key set up) as a separate image
        state.changeSyncTarget(SyncTarget.Server)
        state.changeSyncServer("https://bookmarks.example.org", "alex", "secret")
        ImageComposeScene(640, 620, Density(1f), coroutineContext = Dispatchers.Swing) {
            de.bookmarksplus.desktop.ui.AppTheme(state.accent) {
                androidx.compose.material3.Surface(color = de.bookmarksplus.desktop.ui.Palette.SurfaceHigh, contentColor = de.bookmarksplus.desktop.ui.Palette.Text) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(24.dp)) { de.bookmarksplus.desktop.ui.SyncSettings(state) }
                }
            }
        }.use { scene ->
            var t = 0L
            repeat(10) { scene.render(t); t += 50_000_000; delay(30) }
            File(out, "desktop-sync.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        state.changeSyncTarget(SyncTarget.None)
        state.close()
    }
}
