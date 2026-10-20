package dev.drivecontacts.phone

import dev.drivecontacts.core.model.Contact

/**
 * One raw contact of the DriveContacts account in the phone store.
 * [contact] has `uid = sourceId ?: ""` and empty `extras` (the phone store cannot keep them).
 */
data class PhoneContact(
    val rawContactId: Long,
    val sourceId: String?,
    val deleted: Boolean,
    val contact: Contact,
    /** `RawContacts.DIRTY`: a caller other than the sync adapter changed the raw contact. */
    val dirty: Boolean = false,
    /** `RawContacts.VERSION`: grows with each change of the raw contact or of its data rows. */
    val version: Long = 0L,
    /** The data rows that [ContactRowMapper] reads into [contact]. The store changes only these rows. */
    val mappedRows: List<DataRow> = emptyList(),
)

/** The raw contacts of the DriveContacts account in `ContactsContract` (docs/spec.md section 6). */
interface PhoneContactsStore {
    /** True when the contacts permissions are granted and the DriveContacts account exists. */
    fun isAvailable(): Boolean

    /**
     * A random token that belongs to this instance of the account. It is new when the account is new.
     * Throws when the account does not exist.
     */
    suspend fun accountToken(): String

    /**
     * A cheap summary of the state of the account: the token and, for each raw contact, the id, version,
     * `DIRTY`, `DELETED` and whether it has a source id. It reads no data rows. Throws when the account does not exist.
     */
    suspend fun fingerprint(): String

    /** All raw contacts of the account, including the rows with `DELETED = 1`. Throws when the account does not exist. */
    suspend fun readAll(): List<PhoneContact>

    /** Sets `SOURCE_ID` on the raw contacts (id to uid) that have none. The caller reads the store again after it. */
    suspend fun assignSourceIds(uids: Map<Long, String>)

    /**
     * Makes the raw contacts of the account equal to [target] (matched by `SOURCE_ID = uid`).
     * [current] is the result of [readAll] in the same mirror pass. A live raw contact without a source id stays unchanged.
     * Purges the rows with `DELETED = 1`. Writes with `CALLER_IS_SYNCADAPTER = true`.
     * Changes only the data rows in [PhoneContact.mappedRows]. Throws when a raw contact in [current] changed
     * in the phone store since [readAll]. Returns true when it changed the phone store.
     */
    suspend fun write(target: List<Contact>, current: List<PhoneContact>): Boolean

    /**
     * Sets `DIRTY = 0` on the given raw contacts, after the mirror has applied their changes.
     * A raw contact that changed since [readAll] stays dirty.
     */
    suspend fun clearDirty(rawContacts: Collection<PhoneContact>)
}
