package de.bookmarksplus.netscape

/**
 * Writes the Netscape format exactly as Chrome/Vivaldi export it,
 * so that the file can be imported into the browser again.
 */
object NetscapeWriter {

    fun write(root: NsFolder, lineSeparator: String = "\r\n"): String {
        val sb = StringBuilder(1 shl 20)
        fun line(indent: Int, text: String) {
            repeat(indent) { sb.append("    ") }
            sb.append(text).append(lineSeparator)
        }
        line(0, "<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        line(0, "<!-- This is an automatically generated file.")
        line(0, "     It will be read and overwritten.")
        line(0, "     DO NOT EDIT! -->")
        line(0, "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        line(0, "<TITLE>${escape(root.title)}</TITLE>")
        line(0, "<H1>${escape(root.title)}</H1>")
        line(0, "<DL><p>")

        fun writeChildren(folder: NsFolder, depth: Int) {
            for (node in folder.children) {
                when (node) {
                    is NsFolder -> {
                        line(depth, "<DT><H3${attrs(node.attrs)}>${escape(node.title)}</H3>")
                        line(depth, "<DL><p>")
                        writeChildren(node, depth + 1)
                        line(depth, "</DL><p>")
                    }
                    is NsLink -> {
                        line(depth, "<DT><A${attrs(node.attrs)}>${escape(node.title)}</A>")
                        node.dd?.let { line(depth, "<DD>${escape(it)}") }
                    }
                }
            }
        }
        writeChildren(root, 1)
        line(0, "</DL><p>")
        return sb.toString()
    }

    private fun attrs(map: Map<String, String>): String = buildString {
        for ((k, v) in map) {
            append(' ').append(k).append("=\"")
            append(if (k in RAW_ATTRS) v.replace("\"", "&quot;") else escape(v))
            append('"')
        }
    }

    /** Like Chrome's `base::EscapeForHTML`. */
    fun escape(s: String): String {
        if (s.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return s
        return buildString(s.length + 16) {
            for (c in s) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
