package dev.drivecontacts

import android.net.Uri
import dev.drivecontacts.core.Conflict
import dev.drivecontacts.core.Field
import dev.drivecontacts.core.model.Contact
import kotlinx.coroutines.flow.StateFlow

// Contracts between the UI and the rest of the app. The concrete wiring is DefaultAppGraph.

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState

    /** The UI opens [signInUrl] in a browser tab. The service polls until the user completes the sign-in. */
    data class WaitingForBrowser(val signInUrl: String) : AuthState
    data class SignedIn(val email: String, val userId: String) : AuthState
    data class Failed(val message: String) : AuthState
}

interface AuthService {
    val state: StateFlow<AuthState>

    /** Starts the session-fork sign-in (docs/spec.md section 9). */
    fun startSignIn()
    fun cancelSignIn()

    /** Revokes the session (best effort) and deletes the session secrets. */
    suspend fun signOut()
}

interface ContactsRepository {
    /**
     * Local contacts. The order is not defined; the UI sorts them for display.
     * The list is empty until [loaded] is true. An empty list before that does not mean "no contacts".
     */
    val contacts: StateFlow<List<Contact>>

    /** True after the contacts file has been read. It never goes back to false. */
    val loaded: StateFlow<Boolean>

    /** Not null when the contacts file could not be read. Then [loaded] stays false and all writes fail. */
    val loadError: StateFlow<String?>

    /** Inserts or replaces the contact with the same UID. Sets rev to now. Requests a debounced sync. */
    suspend fun upsert(contact: Contact)

    /**
     * Replaces the contact [uid] with the result of [transform], under the repository lock, so that no other change
     * lands between the read and the write. [transform] gets the current contact or null. A null result changes
     * nothing. Otherwise the result must have the UID [uid]; it gets rev = now and requests a debounced sync.
     */
    suspend fun update(uid: String, transform: (current: Contact?) -> Contact?)

    suspend fun delete(uid: String)
}

interface ConflictRepository {
    val conflicts: StateFlow<List<Conflict>>

    /** The local and the remote contacts of the last merge that gave conflicts, or null. */
    suspend fun lastConflictFiles(): Pair<List<Contact>, List<Contact>>?

    /** FIELDS conflict: use the local value for the fields in [useLocal], the current value for the others. */
    suspend fun resolveFields(uid: String, useLocal: Set<Field>)

    /** Keeps the current local contact and closes the conflict. */
    suspend fun resolveKeep(uid: String)

    /** Deletes the contact locally and closes the conflict. */
    suspend fun resolveDelete(uid: String)
}

data class SyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
)

interface SyncController {
    val status: StateFlow<SyncStatus>

    /** Runs a sync now (expedited one-off work). */
    fun requestSync()

    /** Runs a sync 3 seconds after the last local edit. */
    fun onLocalEdit()

    /** While true, runs a sync every 30 seconds. */
    fun setForeground(foreground: Boolean)
}

interface PhoneSyncSettings {
    val enabled: StateFlow<Boolean>

    /** Creates the DriveContacts account in the phone and starts the mirror. The caller has the contacts permissions. */
    suspend fun enable(email: String)

    /** Removes the DriveContacts account and its raw contacts from the phone. Local and Drive contacts stay. */
    suspend fun disable()
}

/** A source account in the phone contacts (for example a Google account or the device account). */
data class DeviceAccount(
    val type: String?,
    val name: String?,
    val label: String,
    val contactCount: Int,
)

data class ImportSummary(
    val imported: Int,
    val skippedDuplicates: Int,
    val droppedProperties: Int,
)

interface ImportService {
    /** Accounts with contacts in the phone, except the DriveContacts account. Needs READ_CONTACTS. */
    suspend fun deviceAccounts(): List<DeviceAccount>
    suspend fun importFromDevice(accounts: List<DeviceAccount>): ImportSummary
    suspend fun importFromVCard(uri: Uri): ImportSummary
}

interface AppGraph {
    val auth: AuthService
    val contacts: ContactsRepository
    val conflicts: ConflictRepository
    val sync: SyncController
    val phoneSync: PhoneSyncSettings
    val importer: ImportService

    /** Stops the sync, signs out, removes the phone account and deletes all local app data. Drive data stays. */
    suspend fun signOutAndWipe()
}

/** Implemented by the Application class. UI code gets the graph with `(context.applicationContext as AppGraphProvider).graph`. */
interface AppGraphProvider {
    val graph: AppGraph
}
