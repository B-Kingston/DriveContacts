package dev.drivecontacts.sync

import dev.drivecontacts.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Implemented by the Application class. The worker and the sync adapter get the sync from it. */
interface SyncRunnerProvider {
    /** Runs one sync. Returns a failure outcome instead of throwing. */
    suspend fun runSync(): SyncOutcome

    val syncStatus: MutableStateFlow<SyncStatus>

    /** The number of [runTracked] calls in progress. Two runs can overlap, so a flag is not enough. */
    val activeRuns: AtomicInteger
}

/** Runs [SyncRunnerProvider.runSync] and keeps [SyncRunnerProvider.syncStatus] current. */
suspend fun SyncRunnerProvider.runTracked(): SyncOutcome {
    val active = activeRuns
    synchronized(active) {
        active.incrementAndGet()
        syncStatus.update { it.copy(running = true) }
    }
    // The last run that ends clears the flag. Two runs can overlap: one waits for the sync mutex.
    fun finish(change: (SyncStatus) -> SyncStatus) = synchronized(active) {
        val last = active.decrementAndGet() == 0
        syncStatus.update { change(it).copy(running = !last) }
    }
    val outcome = try {
        runSync()
    } catch (e: CancellationException) {
        finish { it }
        throw e
    } catch (e: Exception) {
        SyncOutcome.Failure(e)
    }
    finish {
        when (outcome) {
            is SyncOutcome.Success -> it.copy(lastSyncAt = outcome.finishedAt, lastError = outcome.phoneError)
            is SyncOutcome.Failure -> it.copy(lastError = outcome.error.message ?: outcome.error.javaClass.simpleName)
        }
    }
    return outcome
}

/**
 * True when a retry later can work: an I/O error, an SDK network, transport or I/O error, or an API error with
 * HTTP status 429 or 5xx. The function looks at the whole cause chain.
 */
fun Throwable.isRetryable(): Boolean {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth++ < MAX_CAUSE_DEPTH) {
        if (t is IOException) return true
        if (t is ProtonDriveSdkException && t.error.isRetryable()) return true
        t = t.cause
    }
    return false
}

private fun ProtonSdkError?.isRetryable(): Boolean {
    var e = this
    var depth = 0
    while (e != null && depth++ < MAX_CAUSE_DEPTH) {
        when (e.domain) {
            ProtonSdkError.ErrorDomain.Network,
            ProtonSdkError.ErrorDomain.Transport,
            ProtonSdkError.ErrorDomain.UnknownIO,
            -> return true
            // The secondary code of an API error is the HTTP status.
            ProtonSdkError.ErrorDomain.Api -> {
                val status = e.secondaryCode
                if (status == HTTP_TOO_MANY_REQUESTS || (status != null && status in HTTP_SERVER_ERRORS)) return true
            }
            else -> Unit
        }
        e = e.innerError
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8
private const val HTTP_TOO_MANY_REQUESTS = 429L
private val HTTP_SERVER_ERRORS = 500L..599L
