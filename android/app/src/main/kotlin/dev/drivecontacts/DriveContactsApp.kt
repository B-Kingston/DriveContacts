package dev.drivecontacts

import android.app.Application
import dev.drivecontacts.sync.SyncOutcome
import dev.drivecontacts.sync.SyncRunnerProvider
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

class DriveContactsApp : Application(), AppGraphProvider, SyncRunnerProvider {
    override val graph: DefaultAppGraph by lazy { DefaultAppGraph(this) }

    override val syncStatus: MutableStateFlow<SyncStatus> get() = graph.syncStatus

    override val activeRuns = AtomicInteger()

    override suspend fun runSync(): SyncOutcome = graph.runSync()

    override fun onCreate() {
        super.onCreate()
        // The graph restores the session and schedules the periodic sync.
        graph
    }
}
