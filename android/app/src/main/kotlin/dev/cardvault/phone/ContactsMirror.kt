package dev.cardvault.phone

import dev.cardvault.core.Merge
import dev.cardvault.core.VCard
import dev.cardvault.core.model.Contact
import dev.cardvault.data.AtomicFiles
import dev.cardvault.data.LocalContactsRepository
import dev.cardvault.data.sameContentAs
import java.io.File

/**
 * The two-way mirror between the local contacts and the phone store (docs/spec.md section 6).
 * [dir] is the app files directory. The mirror base is `contacts/mirror-base.vcf`.
 */
class ContactsMirror(
    private val phone: PhoneContactsStore,
    private val local: LocalContactsRepository,
    dir: File,
    private val clock: () -> Long,
) {
    private val baseFile = File(dir, "contacts/mirror-base.vcf")

    /** Runs one mirror pass. Returns true when the pass changed the local contacts. */
    suspend fun mirror(): Boolean {
        if (!phone.isAvailable()) return false
        val current = phone.readAll()
        val base = readBase()
        val baseByUid = base.associateBy { it.uid }

        val assigned = LinkedHashMap<Long, String>()
        val seen = HashSet<String>()
        val phoneContacts = ArrayList<Contact>()
        for (raw in current) {
            if (raw.deleted) continue
            val uid = raw.sourceId?.takeIf { it.isNotEmpty() }
                ?: Contact.newUid().also { assigned[raw.rawContactId] = it }
            if (!seen.add(uid)) continue
            val known = baseByUid[uid]
            val content = raw.contact.copy(uid = uid, extras = known?.extras ?: emptyList())
            val rev = if (known != null && known.sameContentAs(content)) known.rev else clock()
            phoneContacts += content.copy(rev = rev)
        }
        val liveRows = current.count { !it.deleted }

        var attempts = 0
        while (true) {
            val snapshot = local.snapshot()
            // A field conflict takes the phone value (the provisional value). The user does not see it.
            val result = Merge.merge3(base, snapshot.contacts, phoneContacts).contacts
            val changed = !result.sameContentAs(snapshot.contacts)
            if (changed && !local.compareAndSet(snapshot.version, result)) {
                check(++attempts < MAX_ATTEMPTS) { "The local contacts keep changing" }
                continue
            }
            val phoneDiffers = assigned.isNotEmpty() ||
                current.any { it.deleted } ||
                liveRows != phoneContacts.size ||
                !result.sameContentAs(phoneContacts)
            if (phoneDiffers) phone.write(result, current, assigned)
            AtomicFiles.write(baseFile, VCard.serialize(result))
            return changed
        }
    }

    private fun readBase(): List<Contact> =
        AtomicFiles.read(baseFile)?.let { VCard.parse(it).contacts } ?: emptyList()

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
