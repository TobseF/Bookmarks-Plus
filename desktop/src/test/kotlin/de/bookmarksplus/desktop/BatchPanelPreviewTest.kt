package de.bookmarksplus.desktop

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.desktop.ui.AppTheme
import de.bookmarksplus.desktop.ui.BatchPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class BatchPanelPreviewTest {
    @Test
    fun render() = runBlocking(Dispatchers.Swing) {
        val out = File(System.getProperty("preview.dir") ?: "build/batch-preview").apply { mkdirs() }
        val (driver, db) = DatabaseFactory.open(null)
        val state = AppState(driver, db, File(out, "media"))
        val now = System.currentTimeMillis()
        val cases = listOf(
            "0-idle" to null,
            "1-running" to BatchStatus(BatchKind.Enrich, "Thumbnails aktualisieren", 42, 120, current = "github.com", errors = 2),
            "2-paused" to BatchStatus(BatchKind.Ai, "KI-Auswertung", 17, 80, paused = true),
            "3-done" to BatchStatus(BatchKind.Enrich, "Neu laden", 120, 120, phase = BatchPhase.Done, summary = "118 von 120 aktualisiert", startedAt = now - 95_000, endedAt = now),
            "4-cancelled" to BatchStatus(BatchKind.Links, "Alle Links prüfen", 300, 1450, phase = BatchPhase.Cancelled, summary = "12 tot · 40 Weiterleitungen"),
            "5-apps" to BatchStatus(BatchKind.Apps, "App-Daten aktualisieren", 5, 60, step = "Flathub", current = "Blender"),
        )
        for ((name, b) in cases) {
            state.batch = b
            ImageComposeScene(390, 160, Density(1.5f), coroutineContext = Dispatchers.Swing) {
                AppTheme(state.accent) { Column(Modifier.width(260.dp).background(de.bookmarksplus.desktop.ui.Palette.Sidebar).padding(top = 10.dp)) { BatchPanel(state) } }
            }.use { scene ->
                var t = 0L
                repeat(3) { scene.render(t); t += 50_000_000; delay(20) }
                File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
        // if a batch is running, no new one is started
        state.batch = BatchStatus(BatchKind.Enrich, "Neu laden", 1, 10)
        state.checkLinks(listOf(1L, 2L))
        assertEquals("Neu laden", state.batch?.label)
        state.dismissBatch()
        assertEquals("Neu laden", state.batch?.label)
        state.batch = state.batch!!.copy(phase = BatchPhase.Done)
        state.dismissBatch()
        assertEquals(null, state.batch)
    }
}
