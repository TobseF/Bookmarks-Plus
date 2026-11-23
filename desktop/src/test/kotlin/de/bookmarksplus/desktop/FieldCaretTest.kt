package de.bookmarksplus.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import de.bookmarksplus.desktop.ui.Palette
import de.bookmarksplus.desktop.ui.PlainField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cursor and placeholder in the empty field must sit at the same height: the vertical center of the cursor
 * (accent color) and that of the placeholder text may be at most 2 px (1 dp) apart.
 */
class FieldCaretTest {
    @Test
    fun caretCenteredOnPlaceholder() = runBlocking(Dispatchers.Swing) {
        for (size in listOf(12, 13)) {
            val focus = FocusRequester()
            ImageComposeScene(300, 100, Density(2f), coroutineContext = Dispatchers.Swing) {
                de.bookmarksplus.desktop.ui.AppTheme(androidx.compose.ui.graphics.Color(0xFFE5484D)) {
                    Row(Modifier.background(Palette.SurfaceHigh).padding(8.dp).height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(4.dp))
                        PlainField("", {}, Modifier.fillMaxWidth().focusRequester(focus), placeholder = "Suchen Hg", fontSize = size.sp)
                    }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }.use { scene ->
                scene.render(0); delay(50)
                val img = scene.render(10_000_000)
                val bytes = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }.resolve("caret-$size.png").writeBytes(bytes)
                val bmp = javax.imageio.ImageIO.read(bytes.inputStream())
                val bg = bmp.getRGB(2, 20)
                // cursor: first column from x=24 px (padding 8dp + spacer 4dp at density 2) with non-gray pixels
                fun isAccent(c: Int): Boolean {
                    val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                    return maxOf(r, g, b) - minOf(r, g, b) > 60
                }
                fun isInk(c: Int): Boolean = c != bg && !isAccent(c)
                val caretRows = (0 until bmp.height).filter { y -> (20 until 34).any { x -> isAccent(bmp.getRGB(x, y)) } }
                val textRows = (0 until bmp.height).filter { y -> bmp.getRGB(2, y) == bg && (40 until 200).any { x -> isInk(bmp.getRGB(x, y)) } }
                assertTrue(caretRows.isNotEmpty() && textRows.isNotEmpty(), "caret=$caretRows text=$textRows")
                val caretMid = (caretRows.first() + caretRows.last()) / 2.0
                val textMid = (textRows.first() + textRows.last()) / 2.0
                println("size $size: caret ${caretRows.first()}..${caretRows.last()} text ${textRows.first()}..${textRows.last()}")
                assertTrue(kotlin.math.abs(caretMid - textMid) <= 2.0, "size $size: caret ${caretRows.first()}..${caretRows.last()} vs text ${textRows.first()}..${textRows.last()}")
            }
        }
    }
}
