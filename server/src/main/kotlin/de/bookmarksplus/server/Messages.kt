package de.bookmarksplus.server

import java.util.Properties

/**
 * Server messages from `i18n/server.properties` (English, default) or `i18n/server_de.properties`.
 * Language via environment variable `BMP_LANG` (`en` or `de`).
 */
object Messages {
    val lang: String = System.getenv("BMP_LANG")?.trim()?.lowercase()?.takeIf { it == "de" || it == "en" } ?: "en"

    private fun load(name: String): Map<String, String> {
        val stream = Messages::class.java.classLoader.getResourceAsStream("i18n/$name.properties") ?: return emptyMap()
        val p = Properties()
        stream.reader(Charsets.UTF_8).use { p.load(it) }
        return p.stringPropertyNames().associateWith { p.getProperty(it) }
    }

    private val en = load("server")
    private val table = if (lang == "en") en else en + load("server_$lang")

    fun get(key: String, vararg args: Any?): String {
        var s = table[key] ?: return key
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
        return s
    }
}

fun msg(key: String, vararg args: Any?): String = Messages.get(key, *args)
