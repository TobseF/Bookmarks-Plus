package de.bookmarksplus.android

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import de.bookmarksplus.data.BookmarkRepository
import de.bookmarksplus.data.ContentLang
import de.bookmarksplus.data.DatabaseFactory
import de.bookmarksplus.data.Filter
import de.bookmarksplus.data.FilterEngine
import de.bookmarksplus.data.Item
import de.bookmarksplus.data.Library
import de.bookmarksplus.data.ParsedQuery
import de.bookmarksplus.data.SearchIndex
import de.bookmarksplus.data.SettingsRepository
import de.bookmarksplus.db.BookmarksDb
import de.bookmarksplus.sync.FileRecordStore
import de.bookmarksplus.sync.RecordStore
import de.bookmarksplus.sync.ServerRecordStore
import de.bookmarksplus.sync.Crypto
import de.bookmarksplus.sync.FirstSyncDecisionNeeded
import de.bookmarksplus.sync.FirstSyncMode
import de.bookmarksplus.sync.FtpStore
import de.bookmarksplus.sync.Secrets
import de.bookmarksplus.sync.SyncEngine
import de.bookmarksplus.sync.SyncTargetException
import de.bookmarksplus.util.Urls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Base64
import java.util.Date

/** Sync targets that make sense on a phone (a local folder is dropped). */
enum class SyncTarget(@androidx.annotation.StringRes private val labelRes: Int) {
    None(R.string.sync_target_none), Server(R.string.sync_target_server), Ftp(R.string.sync_target_ftp);

    val label: String get() = str(labelRes)
}

sealed interface SyncOutcome {
    data class Done(val text: String, val pulled: Int) : SyncOutcome
    /** First sync, but bookmarks already exist locally (e.g. shared links) → user decides. */
    data object NeedsFirstSyncChoice : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
    data object NotConfigured : SyncOutcome
}

data class AddResult(val id: Long, val created: Boolean)

/**
 * Data of the Android app: the same SQLite database (schema, sync triggers, full-text index) as on the desktop.
 * Enrichment, screenshots and AI run on the desktop only – the phone displays, opens and collects new links in the inbox.
 */
class BookmarkStore(context: Context) {
    private val appDir: File = context.filesDir
    val dbFile = File(appDir, "bookmarks.db")
    private val mediaDir = File(appDir, "media")
    private val driver: SqlDriver
    val db: BookmarksDb

    init {
        // sqlite-jdbc otherwise registers itself only via ServiceLoader
        Class.forName("org.sqlite.JDBC")
        val (d, b) = DatabaseFactory.open(dbFile)
        driver = d
        db = b
    }

    val repo = BookmarkRepository(db)
    private val settings = SettingsRepository(db)
    private val secrets = Secrets(File(context.noBackupFilesDir, "secret.key"))
    private val index = SearchIndex(driver).also { runCatching { it.ensure() } }

    private val _library = MutableStateFlow(Library.EMPTY)
    val library: StateFlow<Library> = _library.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _syncRunning = MutableStateFlow(false)
    val syncRunning: StateFlow<Boolean> = _syncRunning.asStateFlow()
    private val _syncStatus = MutableStateFlow(settings.get("syncCfg.status"))
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()
    private val syncMutex = Mutex()

    // ------------------------------------------------------------------------------------------- Settings

    private fun secret(key: String): String = secrets.reveal(settings.get(key))
    private fun putSecret(key: String, value: String) = settings.put(key, secrets.protect(value))

    var contentLang: ContentLang
        get() = when (settings.get("contentLang")) { "en" -> ContentLang.EN; "de" -> ContentLang.DE; else -> if (de.bookmarksplus.i18n.I18n.lang == de.bookmarksplus.i18n.UiLang.DE) ContentLang.DE else ContentLang.EN }
        set(v) { settings.put("contentLang", v.code) }

    var accent: Long
        get() = settings.get("android.accent")?.toLongOrNull() ?: 0xFFE5484D
        set(v) { settings.put("android.accent", v.toString()) }

