package dev.drivecontacts.phone

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.Context
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.RawContacts
import dev.drivecontacts.core.VCard
import dev.drivecontacts.core.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.UUID

/** The raw contacts of the DriveContacts account in `ContactsContract` (docs/spec.md section 6). */
class AndroidPhoneContactsStore(context: Context) : PhoneContactsStore {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val accounts = AccountManager.get(appContext)
    private val tokenLock = Any()

    override fun isAvailable(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            account() != null

    override suspend fun accountToken(): String = withContext(Dispatchers.IO) { tokenOf(requireAccount()) }

    override suspend fun fingerprint(): String = withContext(Dispatchers.IO) {
        val account = requireAccount()
        val summary = StringBuilder(tokenOf(account))
        for (raw in readRaws(account)) {
            summary.append(';').append(raw.id).append(':').append(raw.version)
                .append(':').append(if (raw.dirty) 1 else 0)
                .append(if (raw.deleted) 1 else 0)
                .append(if (raw.sourceId.isNullOrEmpty()) 0 else 1)
        }
        summary.toString()
    }

    override suspend fun readAll(): List<PhoneContact> = withContext(Dispatchers.IO) {
        val account = requireAccount()
        val raws = readRaws(account)
        val rows = ContactRowMapper.readRows(
            resolver,
            syncUri(ContactsContract.Data.CONTENT_URI, account),
            raws.filter { !it.deleted }.map { it.id },
        )
        // The account or the store can vanish between the two reads. Rows that are gone must not read as empty contacts.
        val again = readRaws(requireAccount())
        check(again.size == raws.size && again.indices.all { again[it].id == raws[it].id && again[it].version == raws[it].version }) {
            "The phone contacts changed during the read"
        }
        raws.map { raw ->
            val rawRows = rows[raw.id].orEmpty()
            PhoneContact(
                rawContactId = raw.id,
                sourceId = raw.sourceId,
                deleted = raw.deleted,
                contact = ContactRowMapper.toContact(raw.sourceId.orEmpty(), 0L, rawRows),
                dirty = raw.dirty,
                version = raw.version,
                mappedRows = ContactRowMapper.mappedRows(rawRows),
            )
        }
    }

    override suspend fun assignSourceIds(uids: Map<Long, String>) {
        if (uids.isEmpty()) return
        withContext(Dispatchers.IO) {
            val rawUri = syncUri(RawContacts.CONTENT_URI, requireAccount())
            val batch = Batch()
            for ((id, uid) in uids) {
                batch.add(1) {
                    listOf(
                        ContentProviderOperation.newUpdate(rawUri)
                            .withSelection(
                                "${RawContacts._ID}=? AND (${RawContacts.SOURCE_ID} IS NULL OR ${RawContacts.SOURCE_ID}='')",
                                arrayOf(id.toString()),
                            )
                            .withValue(RawContacts.SOURCE_ID, uid)
                            .build(),
                    )
                }
            }
            batch.flush()
        }
    }

    override suspend fun write(target: List<Contact>, current: List<PhoneContact>): Boolean = withContext(Dispatchers.IO) {
        val account = requireAccount()
        val rawUri = syncUri(RawContacts.CONTENT_URI, account)
        val dataUri = syncUri(ContactsContract.Data.CONTENT_URI, account)
        val targetUids = target.mapTo(HashSet()) { it.uid }

        val matched = HashMap<String, PhoneContact>()
        val batch = Batch()
        for (raw in current) {
            if (raw.deleted) {
                batch.add(1) { listOf(purgeRaw(rawUri, raw.rawContactId)) }
                continue
            }
            // A live raw contact without a source id has no content. It stays as it is.
            val uid = raw.sourceId?.takeIf { it.isNotEmpty() } ?: continue
            if (uid !in targetUids || matched.containsKey(uid)) {
                batch.add(2) { listOf(assertUnchanged(rawUri, raw), deleteRaw(rawUri, raw.rawContactId)) }
            } else {
                matched[uid] = raw
            }
        }
        for (contact in target) {
            val raw = matched[contact.uid]
            val rows = ContactRowMapper.toRows(contact)
            if (raw == null) {
                batch.add(1 + rows.size) { at ->
                    buildList {
                        add(
                            ContentProviderOperation.newInsert(rawUri)
                                .withValue(RawContacts.ACCOUNT_NAME, account.name)
                                .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                                .withValue(RawContacts.SOURCE_ID, contact.uid)
                                .withYieldAllowed(true)
                                .build(),
                        )
                        rows.forEach { add(insertRow(dataUri, it).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, at).build()) }
                    }
                }
                continue
            }
            val stored = VCard.canonical(raw.contact.copy(uid = contact.uid, rev = contact.rev))
            if (VCard.canonical(ContactRowMapper.normalize(contact)).sameContentAs(stored)) continue
            val ops = buildList {
                add(assertUnchanged(rawUri, raw))
                addAll(rowOperations(dataUri, raw, rows))
                // The mirror has applied the phone edit with this write.
                if (raw.dirty) add(rawUpdate(rawUri, raw.rawContactId).withValue(RawContacts.DIRTY, 0).build())
            }
            batch.add(ops.size) { ops }
        }
        batch.flush()
    }

