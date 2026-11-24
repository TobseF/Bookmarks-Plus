package de.bookmarksplus.desktop

/** Background service that runs a batch (determines whose progress is shown). */
enum class BatchKind { Enrich, Links, Ai, Apps, Sync }

enum class BatchPhase { Running, Done, Cancelled }

/**
 * The one running or most recently finished batch for the status display at the bottom of the sidebar.
 * At most one batch runs at a time; a finished one stays visible until it is closed or a new one is started.
 */
data class BatchStatus(
    val kind: BatchKind,
    val label: String,
    val done: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    val errors: Int = 0,
    /** Section for multi-stage runs (app data: names, Flathub, AlternativeTo). */
    val step: String? = null,
    val paused: Boolean = false,
    val phase: BatchPhase = BatchPhase.Running,
    /** Result after the end, e.g. "12 dead, 3 redirects". */
    val summary: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
) {
    val running: Boolean get() = phase == BatchPhase.Running
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}
