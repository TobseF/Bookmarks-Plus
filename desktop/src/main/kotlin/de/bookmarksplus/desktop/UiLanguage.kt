package de.bookmarksplus.desktop

import androidx.compose.runtime.mutableStateOf
import de.bookmarksplus.i18n.I18n

/**
 * Connects [I18n] with Compose: every translated text reads this state, so that after a
 * language change all composables with texts are redrawn.
 */
object UiLanguage {
    private val state = mutableStateOf(I18n.lang)

    fun install() {
        I18n.onRead = { state.value }
        I18n.onChange { state.value = it }
    }
}
