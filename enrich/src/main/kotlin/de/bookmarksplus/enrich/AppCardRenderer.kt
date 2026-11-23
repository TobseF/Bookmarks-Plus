package de.bookmarksplus.enrich

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Thumbnail for applications (e.g. SourceForge projects): instead of a screenshot of the website, a card
 * with project icon, name, short description, categories and the first app screenshot.
 */
object AppCardRenderer {
    const val WIDTH = 1200
    const val HEIGHT = 630

    data class Card(
        val name: String,
        val tagline: String?,
        val categories: List<String> = emptyList(),
        val source: String = "SourceForge",
        val screenshot: ByteArray? = null,
        val icon: ByteArray? = null,
        val accent: Color = Color(0xFF, 0x9A, 0x3C),
        /** Short metric in the footer, e.g. "★ 12.3k". */
        val badge: String? = null,
    )

    /** PNG bytes of the card. */
    fun render(card: Card): ByteArray {
        val img = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        val shot = card.screenshot?.let(::decode)
        val icon = card.icon?.let(::decode)

        // background: darkened, blurred screenshot or dark gradient
        g.paint = GradientPaint(0f, 0f, Color(0x1B, 0x1D, 0x24), WIDTH.toFloat(), HEIGHT.toFloat(), Color(0x10, 0x11, 0x16))
        g.fillRect(0, 0, WIDTH, HEIGHT)
        if (shot != null) {
            val blurred = blur(shot)
            drawCover(g, blurred, 0, 0, WIDTH, HEIGHT)
            g.color = Color(12, 13, 18, 205)
            g.fillRect(0, 0, WIDTH, HEIGHT)
        }
        g.paint = GradientPaint(0f, 0f, Color(14, 15, 20, 235), WIDTH * 0.62f, 0f, Color(14, 15, 20, 40))
        g.fillRect(0, 0, WIDTH, HEIGHT)

        // screenshot on the right in a frame
        val textRight: Int
        if (shot != null) {
            val boxW = 600
            val boxH = 450
            val scale = minOf(boxW.toDouble() / shot.width, boxH.toDouble() / shot.height)
            val w = (shot.width * scale).toInt().coerceAtLeast(1)
            val h = (shot.height * scale).toInt().coerceAtLeast(1)
            val x = WIDTH - 56 - w
            val y = (HEIGHT - h) / 2
            val r = 18.0
            // shadow
            for (i in 1..14) {
                g.color = Color(0, 0, 0, 10)
                g.fill(RoundRectangle2D.Double(x - i * 0.6, y + i * 1.2, w + i * 1.2, h + i * 0.6, r + i, r + i))
            }
            val clip = RoundRectangle2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), r, r)
            val old = g.clip
            g.clip(clip)
            // transparent images (logos, diagrams) on a light background – otherwise dark text disappears
            if (shot.colorModel.hasAlpha()) {
                g.color = Color(0xF6, 0xF8, 0xFA)
                g.fill(clip)
            }
            g.drawImage(shot, x, y, w, h, null)
            g.clip = old
            g.color = Color(255, 255, 255, 40)
            g.stroke = BasicStroke(1.5f)
            g.draw(clip)
            textRight = x - 48
        } else {
            textRight = WIDTH - 72
        }

        // left column: icon, name, description, categories
        val left = 64
        var y = 84
        if (icon != null) {
            val s = 104
            val clip = RoundRectangle2D.Double(left.toDouble(), y.toDouble(), s.toDouble(), s.toDouble(), 22.0, 22.0)
            g.color = Color(255, 255, 255, 235)
            g.fill(clip)
            val old = g.clip
            g.clip(clip)
            val scale = minOf((s - 16).toDouble() / icon.width, (s - 16).toDouble() / icon.height).coerceAtMost(4.0)
            val iw = (icon.width * scale).toInt()
            val ih = (icon.height * scale).toInt()
            g.drawImage(icon, left + (s - iw) / 2, y + (s - ih) / 2, iw, ih, null)
            g.clip = old
            y += s + 34
        } else {
            y += 30
        }

        val maxW = textRight - left
        val titleFont = fitFont(g, card.name, Font.BOLD, 58, 34, maxW)
        g.font = titleFont
        g.color = Color(0xF3, 0xF4, 0xF7)
        val titleLines = wrap(g, card.name, maxW, 2)
        titleLines.forEach { line ->
            y += g.fontMetrics.ascent
            g.drawString(line, left, y)
            y += g.fontMetrics.descent + 4
        }

        card.tagline?.takeIf { it.isNotBlank() }?.let { t ->
            y += 14
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 25)
            g.color = Color(0xC4, 0xC7, 0xD0)
            val lines = wrap(g, t, maxW, if (titleLines.size > 1) 3 else 4)
            lines.forEach { line ->
                y += g.fontMetrics.ascent
                g.drawString(line, left, y)
                y += g.fontMetrics.descent + 6
            }
        }

        // footer: source and categories
        val footY = HEIGHT - 58
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 20)
        g.color = card.accent
        g.fillOval(left, footY - 13, 12, 12)
        g.drawString(card.source, left + 22, footY)
        var x = left + 22 + g.fontMetrics.stringWidth(card.source) + 18
        card.badge?.let { b ->
            g.color = Color(0xE8, 0xC5, 0x4A)
            g.drawString(b, x, footY)
            x += g.fontMetrics.stringWidth(b) + 18
        }
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 19)
        for (c in card.categories.take(4)) {
            val w = g.fontMetrics.stringWidth(c) + 24
            if (x + w > textRight) break
            g.color = Color(255, 255, 255, 26)
            g.fill(RoundRectangle2D.Double(x.toDouble(), (footY - 24).toDouble(), w.toDouble(), 32.0, 16.0, 16.0))
            g.color = Color(0xD8, 0xDA, 0xE0)
            g.drawString(c, x + 12, footY - 2)
            x += w + 8
        }
        g.dispose()

        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): BufferedImage? = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()

    /** "★ 12.3k" – compact star count for the footer. */
    fun stars(n: Long): String = "★ " + when {
        n >= 1_000_000 -> "%.1fM".format(java.util.Locale.GERMAN, n / 1e6)
        n >= 10_000 -> "${n / 1000}k"
        n >= 1_000 -> "%.1fk".format(java.util.Locale.GERMAN, n / 1e3)
        else -> n.toString()
    }

    private fun drawCover(g: Graphics2D, src: BufferedImage, x: Int, y: Int, w: Int, h: Int) {
        val scale = maxOf(w.toDouble() / src.width, h.toDouble() / src.height)
        val dw = (src.width * scale).toInt()
        val dh = (src.height * scale).toInt()
        g.drawImage(src, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null)
    }

    /** Cheap blur: shrink heavily and scale back up. */
    private fun blur(src: BufferedImage): BufferedImage {
        val small = BufferedImage(48, (48.0 * src.height / src.width).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, small.width, small.height, null)
            dispose()
        }
        return small
    }

    private fun fitFont(g: Graphics2D, text: String, style: Int, max: Int, min: Int, width: Int): Font {
        var size = max
        while (size > min) {
            val f = Font(Font.SANS_SERIF, style, size)
            // fits into at most two lines and the longest word into one
            val fm = g.getFontMetrics(f)
            val longest = text.split(' ').maxOfOrNull { fm.stringWidth(it) } ?: 0
            if (longest <= width && fm.stringWidth(text) <= width * 2) return f
            size -= 2
        }
        return Font(Font.SANS_SERIF, style, min)
    }

    private fun wrap(g: Graphics2D, text: String, width: Int, maxLines: Int): List<String> {
        val fm = g.fontMetrics
        val lines = mutableListOf<String>()
        var cur = ""
        val words = text.replace(Regex("\\s+"), " ").trim().split(' ')
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val next = if (cur.isEmpty()) w else "$cur $w"
            if (fm.stringWidth(next) <= width || cur.isEmpty()) {
                cur = next
                i++
            } else {
                lines += cur
                cur = ""
                if (lines.size == maxLines) break
            }
        }
        if (cur.isNotEmpty() && lines.size < maxLines) lines += cur
        val truncated = i < words.size
        if (truncated && lines.isNotEmpty()) {
            var last = lines.last()
            while (last.isNotEmpty() && fm.stringWidth("$last …") > width) last = last.dropLast(1).trimEnd()
            lines[lines.size - 1] = "$last …"
        }
        return lines
    }
}
