package dev.cardvault.phone

import dev.cardvault.core.model.Contact

/**
 * One raw contact of the Cardvault account in the phone store.
 * [contact] has `uid = sourceId ?: ""` and empty `extras` (the phone store cannot keep them).
 */
data class PhoneContact(
    val rawContactId: Long,
    val sourceId: String?,
    val deleted: Boolean,
    val contact: Contact,
    /** `RawContacts.DIRTY`: a caller other than the sync adapter changed the raw contact. */
    val dirty: Boolean = false,
)

/** The raw contacts of the Cardvault account in `ContactsContract` (docs/spec.md section 6). */
interface PhoneContactsStore {
    /** True when the contacts permissions are granted and the Cardvault account exists. */
    fun isAvailable(): Boolean

    /** All raw contacts of the account, including the rows with `DELETED = 1`. */
    suspend fun readAll(): List<PhoneContact>

    /**
     * Makes the raw contacts of the account equal to [target] (matched by `SOURCE_ID = uid`).
     * [current] is the result of [readAll] in the same mirror pass. Raw contacts in [current] without a source id
     * are matched by [PhoneContact.rawContactId] through [assignedUids] and get their new `SOURCE_ID`.
     * A live raw contact without a source id and without an assigned UID stays unchanged.
     * Purges the rows with `DELETED = 1`. Writes with `CALLER_IS_SYNCADAPTER = true`.
     */
    suspend fun write(target: List<Contact>, current: List<PhoneContact>, assignedUids: Map<Long, String>)

    /** Sets `DIRTY = 0` on the given raw contacts, after the mirror has applied their changes. */
    suspend fun clearDirty(rawContactIds: Collection<Long>)
}
