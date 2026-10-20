package dev.drivecontacts.sync

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
import dev.drivecontacts.SyncController
import dev.drivecontacts.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Runs syncs through WorkManager (docs/spec.md section 5, "Triggers"). */
class WorkSyncController(
    context: Context,
    private val scope: CoroutineScope,
    override val status: StateFlow<SyncStatus>,
) : SyncController {
    private val work = WorkManager.getInstance(context.applicationContext)
    private val lock = Any()
    private val enqueueMutex = Mutex()
    private var debounceJob: Job? = null
    private var foregroundJob: Job? = null

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Starts the periodic job. Call again after a sign-in that followed [cancelAll]. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * "Sync now" and local edits run as expedited work. Which policy applies depends on the existing work: see
     * [policyFor]. A run in progress is never cancelled.
     */
    override fun requestSync() {
        scope.launch(Dispatchers.IO) {
            enqueueMutex.withLock {
                val infos = work.getWorkInfosForUniqueWork(NOW).get().map { it.state to it.runAttemptCount }
                val policy = policyFor(infos) ?: return@withLock
                work.enqueueUniqueWork(NOW, policy, request(expedited = true))
            }
        }
    }

    /**
     * The foreground timer uses this. It is not expedited, because an expedited run is a foreground service with a
     * notification on Android 11 and older. Existing work stays (KEEP): a tick never replaces or queues behind it.
     */
    private fun requestTick() {
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request(expedited = false))
    }

    private fun request(expedited: Boolean) = OneTimeWorkRequestBuilder<SyncWorker>()
        .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
        .build()

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
                        requestTick()
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

    internal companion object {
        /**
         * The policy for a new request, or null when no new work is needed. [infos] has the state and the run
         * attempt count of each existing work item.
         * - Running: one follow-up waits behind it (APPEND_OR_REPLACE). A follow-up that already waits is enough.
         * - Enqueued and never run: it is about to run, so it stays.
         * - Enqueued after a failed run (backoff delay), or finished, or none: replace, so that the run starts at once.
         */
        fun policyFor(infos: List<Pair<WorkInfo.State, Int>>): ExistingWorkPolicy? = when {
            infos.any { it.first == WorkInfo.State.RUNNING } ->
                if (infos.any { it.first == WorkInfo.State.BLOCKED }) null else ExistingWorkPolicy.APPEND_OR_REPLACE
            infos.any { it.first == WorkInfo.State.ENQUEUED && it.second == 0 } -> null
            else -> ExistingWorkPolicy.REPLACE
        }

        const val PERIODIC = "drivecontacts-periodic"
        const val NOW = "drivecontacts-now"
        const val PERIOD_MINUTES = 15L
        const val BACKOFF_SECONDS = 30L
        const val DEBOUNCE_MS = 3_000L
        const val FOREGROUND_INTERVAL_MS = 30_000L
    }
}
