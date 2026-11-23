package de.bookmarksplus.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Target for "Share" from other apps (browser, YouTube, Reddit …). Takes the link from the shared text,
 * puts it into the inbox and triggers a sync; the desktop then enriches it (preview, AI).
 */
class ShareActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(AppLang.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = SharedLink.from(intent)
        if (shared == null) {
            Toast.makeText(this, str(R.string.share_no_link), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val app = app
        app.scope.launch {
            val text = try {
                val r = withContext(Dispatchers.IO) {
                    app.store.addToInbox(shared.url, shared.title).also { app.store.reload() }
                }
                SyncWorker.once(app, app.store)
                if (r.created) str(R.string.share_saved) else str(R.string.share_exists)
            } catch (e: Exception) {
                str(R.string.share_not_saved, e.message ?: e::class.simpleName)
            }
            withContext(Dispatchers.Main) { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
        }
        finish()
    }
}

/** Link and title from an ACTION_SEND intent. */
data class SharedLink(val url: String, val title: String) {
    companion object {
        private val URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

        fun from(intent: Intent?): SharedLink? {
            if (intent?.action != Intent.ACTION_SEND) return null
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
            return parse(text, subject)
        }

        fun parse(text: String, subject: String = ""): SharedLink? {
            val url = URL.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', ':') ?: return null
            // some apps share "title\nURL" instead of a subject
            val rest = text.replace(url, "").trim().lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val title = subject.ifBlank { rest }.take(300)
            return SharedLink(url, title)
        }
    }
}
