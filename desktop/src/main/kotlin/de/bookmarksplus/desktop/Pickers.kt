package de.bookmarksplus.desktop

import de.bookmarksplus.i18n.tr
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/** File and folder selection of the system. */
object Pickers {
    fun saveFile(title: String, name: String): File? {
        val d = FileDialog(null as Frame?, title, FileDialog.SAVE).apply { file = name; isVisible = true }
        return d.file?.let { File(d.directory, it) }
    }

    fun folder(title: String): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showDialog(null, tr("common.choose")) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    /** `.bmpx` file or `keyinfo.json` of an export folder. */
    fun encryptedSource(): File? {
        val d = FileDialog(null as Frame?, tr("picker.chooseExport"), FileDialog.LOAD).apply {
            setFilenameFilter { _, n -> n.endsWith(".bmpx", true) || n == "keyinfo.json" }
            isVisible = true
        }
        return d.file?.let { File(d.directory, it) }
    }
}