    override suspend fun clearDirty(rawContacts: Collection<PhoneContact>) {
        if (rawContacts.isEmpty()) return
        withContext(Dispatchers.IO) {
            val rawUri = syncUri(RawContacts.CONTENT_URI, requireAccount())
            val batch = Batch()
            for (raw in rawContacts) {
                batch.add(1) {
                    listOf(
                        ContentProviderOperation.newUpdate(rawUri)
                            .withSelection(
                                "${RawContacts._ID}=? AND ${RawContacts.VERSION}=?",
                                arrayOf(raw.rawContactId.toString(), raw.version.toString()),
                            )
                            .withValue(RawContacts.DIRTY, 0)
                            .build(),
                    )
                }
            }
            batch.flush()
        }
    }

    /** The DriveContacts account, or null. */
    fun account(): Account? = accounts.getAccountsByType(ACCOUNT_TYPE).firstOrNull()

    private fun requireAccount(): Account = account() ?: throw IllegalStateException("The DriveContacts account does not exist")

    /** The token of [account]. An account without a token (made by an older app version) gets one. */
    private fun tokenOf(account: Account): String = synchronized(tokenLock) {
        accounts.getUserData(account, TOKEN_KEY) ?: newToken().also { accounts.setUserData(account, TOKEN_KEY, it) }
    }

    private class Raw(val id: Long, val sourceId: String?, val deleted: Boolean, val dirty: Boolean, val version: Long)

