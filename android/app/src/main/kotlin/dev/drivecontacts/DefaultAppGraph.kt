package dev.drivecontacts

import android.app.Application
import android.content.Context
import android.util.Log
import dev.drivecontacts.auth.ProtonAuthService
import dev.drivecontacts.data.ConflictStore
import dev.drivecontacts.data.LocalContactsRepository
import dev.drivecontacts.data.SyncState
import dev.drivecontacts.data.SyncStateStore
import dev.drivecontacts.drive.DriveClientProvider
import dev.drivecontacts.drive.DriveRemoteStore
import dev.drivecontacts.importing.DeviceImportService
import dev.drivecontacts.phone.AndroidPhoneContactsStore
import dev.drivecontacts.phone.ContactsMirror
import dev.drivecontacts.phone.PhoneAccountManager
import dev.drivecontacts.sync.SyncEngine
import dev.drivecontacts.sync.SyncOutcome
import dev.drivecontacts.sync.WorkSyncController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Creates and connects all app objects. One instance per process, owned by [DriveContactsApp]. */
class DefaultAppGraph(private val app: Application) : AppGraph {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: () -> Long = System::currentTimeMillis
    private val dir: File = app.filesDir
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // The sync engine, the owner check and the wipe must not run at the same time.
    private val syncLock = Mutex()

    // The job of the sync that holds [syncLock]. Sign-out cancels it, so that the wipe does not wait for the sync.
    @Volatile private var runningSync: Job? = null
    private var signInJob: Job? = null

    // True from the start of a sign-out until its wipe ends. A sync that gets [syncLock] in that time does not start.
    @Volatile private var signingOut = false

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
        phoneFailure = ::phoneFailureText,
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
                    // The job waits for [syncLock]. The collector must go on, so that a sign-out is seen at once.
                    is AuthState.SignedIn -> {
                        signInJob?.cancel()
                        signInJob = launch { onSignedIn(state.userId) }
                    }
                    AuthState.SignedOut -> {
                        // Wait for the sign-in job, so that it cannot schedule work after the cancel.
                        signInJob?.cancelAndJoin()
                        runningSync?.cancel()
                        workSync.cancelAll()
                    }
                    else -> Unit
                }
                gatedSync.refresh()
            }
        }
    }

    private fun signedIn() = authService.state.value is AuthState.SignedIn

    /**
     * Runs one sync. A sync before the session restore completes waits for it. Under [syncLock], the sync first
     * checks the owner of the local data against the signed-in user, so a sync never uploads the contacts of
     * one account into the Drive of another account.
     */
    suspend fun runSync(): SyncOutcome {
        val auth = authService.state.first { it !is AuthState.Loading }
        if (auth !is AuthState.SignedIn) return SyncOutcome.Failure(IllegalStateException("Not signed in"))
        // The sync runs in its own job in the app scope. Sign-out cancels that job. The caller then gets a
        // failure outcome instead of a CancellationException, which would end a sync adapter thread.
        val job = scope.async {
            runningSync = currentCoroutineContext()[Job]
            try {
                syncLock.withLock {
                    // The user can sign out while this call waits for the lock.
                    val current = authService.state.value as? AuthState.SignedIn
                    if (current == null || signingOut) return@withLock SyncOutcome.Failure(IllegalStateException("Not signed in"))
                    claimLocalData(current.userId)
                    engine.sync()
                }
            } finally {
                runningSync = null
            }
        }
        return try {
            job.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                job.cancel()
                throw e
            }
            SyncOutcome.Failure(IOException("The sync stopped because of a sign-out", e))
        }
    }

    /** The whole sequence runs in the app scope, so it ends even when the caller is cancelled. */
    override suspend fun signOutAndWipe() {
        scope.async {
            signingOut = true
            try {
                workSync.cancelAll()
                runningSync?.cancel()
                phoneAccounts.disable()
                authService.signOut()
                syncLock.withLock {
                    wipeLocked()
                    prefs.edit().remove(KEY_OWNER).apply()
                }
            } finally {
                signingOut = false
            }
        }.await()
    }

    private suspend fun onSignedIn(userId: String) {
        syncLock.withLock { claimLocalData(userId) }
        // The collector cancels this job and waits for it at sign-out, so this runs before the cancel of the work.
        if (signedIn()) {
            workSync.schedulePeriodic()
            workSync.requestSync()
        }
    }

    // Local data belongs to one Proton user. A sign-in as another user starts from empty local data,
    // so that the contacts of one account never go into the Drive of another account.
    // The caller holds [syncLock].
    private suspend fun claimLocalData(userId: String) {
        val owner = prefs.getString(KEY_OWNER, null)
        if (owner == userId) return
        if (owner != null) {
            phoneAccounts.disable()
            wipeLocked()
        }
        prefs.edit().putString(KEY_OWNER, userId).apply()
    }

    private fun phoneFailureText(error: Throwable): String {
        // The log has the class name only, because a message can hold contact data.
        Log.w(TAG, "The phone mirror failed: ${error.javaClass.simpleName}")
        return app.getString(R.string.sync_phone_error, error.message ?: error.javaClass.simpleName)
    }

    // The caller holds [syncLock].
    private suspend fun wipeLocked() {
        driveClients.close()
        driveClients.deleteCache()
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
        const val TAG = "DriveContactsGraph"
        const val PREFS = "drivecontacts_graph"
        const val KEY_OWNER = "owner_user_id"
    }
}