    var cardView: Boolean
        get() = settings.get("android.cards") == "true"
        set(v) { settings.put("android.cards", v.toString()) }

    var syncTarget: SyncTarget
        get() = runCatching { SyncTarget.valueOf(settings.get("syncCfg.target") ?: "None") }.getOrDefault(SyncTarget.None)
        set(v) { if (v != syncTarget) { settings.put("syncCfg.target", v.name); resetKey() } }

    data class ServerCfg(val url: String, val user: String, val password: String)
    data class FtpCfg(val host: String, val port: String, val user: String, val password: String, val dir: String, val tls: Boolean)

    var server: ServerCfg
        get() = ServerCfg(settings.get("syncCfg.server.url").orEmpty(), settings.get("syncCfg.server.user").orEmpty(), secret("syncCfg.server.password"))
        set(v) {
            settings.put("syncCfg.server.url", v.url.trim()); settings.put("syncCfg.server.user", v.user.trim())
            putSecret("syncCfg.server.password", v.password)
        }

    var ftp: FtpCfg
        get() = FtpCfg(
            settings.get("syncCfg.ftp.host").orEmpty(), settings.get("syncCfg.ftp.port") ?: "21", settings.get("syncCfg.ftp.user").orEmpty(),
            secret("syncCfg.ftp.password"), settings.get("syncCfg.ftp.dir") ?: "/bookmarks-plus", settings.get("syncCfg.ftp.tls") != "false",
        )
        set(v) {
            settings.put("syncCfg.ftp.host", v.host.trim()); settings.put("syncCfg.ftp.port", v.port.trim()); settings.put("syncCfg.ftp.user", v.user.trim())
            putSecret("syncCfg.ftp.password", v.password); settings.put("syncCfg.ftp.dir", v.dir.trim()); settings.put("syncCfg.ftp.tls", v.tls.toString())
        }

    /** Also sync thumbnails (a few 100 MB on the first sync). */
    var syncMedia: Boolean
        get() = settings.get("syncCfg.media") != "false"
        set(v) { settings.put("syncCfg.media", v.toString()) }

    /** Automatic sync in minutes (0 = off, WorkManager allows at least 15). */
    var syncInterval: Int
        get() = settings.get("syncCfg.interval")?.toIntOrNull() ?: 60
        set(v) { settings.put("syncCfg.interval", v.toString()) }

    var syncWifiOnly: Boolean
        get() = settings.get("android.wifiOnly") == "true"
        set(v) { settings.put("android.wifiOnly", v.toString()) }

    val keyReady: Boolean get() = secret("syncCfg.key").isNotEmpty()
    val syncConfigured: Boolean get() = syncTarget != SyncTarget.None && keyReady

    private fun resetKey() = putSecret("syncCfg.key", "")

    // ------------------------------------------------------------------------------------------- Reading

    /** Reloads the snapshot (call in the background). */
    fun reload() {
        _library.value = Library.load(db, contentLang)
        _loaded.value = true
    }

    fun search(lib: Library, filter: Filter): List<Item> {
        val terms = ParsedQuery.parse(filter.text).terms
        val hits = if (terms.isEmpty()) emptySet() else index.search(terms)
        return FilterEngine.apply(lib, filter, System.currentTimeMillis() / 1000, hits)
    }

    fun iconBytes(hash: String): ByteArray? = runCatching { db.bookmarkQueries.iconByHash(hash).executeAsOneOrNull()?.data_ }.getOrNull()

    /** Folder "Inbox" directly under the root (as on the desktop); created on demand and then synchronized as well. */
    fun inboxFolderId(create: Boolean): Long? {
        val root = db.folderQueries.root().executeAsOneOrNull()?.id ?: if (create) repo.rootFolderId() else return null
        db.folderQueries.all().executeAsList().firstOrNull { it.parent_id == root && it.title.equals("Inbox", true) }?.let { return it.id }
        return if (create) repo.createFolder(root, "Inbox") else null
    }

