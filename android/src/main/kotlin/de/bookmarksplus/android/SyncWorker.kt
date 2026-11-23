package de.bookmarksplus.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Sync in the background – periodic and right after sharing a link (waits for network if necessary). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (applicationContext.app.store.syncNow()) {
            is SyncOutcome.Failed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            else -> Result.success()
        }

    companion object {
        private const val PERIODIC = "sync-periodic"
        private const val ONCE = "sync-once"

        private fun constraints(store: BookmarkStore) = Constraints.Builder()
            .setRequiredNetworkType(if (store.syncWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        /** Schedule (anew) or cancel the periodic sync according to the settings. */
        fun schedule(context: Context, store: BookmarkStore) {
            val wm = WorkManager.getInstance(context)
            val minutes = store.syncInterval
            if (!store.syncConfigured || minutes <= 0) { wm.cancelUniqueWork(PERIODIC); return }
            val req = PeriodicWorkRequestBuilder<SyncWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints(store))
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** One-off sync as soon as network is available (e.g. after sharing). */
        fun once(context: Context, store: BookmarkStore) {
            if (!store.syncConfigured) return
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
