package de.bookmarksplus.netscape

/**
 * Lossless representation of a Netscape bookmark file (Chrome, Vivaldi, Edge, Firefox).
 * Attributes keep their original order and decoded values
 * (exception: HREF and ICON are stored raw, as Chrome writes them).
 */
sealed interface NsNode {
    val title: String
    val attrs: LinkedHashMap<String, String>
}

class NsFolder(
    override var title: String,
    override val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
    val children: MutableList<NsNode> = mutableListOf(),
) : NsNode {
    val addDate: Long? get() = attrs[ADD_DATE]?.toLongOrNull()
    val lastModified: Long? get() = attrs[LAST_MODIFIED]?.toLongOrNull()

    fun walk(): Sequence<NsNode> = sequence {
        for (c in children) {
            yield(c)
            if (c is NsFolder) yieldAll(c.walk())
        }
    }

    override fun toString() = "NsFolder($title, ${children.size} children)"

    companion object {
        const val ADD_DATE = "ADD_DATE"
        const val LAST_MODIFIED = "LAST_MODIFIED"
    }
}

class NsLink(
    override var title: String,
    override val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
    /** Description from a `<DD>` element (Firefox style). Vivaldi uses the DESCRIPTION attribute instead. */
    var dd: String? = null,
) : NsNode {
    val href: String get() = attrs[HREF].orEmpty()

    override fun toString() = "NsLink($title, $href)"

    companion object {
        const val HREF = "HREF"
        const val ICON = "ICON"
        const val DESCRIPTION = "DESCRIPTION"
    }
}

/**
 * Attributes whose values are not HTML-decoded/encoded because Chrome/Vivaldi write them raw.
 * Vivaldi takes DESCRIPTION unfiltered from the meta tags (contains e.g. `<br>` next to `&amp;`);
 * the UI decodes entities when displaying.
 */
internal val RAW_ATTRS = setOf(NsLink.HREF, NsLink.ICON, NsLink.DESCRIPTION, "ICON_URI", "FEEDURL")
