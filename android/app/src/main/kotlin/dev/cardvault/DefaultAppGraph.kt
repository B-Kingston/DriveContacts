package dev.cardvault

import android.app.Application
import android.content.Context
import dev.cardvault.auth.ProtonAuthService
import dev.cardvault.data.ConflictStore
import dev.cardvault.data.LocalContactsRepository
import dev.cardvault.data.SyncState
import dev.cardvault.data.SyncStateStore
import dev.cardvault.drive.DriveClientProvider
import dev.cardvault.drive.DriveRemoteStore
import dev.cardvault.importing.DeviceImportService
import dev.cardvault.phone.AndroidPhoneContactsStore
import dev.cardvault.phone.ContactsMirror
import dev.cardvault.phone.PhoneAccountManager
import dev.cardvault.sync.SyncEngine
import dev.cardvault.sync.SyncOutcome
import dev.cardvault.sync.WorkSyncController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Creates and connects all app objects. One instance per process, owned by [CardvaultApp]. */
class DefaultAppGraph(private val app: Application) : AppGraph {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: () -> Long = System::currentTimeMillis
    private val dir: File = app.filesDir
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // The sync engine and the wipe must not run at the same time.
    private val syncLock = Mutex()

    val syncStatus = MutableStateFlow(SyncStatus())

    private val authService = ProtonAuthService(app, scope)
    private val driveClients = DriveClientProvider(app, scope, authService)
    private val workSync = WorkSyncController(app, scope, syncStatus.asStateFlow())
    private val local = LocalContactsRepository(dir, clock) { workSync.onLocalEdit() }
    private val conflictStore = ConflictStore(dir, local, clock)
    private val stateStore = SyncStateStore(dir)
    private val phoneAccounts = PhoneAccountManager(app, scope)
    private val engine = SyncEngine(
        remote = DriveRemoteStore(driveClients, scope),
        local = local,
        conflicts = conflictStore,
        state = stateStore,
        mirror = ContactsMirror(AndroidPhoneContactsStore(app), local, dir, clock),
        clock = clock,
    )

    override val auth: AuthService get() = authService
    override val contacts: ContactsRepository get() = local
    override val conflicts: ConflictRepository get() = conflictStore
    override val sync: SyncController get() = gatedSync
    override val phoneSync: PhoneSyncSettings get() = phoneAccounts
    override val importer: ImportService = DeviceImportService(app, local, clock)

    // Sync triggers do nothing while nobody is signed in. The foreground loop starts at sign-in when the UI is visible.
    private val gatedSync = object : SyncController {
        @Volatile private var foreground = false
        override val status get() = workSync.status
        override fun requestSync() { if (signedIn()) workSync.requestSync() }
        override fun onLocalEdit() { if (signedIn()) workSync.onLocalEdit() }
        override fun setForeground(foreground: Boolean) {
            this.foreground = foreground
            workSync.setForeground(foreground && signedIn())
        }
        fun refresh() = workSync.setForeground(foreground && signedIn())
    }

    init {
        stateStore.state.let { syncStatus.value = SyncStatus(lastSyncAt = it.lastSyncAt, lastError = it.lastError) }
        scope.launch {
            authService.state.collect { state ->
                when (state) {
                    is AuthState.SignedIn -> onSignedIn(state.userId)
                    AuthState.SignedOut -> workSync.cancelAll()
                    else -> Unit
                }
                gatedSync.refresh()
            }
        }
    }

    private fun signedIn() = authService.state.value is AuthState.SignedIn

    /** Runs one sync. A sync before the session restore completes waits for it. */
    suspend fun runSync(): SyncOutcome {
        val auth = authService.state.first { it !is AuthState.Loading }
        if (auth !is AuthState.SignedIn) return SyncOutcome.Failure(IllegalStateException("Not signed in"))
        return syncLock.withLock { engine.sync() }
    }

    override suspend fun signOutAndWipe() {
        workSync.cancelAll()
        phoneAccounts.disable()
        authService.signOut()
        driveClients.deleteCache()
        wipeLocalData()
        prefs.edit().remove(KEY_OWNER).apply()
    }

    // Local data belongs to one Proton user. A sign-in as another user starts from empty local data,
    // so that the contacts of one account never go into the Drive of another account.
    private suspend fun onSignedIn(userId: String) {
        val owner = prefs.getString(KEY_OWNER, null)
        if (owner != null && owner != userId) {
            phoneAccounts.disable()
            driveClients.deleteCache()
            wipeLocalData()
        }
        prefs.edit().putString(KEY_OWNER, userId).apply()
        workSync.schedulePeriodic()
        workSync.requestSync()
    }

    private suspend fun wipeLocalData() = syncLock.withLock {
        withContext(Dispatchers.IO) {
            while (true) {
                val snapshot = local.snapshot()
                if (local.compareAndSet(snapshot.version, emptyList())) break
            }
            // With no local contacts, every stored conflict is dropped.
            conflictStore.replaceFrom(emptyList(), emptyList(), emptyList())
            stateStore.update { SyncState() }
            stateStore.writeBase(ByteArray(0))
            syncStatus.value = SyncStatus()
        }
    }

    private companion object {
        const val PREFS = "cardvault_graph"
        const val KEY_OWNER = "owner_user_id"
    }
}