    /** Stores a shared link in the inbox. Existing (normalized) URLs are not created twice. */
    fun addToInbox(url: String, title: String): AddResult {
        val u = url.trim().let { if (it.contains("://")) it else "https://$it" }
        val (id, created) = repo.add(u, title.ifBlank { Urls.domain(u) ?: u }, inboxFolderId(create = true)!!)
        runCatching { index.update(id) }
        return AddResult(id, created)
    }

    // ------------------------------------------------------------------------------------------- Sync

    /** Sync at record level: own server (API v2) or record files via FTP. */
    private fun openStore(): RecordStore = when (syncTarget) {
        SyncTarget.Server -> server.let { ServerRecordStore(it.url, it.user, it.password) }
        SyncTarget.Ftp -> ftp.let { FileRecordStore(FtpStore(it.host, it.port.toIntOrNull() ?: 21, it.user, it.password, it.dir, it.tls)) }
        SyncTarget.None -> throw SyncTargetException(str(R.string.sync_no_target))
    }

    private fun errorText(e: Throwable): String = (e as? SyncTargetException)?.message ?: e.message ?: e::class.simpleName.orEmpty()

    private fun setStatus(text: String) { _syncStatus.value = text; settings.put("syncCfg.status", text) }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        try {
            val n = openStore().use { rs -> if (rs is ServerRecordStore) rs.count() else rs.changes(null).records.count { !it.deleted }.toLong() }
            str(R.string.sync_connection_ok, n)
        } catch (e: Exception) {
            str(R.string.sync_connection_failed, errorText(e))
        }
    }

    /**
     * Enter passphrase: if a `keyinfo.json` (from the desktop) already exists at the target, it must match; otherwise a new
     * key is created. Only the derived key is stored (encrypted in the app folder), not the passphrase.
     */
    suspend fun setPassphrase(pass: String): String = withContext(Dispatchers.IO) {
        try {
            val key = openStore().use { store ->
                val existing = store.blobs.get("keyinfo.json")
                if (existing != null) Crypto.unlock(pass.toCharArray(), Crypto.decodeKeyInfo(existing))
                else {
                    val (info, k) = Crypto.newKeyInfo(pass.toCharArray())
                    store.blobs.put("keyinfo.json", Crypto.encodeKeyInfo(info))
                    k
                }
            }
            putSecret("syncCfg.key", Base64.getEncoder().encodeToString(key))
            str(R.string.sync_key_ready)
        } catch (e: Exception) {
            str(R.string.sync_key_failed, errorText(e))
        }
    }

    suspend fun syncNow(mode: FirstSyncMode = FirstSyncMode.Ask): SyncOutcome = withContext(Dispatchers.IO) {
        val keyText = secret("syncCfg.key")
        if (syncTarget == SyncTarget.None || keyText.isEmpty()) return@withContext SyncOutcome.NotConfigured
        syncMutex.withLock {
            _syncRunning.value = true
            try {
                val key = Base64.getDecoder().decode(keyText)
                var r = openStore().use { SyncEngine(dbFile, mediaDir, it, key, syncMedia).sync(mode) }
                // merge an "Inbox" created here before the first sync with the one from the desktop and pass it on right away
                if (repo.mergeRootFolders("Inbox") > 0) {
                    val again = openStore().use { SyncEngine(dbFile, mediaDir, it, key, syncMedia).sync() }
                    r = r.copy(pulled = r.pulled + again.pulled, pushed = r.pushed + again.pushed)
                }
                if (r.pulled > 0) runCatching { index.rebuild() }
                reload()
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                val text = str(R.string.sync_last_synced, time, r.pulled, r.pushed) +
                    if (r.mediaDown + r.mediaUp > 0) str(R.string.sync_images, r.mediaDown + r.mediaUp) else ""
                setStatus(text)
                SyncOutcome.Done(text, r.pulled)
            } catch (e: FirstSyncDecisionNeeded) {
                setStatus(str(R.string.sync_first_waiting))
                SyncOutcome.NeedsFirstSyncChoice
            } catch (e: Exception) {
                val msg = str(R.string.sync_failed, errorText(e))
                setStatus(msg)
                SyncOutcome.Failed(msg)
            } finally {
                _syncRunning.value = false
            }
        }
    }
}
