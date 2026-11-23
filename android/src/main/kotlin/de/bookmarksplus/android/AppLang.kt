package de.bookmarksplus.android

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import de.bookmarksplus.i18n.I18n
import de.bookmarksplus.i18n.UiLang
import java.util.Locale

/**
 * Language of the app (default: English), independent of the system language. Stored in the SharedPreferences
 * because it is already needed in `attachBaseContext` – before the database is opened.
 */
object AppLang {
    private const val PREFS = "ui"
    private const val KEY = "lang"

    @Volatile private var res: Resources? = null

    fun get(context: Context): UiLang = UiLang.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun set(context: Context, lang: UiLang) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang.code).apply()
        wrap(context.applicationContext)
    }

    /** Context in the chosen language; also sets the language of the shared messages (sync, errors). */
    fun wrap(base: Context): Context {
        val lang = get(base)
        I18n.lang = lang
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(lang.code))
        return base.createConfigurationContext(cfg).also { res = it.resources }
    }

    internal fun resources(): Resources = res ?: Resources.getSystem()
}

/** Text from `strings_ui.xml` in the chosen app language – also usable outside composables. */
fun str(@StringRes id: Int, vararg args: Any?): String =
    if (args.isEmpty()) AppLang.resources().getString(id) else AppLang.resources().getString(id, *args)
