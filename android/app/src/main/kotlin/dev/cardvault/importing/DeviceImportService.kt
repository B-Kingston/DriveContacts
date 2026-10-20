package dev.cardvault.importing

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import dev.cardvault.DeviceAccount
import dev.cardvault.ImportService
import dev.cardvault.ImportSummary
import dev.cardvault.core.Import
import dev.cardvault.core.VCard
import dev.cardvault.core.model.Contact
import dev.cardvault.data.LocalContactsRepository
import dev.cardvault.phone.AndroidPhoneContactsStore
import dev.cardvault.phone.ContactRowMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Imports contacts from other phone accounts and from .vcf files (docs/spec.md section 7). */
class DeviceImportService(
    context: Context,
    private val local: LocalContactsRepository,
    private val clock: () -> Long,
) : ImportService {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun deviceAccounts(): List<DeviceAccount> = withContext(Dispatchers.IO) {
        val counts = LinkedHashMap<Pair<String?, String?>, Int>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.DELETED}=0",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) to c.getString(1)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        counts.filterKeys { it.first != AndroidPhoneContactsStore.ACCOUNT_TYPE }
            .map { (key, count) -> DeviceAccount(key.first, key.second, accountLabel(key.first, key.second), count) }
            .sortedBy { it.label.lowercase() }
    }

    override suspend fun importFromDevice(accounts: List<DeviceAccount>): ImportSummary = withContext(Dispatchers.IO) {
        val now = clock()
        val incoming = ArrayList<Contact>()
        for (account in accounts) {
            if (account.type == AndroidPhoneContactsStore.ACCOUNT_TYPE) continue
            val rawIds = rawContactIds(account)
            val rows = ContactRowMapper.readRows(resolver, ContactsContract.Data.CONTENT_URI, rawIds)
            for (id in rawIds) {
                val contact = ContactRowMapper.toContact(Contact.newUid(), now, rows[id].orEmpty())
                if (contact != Contact(uid = contact.uid, rev = now)) incoming += contact
            }
        }
        add(incoming, now, 0)
    }

    override suspend fun importFromVCard(uri: Uri): ImportSummary = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_FILE_BYTES) throw IOException("The file is larger than 20 MB")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw IOException("Cannot open the file")
        val parsed = VCard.parse(bytes)
        add(parsed.contacts, clock(), parsed.droppedProperties)
    }

    private suspend fun add(incoming: List<Contact>, now: Long, dropped: Int): ImportSummary {
        val result = Import.prepare(local.snapshot().contacts, incoming, now)
        local.addAll(result.imported)
        return ImportSummary(result.imported.size, result.skippedDuplicates, dropped)
    }

    private fun rawContactIds(account: DeviceAccount): List<Long> {
        val args = ArrayList<String>()
        val parts = mutableListOf("${RawContacts.DELETED}=0")
        if (account.type == null) {
            parts += "${RawContacts.ACCOUNT_TYPE} IS NULL"
        } else {
            parts += "${RawContacts.ACCOUNT_TYPE}=?"
            args += account.type
        }
        if (account.name == null) {
            parts += "${RawContacts.ACCOUNT_NAME} IS NULL"
        } else {
            parts += "${RawContacts.ACCOUNT_NAME}=?"
            args += account.name
        }
        val ids = ArrayList<Long>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            parts.joinToString(" AND "),
            args.toTypedArray().takeIf { it.isNotEmpty() },
            "${RawContacts._ID} ASC",
        )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        return ids
    }

    internal companion object {
        const val MAX_FILE_BYTES = 20 * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024

        fun accountLabel(type: String?, name: String?): String = when (type) {
            null -> "Device"
            "com.google" -> "Google · ${name.orEmpty()}"
            else -> "${name.orEmpty()} ($type)"
        }
    }
}
