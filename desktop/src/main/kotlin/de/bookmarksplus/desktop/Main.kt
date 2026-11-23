package de.bookmarksplus.desktop

import de.bookmarksplus.i18n.tr
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.desktop.ui.App
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

val appDir = File(System.getProperty("user.home"), ".bookmarks-plus")

private object Main

private fun java.awt.image.BufferedImage.toPainter() = BitmapPainter(toComposeImageBitmap())

fun main() = application {
    remember { UiLanguage.install() }
    val state = remember {
        val dbFile = File(appDir, "bookmarks.db")
        // apply prepared backup before opening
        de.bookmarksplus.sync.Backups.applyPendingRestore(dbFile, File(appDir, "backups"))
        val (driver, db) = DatabaseFactory.open(dbFile)
        AppState(driver, db, dbFile = dbFile).also { it.startApi() }
    }
    val searchFocus = remember { FocusRequester() }
    Window(
        onCloseRequest = { state.close(); exitApplication() },
        title = "Bookmarks Plus",
        icon = remember { Main::class.java.getResourceAsStream("/icon.png")?.use { javax.imageio.ImageIO.read(it) }?.toPainter() },
        state = rememberWindowState(size = DpSize(1480.dp, 920.dp)),
        onPreviewKeyEvent = { e ->
            when {
                e.type != KeyEventType.KeyDown -> false
                e.isCtrlPressed && e.key == Key.F -> { runCatching { searchFocus.requestFocus() }; true }
                e.isCtrlPressed && e.key == Key.N -> { state.startAdd(); true }
                e.key == Key.Escape && state.selection.isNotEmpty() -> { state.clearSelection(); true }
                else -> false
            }
        },
        // keys not consumed by text fields: Del and F2 act on the selection
        onKeyEvent = { e ->
            when {
                e.type != KeyEventType.KeyDown || state.editingId != null -> false
                e.key == Key.Delete && state.selection.isNotEmpty() -> { state.deleteSelection(); true }
                e.key == Key.F2 && state.selection.size == 1 -> { state.renameSelection(); true }
                e.isCtrlPressed && e.key == Key.A -> { state.selectAll(); true }
                e.isCtrlPressed && e.key == Key.V -> { state.startAdd(); state.addRequest?.url?.isNotEmpty() == true || run { state.addRequest = null; false } }
                else -> false
            }
        },
    ) {
        App(
            state, searchFocus,
            onImport = { pickFile(window, tr("menu.importHtml"), FileDialog.LOAD)?.let(state::importFile) },
            onExport = { pickFile(window, tr("menu.exportHtml"), FileDialog.SAVE)?.let(state::exportFile) },
        )
    }
}

private fun pickFile(parent: Frame, title: String, mode: Int): File? {
    val dialog = FileDialog(parent, title, mode).apply {
        if (mode == FileDialog.SAVE) file = "bookmarks_export.html" else setFilenameFilter { _, n -> n.endsWith(".html", true) || n.endsWith(".htm", true) }
        isVisible = true
    }
    return dialog.file?.let { File(dialog.directory, it) }
}