    private fun readRaws(account: Account): List<Raw> {
        val raws = ArrayList<Raw>()
        val projection = arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.DELETED, RawContacts.DIRTY, RawContacts.VERSION)
        val cursor = resolver.query(
            syncUri(RawContacts.CONTENT_URI, account),
            projection,
            "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=?",
            arrayOf(account.name, account.type),
            "${RawContacts._ID} ASC",
        ) ?: throw IllegalStateException("The contacts provider is not available")
        cursor.use { c ->
            while (c.moveToNext()) raws += Raw(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getInt(3) != 0, c.getLong(4))
        }
        return raws
    }

    /**
     * The operations that turn the mapped data rows of [raw] into [rows]. A single kind (name, nickname,
     * organization, birthday, note) updates its row in place. Other rows of the same kind stay while the field has a value.
     * When the field is empty, all rows of the kind go. A list kind replaces all its rows.
     * `IS_PRIMARY` and `IS_SUPER_PRIMARY` stay on a phone or email row with the same value.
     * Rows of other kinds stay as they are.
     */
    private fun rowOperations(dataUri: Uri, raw: PhoneContact, rows: List<DataRow>): List<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()
        for (mime in ContactRowMapper.SINGLE_MIME_TYPES) {
            val old = raw.mappedRows.firstOrNull { it.mimeType == mime }
            val new = rows.firstOrNull { it.mimeType == mime }
            when {
                old != null && new != null -> {
                    val update = ContentProviderOperation.newUpdate(dataUri)
                        .withSelection("${ContactsContract.Data._ID}=?", arrayOf(old.id.toString()))
                    new.values.forEach { (column, value) -> update.withValue(column, value?.takeIf { it.isNotEmpty() }) }
                    ops += update.build()
                }
                // A cleared field removes all rows of its kind. A second row would take its place at the next read.
                old != null -> ops += deleteKind(dataUri, raw.rawContactId, mime)
                new != null -> ops += insertRow(dataUri, new).withValue(ContactsContract.Data.RAW_CONTACT_ID, raw.rawContactId).build()
            }
        }
        for (mime in ContactRowMapper.LIST_MIME_TYPES) {
            val old = raw.mappedRows.filter { it.mimeType == mime }
            if (old.isNotEmpty()) ops += deleteRows(dataUri, old)
            val unused = old.toMutableList()
            for (new in rows.filter { it.mimeType == mime }) {
                val builder = insertRow(dataUri, new).withValue(ContactsContract.Data.RAW_CONTACT_ID, raw.rawContactId)
                if (mime == Phone.CONTENT_ITEM_TYPE || mime == Email.CONTENT_ITEM_TYPE) {
                    val same = unused.firstOrNull { it.values[ContactsContract.Data.DATA1] == new.values[ContactsContract.Data.DATA1] }
                    if (same != null) {
                        unused.remove(same)
                        if (same.isPrimary != 0) builder.withValue(ContactsContract.Data.IS_PRIMARY, same.isPrimary)
                        if (same.isSuperPrimary != 0) builder.withValue(ContactsContract.Data.IS_SUPER_PRIMARY, same.isSuperPrimary)
                    }
                }
                ops += builder.build()
            }
        }
        return ops
    }

    private fun deleteRows(dataUri: Uri, rows: List<DataRow>): ContentProviderOperation =
        ContentProviderOperation.newDelete(dataUri)
            .withSelection("${ContactsContract.Data._ID} IN (${rows.joinToString(",") { it.id.toString() }})", null)
            .build()

    /** Deletes all rows of one single kind of a raw contact. A birthday is an event row of the birthday type. */
    private fun deleteKind(dataUri: Uri, rawContactId: Long, mime: String): ContentProviderOperation {
        val birthday = mime == Event.CONTENT_ITEM_TYPE
        val selection = "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?" +
            if (birthday) " AND ${Event.TYPE}=${Event.TYPE_BIRTHDAY}" else ""
        return ContentProviderOperation.newDelete(dataUri).withSelection(selection, arrayOf(rawContactId.toString(), mime)).build()
    }

    private fun rawUpdate(rawUri: Uri, id: Long): ContentProviderOperation.Builder =
        ContentProviderOperation.newUpdate(rawUri).withSelection("${RawContacts._ID}=?", arrayOf(id.toString()))

    /**
     * Makes the batch fail when the raw contact changed since the read. It is the first operation of its unit and the
     * only one that may yield: the provider can let other writers in before it, never between it and the guarded changes.
     */
    private fun assertUnchanged(rawUri: Uri, raw: PhoneContact): ContentProviderOperation =
        ContentProviderOperation.newAssertQuery(rawUri)
            .withSelection(
                "${RawContacts._ID}=? AND ${RawContacts.VERSION}=?",
                arrayOf(raw.rawContactId.toString(), raw.version.toString()),
            )
            .withExpectedCount(1)
            .withYieldAllowed(true)
            .build()

    /** The delete of a raw contact in its own unit (a purge). It may yield: nothing guards it. */
    private fun purgeRaw(rawUri: Uri, id: Long): ContentProviderOperation =
        ContentProviderOperation.newDelete(rawUri)
            .withSelection("${RawContacts._ID}=?", arrayOf(id.toString()))
            .withYieldAllowed(true)
            .build()

    /** The delete of a raw contact after [assertUnchanged]. It must not yield: the assert and the delete are one step. */
    private fun deleteRaw(rawUri: Uri, id: Long): ContentProviderOperation =
        ContentProviderOperation.newDelete(rawUri)
            .withSelection("${RawContacts._ID}=?", arrayOf(id.toString()))
            .build()

    private fun insertRow(dataUri: Uri, row: DataRow): ContentProviderOperation.Builder {
        val builder = ContentProviderOperation.newInsert(dataUri)
            .withValue(ContactsContract.Data.MIMETYPE, row.mimeType)
        row.values.forEach { (column, value) -> if (!value.isNullOrEmpty()) builder.withValue(column, value) }
        return builder
    }

    /** Collects operations and applies them in chunks of at most [MAX_OPERATIONS]. A unit is never split. */
    private inner class Batch {
        private val ops = ArrayList<ContentProviderOperation>()

        /** Adds one unit of [size] operations. [build] gets the batch index of the first operation. */
        suspend fun add(size: Int, build: (firstIndex: Int) -> List<ContentProviderOperation>) {
            if (ops.isNotEmpty() && ops.size + size > MAX_OPERATIONS) flush()
            ops += build(ops.size)
        }

        /** Applies the collected operations. Returns true when there were any. */
        suspend fun flush(): Boolean {
            if (ops.isEmpty()) return applied
            try {
                resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
            } catch (e: OperationApplicationException) {
                throw IllegalStateException("The phone store refused the changes. A contact may have changed during the pass.", e)
            }
            ops.clear()
            applied = true
            yield()
            return true
        }

        private var applied = false
    }

    companion object {
        const val ACCOUNT_TYPE = "dev.drivecontacts.account"

        /** The key of the account token in the user data of the account. */
        const val TOKEN_KEY = "dev.drivecontacts.account-token"

        private const val MAX_OPERATIONS = 400

        /** A new random account token. */
        fun newToken(): String = UUID.randomUUID().toString()

        /** [base] with `CALLER_IS_SYNCADAPTER` and the account. Only the sync adapter URI can write and purge. */
        fun syncUri(base: Uri, account: Account): Uri = base.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
            .build()
    }
}
