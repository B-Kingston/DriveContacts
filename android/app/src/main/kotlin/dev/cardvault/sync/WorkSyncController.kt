package dev.cardvault.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.cardvault.SyncController
import dev.cardvault.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Runs syncs through WorkManager (docs/spec.md section 5, "Triggers"). */
class WorkSyncController(
    context: Context,
    private val scope: CoroutineScope,
    override val status: StateFlow<SyncStatus>,
) : SyncController {
    private val work = WorkManager.getInstance(context.applicationContext)
    private val lock = Any()
    private var debounceJob: Job? = null
    private var foregroundJob: Job? = null

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    init {
        schedulePeriodic()
    }

    /** Starts the periodic job. Call again after a sign-in that followed [cancelAll]. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * A run that is already in progress stays (KEEP): a second run would only wait for the sync mutex.
     * Work that waits in a backoff delay is replaced, so that "Sync now" runs at once.
     */
    override fun requestSync() {
        scope.launch(Dispatchers.IO) {
            val running = work.getWorkInfosForUniqueWork(NOW).get().any { it.state == WorkInfo.State.RUNNING }
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            work.enqueueUniqueWork(
                NOW,
                if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    override fun onLocalEdit() {
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(DEBOUNCE_MS)
                requestSync()
            }
        }
    }

    override fun setForeground(foreground: Boolean) {
        synchronized(lock) {
            if (foreground) {
                if (foregroundJob?.isActive == true) return
                foregroundJob = scope.launch {
                    while (isActive) {
                        requestSync()
                        delay(FOREGROUND_INTERVAL_MS)
                    }
                }
            } else {
                foregroundJob?.cancel()
                foregroundJob = null
            }
        }
    }

    /** Stops all timers and cancels all work. Call it at sign-out. */
    fun cancelAll() {
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = null
            foregroundJob?.cancel()
            foregroundJob = null
        }
        work.cancelUniqueWork(NOW)
        work.cancelUniqueWork(PERIODIC)
    }

    private companion object {
        const val PERIODIC = "cardvault-periodic"
        const val NOW = "cardvault-now"
        const val PERIOD_MINUTES = 15L
        const val BACKOFF_SECONDS = 30L
        const val DEBOUNCE_MS = 3_000L
        const val FOREGROUND_INTERVAL_MS = 30_000L
    }
}
