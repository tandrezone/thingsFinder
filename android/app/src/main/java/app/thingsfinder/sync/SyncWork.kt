package app.thingsfinder.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.thingsfinder.ThingsFinderApp
import java.util.concurrent.TimeUnit

/** Runs one sync in the background; WorkManager retries it with backoff when the network is the problem. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = (applicationContext as ThingsFinderApp).container.syncEngine
        return when (val outcome = engine.syncNow()) {
            is SyncOutcome.Failed -> if (outcome.retryable && runAttemptCount < 5) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}

/**
 * When to sync: every hour in the background, shortly after the app starts,
 * and ~15 s after any local change (repeated edits push the timer back, so a
 * burst of edits becomes one sync). Nothing runs without a network; the
 * worker itself is a no-op while sync is off or nobody is signed in.
 */
class SyncScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager by lazy { WorkManager.getInstance(appContext) }
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun requestSoon(delaySeconds: Long = 15) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val PERIODIC = "cloud-sync-periodic"
        const val SOON = "cloud-sync-soon"
    }
}
