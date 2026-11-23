package de.bookmarksplus.i18n

/** Language of the UI and messages. English is the default. */
enum class UiLang(val code: String, val label: String) {
    EN("en", "English"), DE("de", "Deutsch");

    companion object {
        val DEFAULT = EN
        fun of(code: String?): UiLang = entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) || it.name.equals(code?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Message texts from `i18n/<bundle>.properties` (English) or `i18n/<bundle>_de.properties` (German).
 * Placeholders `{0}`, `{1}` … are replaced by the arguments. If a key is missing in the chosen language,
 * the English text applies, otherwise the key itself.
 */
object I18n {
    /** Bundles of the modules – missing ones (e.g. "desktop" on Android) are skipped. */
    private val BUNDLES = listOf("shared", "sync", "enrich", "desktop")

    @kotlin.concurrent.Volatile
    var lang: UiLang = UiLang.DEFAULT
        set(value) { field = value; listeners.forEach { it(value) } }

    private val listeners = mutableListOf<(UiLang) -> Unit>()
    fun onChange(listener: (UiLang) -> Unit) { listeners += listener }

    private val tables: Map<UiLang, Lazy<Map<String, String>>> = UiLang.entries.associateWith { l ->
        lazy { HashMap<String, String>().also { m -> BUNDLES.forEach { m.putAll(loadBundle(if (l == UiLang.EN) it else "${it}_${l.code}")) } } }
    }

    private fun table(l: UiLang): Map<String, String> = tables.getValue(l).value

    fun has(key: String): Boolean = table(lang).containsKey(key) || table(UiLang.EN).containsKey(key)

    fun tr(key: String, vararg args: Any?): String = trIn(lang, key, *args)

    /** Called on every lookup – the desktop app reads a Compose state here so that texts are redrawn on language change. */
    var onRead: (() -> Unit)? = null

    fun trIn(l: UiLang, key: String, vararg args: Any?): String {
        onRead?.invoke()
        val pattern = table(l)[key] ?: table(UiLang.EN)[key] ?: return key
        if (args.isEmpty()) return pattern
        val sb = StringBuilder(pattern.length + 16)
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '{') {
                val end = pattern.indexOf('}', i)
                val idx = if (end > i) pattern.substring(i + 1, end).toIntOrNull() else null
                if (idx != null && idx in args.indices) { sb.append(args[idx]); i = end + 1; continue }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }
}

/** Shorthand for [I18n.tr]. */
fun tr(key: String, vararg args: Any?): String = I18n.tr(key, *args)

/** Reads a bundle from the resources; empty if it does not exist. */
internal expect fun loadBundle(name: String): Map<String, String>
