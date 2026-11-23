package de.bookmarksplus.netscape

/**
 * Tolerant parser for the Netscape bookmark format. Works as a simple scanner
 * instead of an HTML parser because the format is not valid HTML and this way 6,000 entries
 * are read in a few milliseconds.
 */
object NetscapeParser {

    fun parse(html: String): NsFolder {
        val root = NsFolder(title = "Bookmarks")
        val stack = ArrayList<NsFolder>()
        var pendingFolder: NsFolder? = null
        var lastNode: NsNode? = null
        var rootOpened = false
        var i = 0
        val n = html.length

        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) break
            // text after <DD> belongs to the last element
            i = lt + 1
            if (html.startsWith("!--", i)) {
                val end = html.indexOf("-->", i)
                i = if (end < 0) n else end + 3
                continue
            }
            val tagEnd = findTagEnd(html, i)
            val rawTag = html.substring(i, tagEnd)
            i = (tagEnd + 1).coerceAtMost(n)
            val closing = rawTag.startsWith("/")
            val nameEnd = rawTag.indexOfFirst { it.isWhitespace() }.let { if (it < 0) rawTag.length else it }
            val name = rawTag.substring(if (closing) 1 else 0, nameEnd).uppercase()

            when {
                name == "H1" && !closing -> {
                    val end = html.indexOf("</", i).let { if (it < 0) n else it }
                    root.title = decodeEntities(html.substring(i, end).trim())
                    i = end
                }
                name == "H3" && !closing -> {
                    val end = indexOfIgnoreCase(html, "</H3>", i).let { if (it < 0) n else it }
                    val folder = NsFolder(decodeEntities(html.substring(i, end)), parseAttrs(rawTag, nameEnd))
                    (stack.lastOrNull() ?: root).children += folder
                    pendingFolder = folder
                    lastNode = folder
                    i = (end + 5).coerceAtMost(n)
                }
                name == "A" && !closing -> {
                    val end = indexOfIgnoreCase(html, "</A>", i).let { if (it < 0) n else it }
                    val link = NsLink(decodeEntities(html.substring(i, end)), parseAttrs(rawTag, nameEnd))
                    (stack.lastOrNull() ?: root).children += link
                    pendingFolder = null
                    lastNode = link
                    i = (end + 4).coerceAtMost(n)
                }
                name == "DD" && !closing -> {
                    val end = html.indexOf('<', i).let { if (it < 0) n else it }
                    val text = decodeEntities(html.substring(i, end).trim())
                    when (val target = lastNode) {
                        is NsLink -> target.dd = text
                        is NsFolder -> target.attrs["DESCRIPTION"] = text
                        null -> {}
                    }
                    i = end
                }
                name == "DL" && !closing -> {
                    val pf = pendingFolder
                    when {
                        pf != null -> stack += pf
                        !rootOpened -> rootOpened = true
                        else -> stack += (stack.lastOrNull() ?: root) // unexpected <DL>: stay in the current folder
                    }
                    pendingFolder = null
                }
                name == "DL" && closing -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    pendingFolder = null
                }
            }
        }
        return root
    }

    /** Find the end of a tag, ignoring `>` inside quotation marks. */
    private fun findTagEnd(s: String, from: Int): Int {
        var q: Char? = null
        var j = from
        while (j < s.length) {
            val c = s[j]
            if (q != null) {
                if (c == q) q = null
            } else if (c == '"' || c == '\'') {
                q = c
            } else if (c == '>') {
                return j
            }
            j++
        }
        return s.length
    }

    private fun parseAttrs(tag: String, from: Int): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        var j = from
        val n = tag.length
        while (j < n) {
            while (j < n && (tag[j].isWhitespace() || tag[j] == '/')) j++
            val ns = j
            while (j < n && !tag[j].isWhitespace() && tag[j] != '=') j++
            if (ns == j) break
            val key = tag.substring(ns, j).uppercase()
            while (j < n && tag[j].isWhitespace()) j++
            var value = ""
            if (j < n && tag[j] == '=') {
                j++
                while (j < n && tag[j].isWhitespace()) j++
                if (j < n && (tag[j] == '"' || tag[j] == '\'')) {
                    val q = tag[j]
                    val end = tag.indexOf(q, j + 1).let { if (it < 0) n else it }
                    value = tag.substring(j + 1, end)
                    j = end + 1
                } else {
                    val vs = j
                    while (j < n && !tag[j].isWhitespace()) j++
                    value = tag.substring(vs, j)
                }
            }
            map[key] = if (key in RAW_ATTRS) value.replace("&quot;", "\"") else decodeEntities(value)
        }
        return map
    }

    private fun indexOfIgnoreCase(s: String, needle: String, from: Int): Int {
        val idx = s.indexOf(needle, from)
        if (idx >= 0) return idx
        return s.indexOf(needle, from, ignoreCase = true)
    }

    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 2)..(i + 10)) {
                    val ent = s.substring(i + 1, semi)
                    val decoded: String? = when {
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let(::codePointToString)
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let(::codePointToString)
                        else -> NAMED_ENTITIES[ent]
                    }
                    if (decoded != null) {
                        sb.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun codePointToString(cp: Int): String = when {
        cp < 0 || cp > 0x10FFFF -> "�"
        cp < 0x10000 -> cp.toChar().toString()
        else -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    )
}
