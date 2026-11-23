package de.bookmarksplus.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import de.bookmarksplus.android.ui.AppTheme
import de.bookmarksplus.android.ui.BookmarksUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(AppLang.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        // app is always dark → light icons in status and navigation bar
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val store = app.store
        lifecycleScope.launch(Dispatchers.IO) { if (!store.loaded.value) store.reload() }
        setContent {
            val accent = remember { mutableStateOf(Color(store.accent)) }
            AppTheme(accent.value) {
                BookmarksUi(store, accent = accent.value, onAccent = { accent.value = it; store.accent = it.toArgb().toLong() and 0xFFFFFFFFL })
            }
        }
    }
}
