package dev.drivecontacts.phone

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import dev.drivecontacts.sync.SyncOutcome
import dev.drivecontacts.sync.SyncRunnerProvider
import dev.drivecontacts.sync.isRetryable
import dev.drivecontacts.sync.runTracked
import kotlinx.coroutines.runBlocking

/** Runs the app sync when the system asks for a sync of the phone contacts (for example after an edit in the Contacts app). */
class ContactsSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {
    override fun onPerformSync(
        account: Account?,
        extras: Bundle?,
        authority: String?,
        provider: ContentProviderClient?,
        syncResult: SyncResult,
    ) {
        val runner = context.applicationContext as SyncRunnerProvider
        when (val outcome = runBlocking { runner.runTracked() }) {
            is SyncOutcome.Success -> Unit
            is SyncOutcome.Failure ->
                if (outcome.error.isRetryable()) syncResult.stats.numIoExceptions++ else syncResult.stats.numParseExceptions++
        }
    }
}

class ContactsSyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = adapter(this).syncAdapterBinder

    private companion object {
        private var instance: ContactsSyncAdapter? = null

        @Synchronized
        fun adapter(context: Context): ContactsSyncAdapter =
            instance ?: ContactsSyncAdapter(context.applicationContext).also { instance = it }
    }
}
