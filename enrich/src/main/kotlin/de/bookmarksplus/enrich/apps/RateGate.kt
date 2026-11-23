package de.bookmarksplus.enrich.apps

import de.bookmarksplus.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Polite throttle for a website: at most one request at a time, minimum interval plus random share,
 * daily limit and pause after blocks (exponential, 1 min … [maxPauseMs]). `Retry-After` is respected.
 */
class RateGate(
    val name: String,
    @Volatile var minIntervalMs: Long,
    /** Random surcharge in percent of the minimum interval (looks less mechanical, spreads the load). */
    private val jitterPercent: Int = 0,
    @Volatile var dailyLimit: Int = Int.MAX_VALUE,
    private val maxPauseMs: Long = 60 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    @Volatile private var last = 0L
    @Volatile private var strikes = 0
    @Volatile var pausedUntil: Long = 0
        private set
    @Volatile private var day = 0L
    @Volatile var usedToday: Int = 0
        private set

    val paused: Boolean get() = clock() < pausedUntil
    val exhausted: Boolean get() = currentDay() == day && usedToday >= dailyLimit

    private fun currentDay() = clock() / 86_400_000L

    /** Runs [block] as soon as the throttle allows it. Throws [RateLimited] if paused or the daily limit is reached. */
    suspend fun <T> run(block: () -> T): T = mutex.withLock {
        if (paused) throw RateLimited(name, pausedUntil)
        val today = currentDay()
        if (today != day) { day = today; usedToday = 0 }
        if (usedToday >= dailyLimit) throw RateLimited(name, (today + 1) * 86_400_000L)
        val jitter = if (jitterPercent > 0) Random.nextLong(minIntervalMs * jitterPercent / 100 + 1) else 0L
        val wait = last + minIntervalMs + jitter - clock()
        if (wait > 0) delay(wait)
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            last = clock()
            usedToday++
        }
    }

    /** Successful response: reset block counter. */
    fun success() { strikes = 0 }

    /** Block (403/429/bot check): extend pause. [retryAfterSec] from the header takes precedence. */
    fun penalize(retryAfterSec: Long? = null): Long {
        strikes++
        val ms = retryAfterSec?.times(1000)?.coerceAtMost(maxPauseMs)
            ?: (60_000L shl (strikes - 1).coerceAtMost(10)).coerceAtMost(maxPauseMs)
        pausedUntil = clock() + ms
        return pausedUntil
    }

    fun resetPause() { pausedUntil = 0; strikes = 0 }
}

class RateLimited(val site: String, val until: Long) : Exception(tr("enrich.rateGate.paused", site, java.time.Instant.ofEpochMilli(until)))
