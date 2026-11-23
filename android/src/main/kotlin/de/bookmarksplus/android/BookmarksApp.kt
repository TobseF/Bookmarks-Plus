package de.bookmarksplus.android

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BookmarksApp : Application() {
    /** Process-wide data access (main view, sharing and background sync use the same instance). */
    val store: BookmarkStore by lazy { BookmarkStore(this) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AppLang.wrap(base)
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch(Dispatchers.IO) { SyncWorker.schedule(this@BookmarksApp, store) }
    }
}

val Context.app: BookmarksApp get() = applicationContext as BookmarksApp
