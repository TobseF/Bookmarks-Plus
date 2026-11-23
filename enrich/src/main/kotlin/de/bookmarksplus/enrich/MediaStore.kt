package de.bookmarksplus.enrich

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Images (thumbnails, card images) as files in the app folder; the DB stores only the path.
 * Large images are shrunk to [maxWidth] and stored as JPEG so that 6,000 previews stay small.
 */
class MediaStore(private val root: File, private val maxWidth: Int = 720) {

    /**
     * [maxWidth]/[maxHeight] limit the size (aspect ratio is kept); larger images are shrunk
     * so that they do not take unnecessarily long to load. Custom = own thumbnail (upload/clipboard).
     */
    enum class Kind(val dir: String, val maxWidth: Int? = null, val maxHeight: Int? = null) {
        Thumbnail("thumbs"), Card("cards"), Screenshot("shots", 1280, 2560), Custom("custom", 960, 1920),
    }

    /** Stores the image and returns the absolute path; null if it is not a displayable image. */
    fun save(kind: Kind, key: String, bytes: ByteArray): String? {
        val mime = ImageSniffer.mime(bytes) ?: return null
        if (mime == "image/svg+xml" || mime == "image/x-icon") return null
        val dir = File(root, kind.dir).apply { mkdirs() }
        // remove previous files with the same key
        dir.listFiles { f -> f.nameWithoutExtension == key }?.forEach { it.delete() }

        val img = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()
        if (img == null) {
            // e.g. WebP/AVIF: store unchanged, Skia can decode them in the app
            if (bytes.size > 3 shl 20) return null
            return File(dir, "$key.${ImageSniffer.extension(mime)}").apply { writeBytes(bytes) }.absolutePath
        }
        val out = File(dir, "$key.jpg")
        out.writeBytes(toJpeg(scale(img, kind.maxWidth ?: maxWidth, kind.maxHeight)))
        return out.absolutePath
    }

    fun delete(path: String?) {
        path?.let { File(it).takeIf { f -> f.isFile && f.absolutePath.startsWith(root.absolutePath) }?.delete() }
    }

    private fun scale(src: BufferedImage, limit: Int, maxHeight: Int? = null): BufferedImage {
        var w = minOf(src.width, limit)
        // additionally limit the height (very long page screenshots)
        if (maxHeight != null && src.height * (w.toDouble() / src.width) > maxHeight) w = (src.width * (maxHeight.toDouble() / src.height)).toInt().coerceAtLeast(1)
        val h = (src.height * (w.toDouble() / src.width)).toInt().coerceAtLeast(1)
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = java.awt.Color(0x17, 0x17, 0x1C) // background for transparent PNGs
        g.fillRect(0, 0, w, h)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return dst
    }

    private fun toJpeg(img: BufferedImage, quality: Float = 0.84f): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bos = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bos).use { ios ->
            writer.output = ios
            val p = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
            writer.write(null, IIOImage(img, null, null), p)
        }
        writer.dispose()
        return bos.toByteArray()
    }
}
