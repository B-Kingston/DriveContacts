package dev.cardvault

import android.app.Application
import dev.cardvault.sync.SyncOutcome
import dev.cardvault.sync.SyncRunnerProvider
import kotlinx.coroutines.flow.MutableStateFlow

class CardvaultApp : Application(), AppGraphProvider, SyncRunnerProvider {
    override val graph: DefaultAppGraph by lazy { DefaultAppGraph(this) }

    override val syncStatus: MutableStateFlow<SyncStatus> get() = graph.syncStatus

    override suspend fun runSync(): SyncOutcome = graph.runSync()

    override fun onCreate() {
        super.onCreate()
        // The graph restores the session and schedules the periodic sync.
        graph
    }
}
