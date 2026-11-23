package de.bookmarksplus.sync

import de.bookmarksplus.i18n.tr
import java.io.File
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Backups of the whole database: daily locally (unencrypted, in the app folder) and, on request, encrypted
 * at the sync target. Restoring places the file as `<db>.restore`; the app swaps it in on the next start.
 */
object Backups {
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Consistent copy of the running database (VACUUM INTO, also works while the app is writing). */
    fun copyDb(dbFile: File, target: File) {
        target.parentFile?.mkdirs()
        target.delete()
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout = 30000")
                st.execute("VACUUM INTO '${target.absolutePath.replace("'", "''")}'")
            }
        }
    }

    /** Daily local backup; keeps the last [keep]. Returns the new file or null if there is already one today. */
    fun localDaily(dbFile: File, dir: File, keep: Int = 7): File? {
        val today = LocalDateTime.now().format(STAMP).substring(0, 8)
        if (dir.listFiles().orEmpty().any { it.name.startsWith("bookmarks-$today") }) return null
        val f = File(dir, "bookmarks-${LocalDateTime.now().format(STAMP)}.db")
        copyDb(dbFile, f)
        listLocal(dir).drop(keep).forEach { it.delete() }
        return f
    }

    /** Local backups, newest first. */
    fun listLocal(dir: File): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("bookmarks-") && it.name.endsWith(".db") }.sortedByDescending { it.name }

    /** Encrypted backup at the target; keeps the last [keep]. */
    fun upload(dbFile: File, store: BlobStore, key: ByteArray, keep: Int = 10): String {
        val tmp = File.createTempFile("bmplus-backup", ".db")
        try {
            copyDb(dbFile, tmp)
            val name = "backups/backup-${LocalDateTime.now().format(STAMP)}.bin"
            store.put(name, Crypto.seal(key, tmp.readBytes(), name))
            listRemote(store).drop(keep).forEach { store.delete(it) }
            return name
        } finally {
            tmp.delete()
        }
    }

    /** Backups at the target, newest first. */
    fun listRemote(store: BlobStore): List<String> = store.list("backups/").sortedDescending()

    fun restoreFile(dbFile: File) = File(dbFile.parentFile, dbFile.name + ".restore")

    /** Fetches a backup from the target and prepares it for restoring on the next start. */
    fun prepareRemoteRestore(store: BlobStore, key: ByteArray, name: String, dbFile: File) {
        val blob = store.get(name) ?: throw SyncTargetException(tr("sync.backup.notFound", name))
        val bytes = Crypto.open(key, blob, name)
        check(String(bytes, 0, 15) == "SQLite format 3") { tr("sync.backup.invalidDb") }
        restoreFile(dbFile).writeBytes(bytes)
    }

    fun prepareLocalRestore(backup: File, dbFile: File) {
        backup.copyTo(restoreFile(dbFile), overwrite = true)
    }

    /**
     * Call at startup before the database is opened: swaps in a prepared backup.
     * The previous database remains as `before-restore-…db` in the backup folder.
     */
    fun applyPendingRestore(dbFile: File, backupDir: File): Boolean {
        val restore = restoreFile(dbFile).takeIf { it.isFile } ?: return false
        backupDir.mkdirs()
        if (dbFile.isFile) dbFile.copyTo(File(backupDir, "before-restore-${LocalDateTime.now().format(STAMP)}.db"), overwrite = true)
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        dbFile.delete()
        if (!restore.renameTo(dbFile)) { restore.copyTo(dbFile, overwrite = true); restore.delete() }
        // sync state belongs to the state before the backup or to another device → decide anew on the next sync
        runCatching {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
                c.createStatement().use { it.execute("DELETE FROM setting WHERE key IN ('sync.deviceId', 'sync.lastSync', 'sync.applied', 'sync.snapshot')") }
            }
        }
        return true
    }
}
