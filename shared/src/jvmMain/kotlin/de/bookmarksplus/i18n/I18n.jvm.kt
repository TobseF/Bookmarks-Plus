package de.bookmarksplus.i18n

import java.util.Properties

internal actual fun loadBundle(name: String): Map<String, String> {
    val stream = I18n::class.java.classLoader?.getResourceAsStream("i18n/$name.properties")
        ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("i18n/$name.properties")
        ?: return emptyMap()
    val p = Properties()
    stream.reader(Charsets.UTF_8).use { p.load(it) }
    return p.stringPropertyNames().associateWith { p.getProperty(it) }
}
