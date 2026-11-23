package de.bookmarksplus.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import de.bookmarksplus.desktop.AppState
import de.bookmarksplus.netscape.NetscapeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import java.net.URI
import org.commonmark.node.Image as MdImageNode
import org.commonmark.node.Text as MdText

/** Base URLs against which relative links and images of a README are resolved. */
data class MdBase(val links: String?, val images: String?) {
    fun link(dest: String): String? = resolve(dest, links)
    fun image(dest: String): String? = resolve(dest, images)

    private fun resolve(dest: String, base: String?): String? {
        val d = dest.trim()
        if (d.isEmpty() || d.startsWith("#")) return null
        if (d.startsWith("http://") || d.startsWith("https://") || d.startsWith("mailto:")) return d
        if (d.startsWith("//")) return "https:$d"
        base ?: return null
        return runCatching { URI(base).resolve(d.removePrefix("/").replace(" ", "%20")).toString() }.getOrNull()
    }

    companion object {
        /** For a GitHub repo: links to github.com/blob, images from raw.githubusercontent.com. */
        fun github(owner: String, repo: String) =
            MdBase("https://github.com/$owner/$repo/blob/HEAD/", "https://raw.githubusercontent.com/$owner/$repo/HEAD/")
    }
}

private val parser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create()))
    .build()

private val IMG = Regex("""<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
private val ALT = Regex("""\balt\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val HTML_BREAK = Regex("""<br\s*/?>|</p>|</div>|</h\d>|</li>|</tr>""", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("""<[^>]{1,500}>""")

private data class MdImg(val url: String, val alt: String)

/** Renders Markdown (CommonMark + GFM tables) as Compose elements; HTML is reduced to text and images. */
@Composable
fun Markdown(state: AppState, markdown: String, base: MdBase, modifier: Modifier = Modifier) {
    val doc = remember(markdown) { parser.parse(markdown) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Blocks(state, doc, base) }
    }
}

@Composable
private fun Blocks(state: AppState, parent: Node, base: MdBase) {
    var n = parent.firstChild
    while (n != null) {
        Block(state, n, base)
        n = n.next
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Block(state: AppState, n: Node, base: MdBase) {
    val accent = MaterialTheme.colorScheme.primary
    when (n) {
        is Heading -> {
            val (text, imgs) = inline(n, base, accent)
            val size = when (n.level) { 1 -> 20.sp; 2 -> 17.sp; 3 -> 15.sp; else -> 14.sp }
            Images(state, imgs)
            if (text.isNotBlank()) Text(text, fontSize = size, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.padding(top = 6.dp))
            if (n.level <= 2) HorizontalDivider(color = Palette.OutlineSoft)
        }
        is Paragraph -> {
            val (text, imgs) = inline(n, base, accent)
            Images(state, imgs)
            if (text.isNotBlank()) Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = Palette.TextMuted)
        }
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var i = (n as? OrderedList)?.markerStartNumber ?: 1
            var item = n.firstChild
            while (item != null) {
                if (item is ListItem) {
                    val marker = if (n is OrderedList) "${i++}." else "•"
                    Row {
                        Text(marker, fontSize = 13.sp, color = Palette.TextFaint, modifier = Modifier.width(if (n is OrderedList) 22.dp else 14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Blocks(state, item, base) }
                    }
                }
                item = item.next
            }
        }
        is FencedCodeBlock -> CodeBox(n.literal)
        is IndentedCodeBlock -> CodeBox(n.literal)
        is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Palette.Outline))
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Blocks(state, n, base) }
        }
        is ThematicBreak -> HorizontalDivider(color = Palette.Outline)
        is HtmlBlock -> HtmlFragment(state, n.literal, base)
        is TableBlock -> Table(state, n, base, accent)
        else -> Blocks(state, n, base)
    }
}

