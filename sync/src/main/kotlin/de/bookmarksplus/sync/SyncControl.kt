package de.bookmarksplus.sync

/** Stage of a sync run. Bookmarks (entries) come first, images last. */
enum class SyncPhase { Prepare, Fetch, Send, ImagesUp, ImagesDown }

/**
 * Progress of a sync run. [total] is the overall number of items known so far: entries to fetch + entries to send
 * (known right after the change lists were exchanged), later extended by the images that have to be transferred.
 */
data class SyncProgress(val phase: SyncPhase, val done: Int, val total: Int)

/** The run was stopped by the user. Everything transferred so far is kept; the next run continues where it left off. */
class SyncCancelledException : RuntimeException("Sync cancelled")

/** Pause/stop switch of a running sync. The engine calls [checkpoint] between items. */
class SyncControl {
    @Volatile var paused = false; private set
    @Volatile var cancelled = false; private set

    fun pause() { paused = true }
    fun resume() { paused = false }
    fun cancel() { cancelled = true; paused = false }

    /** Blocks while paused; throws [SyncCancelledException] once cancelled. */
    fun checkpoint() {
        while (paused && !cancelled) Thread.sleep(150)
        if (cancelled) throw SyncCancelledException()
    }
}
