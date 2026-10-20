package dev.cardvault.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/** One sync run. The Application (a [SyncRunnerProvider]) does the work. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val provider = applicationContext as SyncRunnerProvider
        return when (val outcome = provider.runTracked()) {
            is SyncOutcome.Success -> Result.success()
            is SyncOutcome.Failure -> if (outcome.error.isNetworkError()) Result.retry() else Result.failure()
        }
    }

    // Expedited work needs this on Android 11 and older.
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = android.app.Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Cardvault")
            .setContentText("Syncing contacts")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private companion object {
        const val CHANNEL_ID = "cardvault-sync"
        const val NOTIFICATION_ID = 1
    }
}
