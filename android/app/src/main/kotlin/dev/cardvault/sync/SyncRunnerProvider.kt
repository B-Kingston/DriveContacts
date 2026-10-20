package dev.cardvault.sync

import dev.cardvault.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

/** Implemented by the Application class. The worker and the sync adapter get the sync from it. */
interface SyncRunnerProvider {
    /** Runs one sync. Returns a failure outcome instead of throwing. */
    suspend fun runSync(): SyncOutcome

    val syncStatus: MutableStateFlow<SyncStatus>
}

/** Runs [SyncRunnerProvider.runSync] and keeps [SyncRunnerProvider.syncStatus] current. */
suspend fun SyncRunnerProvider.runTracked(): SyncOutcome {
    syncStatus.update { it.copy(running = true) }
    val outcome = try {
        runSync()
    } catch (e: CancellationException) {
        syncStatus.update { it.copy(running = false) }
        throw e
    } catch (e: Exception) {
        SyncOutcome.Failure(e)
    }
    syncStatus.update {
        when (outcome) {
            is SyncOutcome.Success -> it.copy(running = false, lastSyncAt = outcome.finishedAt, lastError = null)
            is SyncOutcome.Failure -> it.copy(
                running = false,
                lastError = outcome.error.message ?: outcome.error.javaClass.simpleName,
            )
        }
    }
    return outcome
}

/** True when the error or one of its causes is an I/O error. Then a retry later can work. */
fun Throwable.isNetworkError(): Boolean {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth++ < 8) {
        if (t is IOException) return true
        t = t.cause
    }
    return false
}
