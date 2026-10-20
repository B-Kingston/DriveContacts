package dev.cardvault.phone

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import dev.cardvault.core.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** The raw contacts of the Cardvault account in `ContactsContract` (docs/spec.md section 6). */
class AndroidPhoneContactsStore(context: Context) : PhoneContactsStore {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override fun isAvailable(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            account() != null

    override suspend fun readAll(): List<PhoneContact> = withContext(Dispatchers.IO) {
        val account = account() ?: return@withContext emptyList()
        class Raw(val id: Long, val sourceId: String?, val deleted: Boolean, val dirty: Boolean)

        val raws = ArrayList<Raw>()
        val projection = arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.DELETED, RawContacts.DIRTY)
        resolver.query(
            syncUri(RawContacts.CONTENT_URI, account),
            projection,
            "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=?",
            arrayOf(account.name, account.type),
            "${RawContacts._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) raws += Raw(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getInt(3) != 0)
        }
        val rows = ContactRowMapper.readRows(
            resolver,
            syncUri(ContactsContract.Data.CONTENT_URI, account),
            raws.filter { !it.deleted }.map { it.id },
        )
        raws.map { raw ->
            PhoneContact(
                rawContactId = raw.id,
                sourceId = raw.sourceId,
                deleted = raw.deleted,
                contact = ContactRowMapper.toContact(raw.sourceId.orEmpty(), 0L, rows[raw.id].orEmpty()),
                dirty = raw.dirty,
            )
        }
    }

    override suspend fun write(target: List<Contact>, current: List<PhoneContact>, assignedUids: Map<Long, String>) {
        withContext(Dispatchers.IO) {
            val account = account() ?: throw IllegalStateException("The Cardvault account does not exist")
            val rawUri = syncUri(RawContacts.CONTENT_URI, account)
            val dataUri = syncUri(ContactsContract.Data.CONTENT_URI, account)
            val targetUids = target.mapTo(HashSet()) { it.uid }

            val matched = HashMap<String, PhoneContact>()
            val batch = Batch()
            for (raw in current) {
                val uid = raw.sourceId?.takeIf { it.isNotEmpty() } ?: assignedUids[raw.rawContactId]
                // A raw contact without a source id that the mirror did not take (it has no content) stays as it is.
                if (uid == null && !raw.deleted) continue
                if (raw.deleted || uid == null || uid !in targetUids || matched.containsKey(uid)) {
                    batch.add(1) { listOf(deleteRaw(rawUri, raw.rawContactId)) }
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
                if (raw.sourceId.isNullOrEmpty()) {
                    batch.add(1) {
                        listOf(
                            ContentProviderOperation.newUpdate(rawUri)
                                .withSelection("${RawContacts._ID}=?", arrayOf(raw.rawContactId.toString()))
                                .withValue(RawContacts.SOURCE_ID, contact.uid)
                                .withYieldAllowed(true)
                                .build(),
                        )
                    }
                }
                val same = ContactRowMapper.normalize(contact).sameContentAs(raw.contact.copy(uid = contact.uid, rev = contact.rev))
                if (!same) {
                    batch.add(1 + rows.size) {
                        buildList {
                            add(
                                ContentProviderOperation.newDelete(dataUri)
                                    .withSelection("${ContactsContract.Data.RAW_CONTACT_ID}=?", arrayOf(raw.rawContactId.toString()))
                                    .withYieldAllowed(true)
                                    .build(),
                            )
                            rows.forEach { add(insertRow(dataUri, it).withValue(ContactsContract.Data.RAW_CONTACT_ID, raw.rawContactId).build()) }
                        }
                    }
                }
            }
            batch.flush()
        }
    }

    override suspend fun clearDirty(rawContactIds: Collection<Long>) {
        if (rawContactIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            val account = account() ?: return@withContext
            val rawUri = syncUri(RawContacts.CONTENT_URI, account)
            val batch = Batch()
            for (id in rawContactIds) {
                batch.add(1) {
                    listOf(
                        ContentProviderOperation.newUpdate(rawUri)
                            .withSelection("${RawContacts._ID}=?", arrayOf(id.toString()))
                            .withValue(RawContacts.DIRTY, 0)
                            .build(),
                    )
                }
            }
            batch.flush()
        }
    }

    /** The Cardvault account, or null. */
    fun account(): Account? = AccountManager.get(appContext).getAccountsByType(ACCOUNT_TYPE).firstOrNull()

    private fun deleteRaw(rawUri: Uri, id: Long): ContentProviderOperation =
        ContentProviderOperation.newDelete(rawUri)
            .withSelection("${RawContacts._ID}=?", arrayOf(id.toString()))
            .withYieldAllowed(true)
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

        suspend fun flush() {
            if (ops.isEmpty()) return
            resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
            ops.clear()
            yield()
        }
    }

    companion object {
        const val ACCOUNT_TYPE = "dev.cardvault.account"
        private const val MAX_OPERATIONS = 400

        /** [base] with `CALLER_IS_SYNCADAPTER` and the account. Only the sync adapter URI can write and purge. */
        fun syncUri(base: Uri, account: Account): Uri = base.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
            .build()
    }
}
