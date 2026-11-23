package de.bookmarksplus.desktop

import de.bookmarksplus.i18n.tr
import org.jetbrains.skia.EncodedImageFormat
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Fetch images from disk or clipboard and make them readable. */
object ImageInput {
    /** Larger files are not even read. */
    const val MAX_FILE_BYTES = 40L shl 20

    private val EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /** File dialog for an image; null on cancel. */
    fun pickFile(title: String = tr("image.choose")): File? {
        val d = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            setFilenameFilter { _, n -> n.substringAfterLast('.', "").lowercase() in EXT }
            isVisible = true
        }
        val name = d.file ?: return null
        return File(d.directory, name)
    }

    /** Image from the clipboard: copied image or (in Explorer) copied image file. */
    fun fromClipboard(): ByteArray? = runCatching {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        when {
            cb.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
                val img = cb.getData(DataFlavor.imageFlavor) as java.awt.Image
                val bi = BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB)
                bi.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
                ByteArrayOutputStream().also { ImageIO.write(bi, "png", it) }.toByteArray()
            }
            cb.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> {
                @Suppress("UNCHECKED_CAST")
                val files = cb.getData(DataFlavor.javaFileListFlavor) as List<File>
                files.firstOrNull { it.extension.lowercase() in EXT }?.let(::readFile)
            }
            else -> null
        }
    }.getOrNull()

    fun readFile(f: File): ByteArray? = f.takeIf { it.isFile && it.length() in 1..MAX_FILE_BYTES }?.readBytes()

    /**
     * Makes bytes readable for the MediaStore: formats that Java does not know (e.g. WebP) are converted to PNG via Skia.
     * Downscaling to the target size is then done by the MediaStore.
     */
    fun readable(raw: ByteArray): ByteArray? {
        if (raw.isEmpty() || raw.size > MAX_FILE_BYTES) return null
        if (runCatching { ImageIO.read(raw.inputStream()) }.getOrNull() != null) return raw
        return runCatching {
            org.jetbrains.skia.Image.makeFromEncoded(raw).encodeToData(EncodedImageFormat.PNG)?.bytes
        }.getOrNull()
    }
}
