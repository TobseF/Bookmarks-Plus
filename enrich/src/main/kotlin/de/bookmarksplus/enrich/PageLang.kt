package de.bookmarksplus.enrich

import de.bookmarksplus.i18n.I18n
import de.bookmarksplus.i18n.UiLang

/** Preferred language when fetching pages and screenshots – follows the UI language. */
object PageLang {
    val acceptLanguage: String
        get() = if (I18n.lang == UiLang.DE) "de-DE,de;q=0.9,en;q=0.8" else "en-US,en;q=0.9,de;q=0.8"

    val locale: String
        get() = if (I18n.lang == UiLang.DE) "de-DE" else "en-US"
}