@Composable
private fun HtmlFragment(state: AppState, html: String, base: MdBase) {
    val imgs = IMG.findAll(html).mapNotNull { m ->
        base.image(m.groupValues[1])?.let { MdImg(it, ALT.find(m.value)?.groupValues?.get(1).orEmpty()) }
    }.toList()
    Images(state, imgs)
    val text = NetscapeParser.decodeEntities(html.replace(HTML_BREAK, "\n").replace(HTML_TAG, ""))
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    if (text.isNotBlank()) Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = Palette.TextMuted)
}

@Composable
private fun CodeBox(code: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Background)
            .border(1.dp, Palette.OutlineSoft, RoundedCornerShape(6.dp)).horizontalScroll(rememberScrollState()).padding(10.dp),
    ) {
        Text(code.trimEnd(), fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace, color = Palette.Text, softWrap = false)
    }
}

@Composable
private fun Table(state: AppState, t: TableBlock, base: MdBase, accent: Color) {
    Column(Modifier.fillMaxWidth().border(1.dp, Palette.OutlineSoft, RoundedCornerShape(6.dp))) {
        var section = t.firstChild
        while (section != null) {
            val head = section is TableHead
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) Row(Modifier.fillMaxWidth().background(if (head) Palette.SurfaceHigh else Color.Transparent).height(IntrinsicSize.Min)) {
                    var cell = row.firstChild
                    while (cell != null) {
                        if (cell is TableCell) {
                            val (text, _) = inline(cell, base, accent)
                            Text(
                                text, fontSize = 12.sp, lineHeight = 16.sp, color = if (head) Palette.Text else Palette.TextMuted,
                                fontWeight = if (head) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f).padding(6.dp),
                            )
                        }
                        cell = cell.next
                    }
                }
                HorizontalDivider(color = Palette.OutlineSoft)
                row = row.next
            }
            section = section.next
        }
    }
}

/** Inline content as AnnotatedString; images are collected and displayed separately. */
private fun inline(parent: Node, base: MdBase, accent: Color): Pair<AnnotatedString, List<MdImg>> {
    val images = ArrayList<MdImg>()
    val text = buildAnnotatedString {
        fun visit(n: Node) {
            when (n) {
                is MdText -> append(n.literal)
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Palette.SurfaceHighest, color = Palette.Text)) { append(n.literal) }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children(n, ::visit) }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Palette.Text)) { children(n, ::visit) }
                is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children(n, ::visit) }
                is Link -> {
                    val url = base.link(n.destination)
                    if (url != null) withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = accent)))) { children(n, ::visit) }
                    else children(n, ::visit)
                }
                is MdImageNode -> base.image(n.destination)?.let { images += MdImg(it, n.title ?: "") }
                is SoftLineBreak -> append(' ')
                is HardLineBreak -> append('\n')
                is HtmlInline -> {
                    val lit = n.literal
                    when {
                        lit.startsWith("<br", true) -> append('\n')
                        lit.startsWith("<img", true) -> IMG.find(lit)?.let { m -> base.image(m.groupValues[1])?.let { images += MdImg(it, "") } }
                    }
                }
                else -> children(n, ::visit)
            }
        }
        children(parent, ::visit)
    }
    return text to images
}

private inline fun children(n: Node, f: (Node) -> Unit) {
    var c = n.firstChild
    while (c != null) {
        val next = c.next
        f(c)
        c = next
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Images(state: AppState, images: List<MdImg>) {
    if (images.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        images.forEach { RemoteImageView(state, it) }
    }
}

@Composable
private fun RemoteImageView(state: AppState, img: MdImg) {
    val loaded by produceState(state.cachedRemoteImage(img.url), img.url) {
        if (value == null) value = withContext(Dispatchers.IO) { state.loadRemoteImage(img.url) }
    }
    val r = loaded ?: return
    BoxWithConstraints {
        val w = min(r.width.dp, maxWidth)
        val h = w * (r.height / r.width.coerceAtLeast(1f))
        Image(r.bitmap, img.alt, Modifier.size(w, h))
    }
}
