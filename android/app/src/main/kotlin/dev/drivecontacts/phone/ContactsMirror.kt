package dev.drivecontacts.phone

import dev.drivecontacts.core.Merge
import dev.drivecontacts.core.VCard
import dev.drivecontacts.core.model.Contact
import dev.drivecontacts.data.AtomicFiles
import dev.drivecontacts.data.LocalContactsRepository
import dev.drivecontacts.data.sameContentAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The two-way mirror between the local contacts and the phone store (docs/spec.md section 6).
 * [dir] is the app files directory. The mirror base is `contacts/mirror-base.vcf`.
 * The file `contacts/mirror-base.account` holds the token of the phone account that the base belongs to.
 * A pass throws when it cannot finish. The next pass starts again from the stored state.
 */
class ContactsMirror(
    private val phone: PhoneContactsStore,
    private val local: LocalContactsRepository,
    dir: File,
    private val clock: () -> Long,
) {
    private val baseFile = File(dir, "contacts/mirror-base.vcf")
    private val tokenFile = File(dir, "contacts/mirror-base.account")
    private val mutex = Mutex()

    // The state at the end of the last pass that changed nothing. A pass with the same state has nothing to do.
    private var lastFingerprint: String? = null
    private var lastLocalVersion = -1L

    /** Runs one mirror pass. Returns true when the pass changed the local contacts. */
    suspend fun mirror(): Boolean = mutex.withLock { pass() }

    private suspend fun pass(): Boolean {
        if (!phone.isAvailable()) return false
        // The fingerprint comes before the read. An edit after it makes the next fingerprint differ.
        val fingerprint = phone.fingerprint()
        if (fingerprint == lastFingerprint && local.snapshot().version == lastLocalVersion) return false
        lastFingerprint = null

        var current = phone.readAll()
        // A new raw contact gets its UID in the phone store first. A pass that fails later finds the same UID again.
        val fresh = current.filter { !it.deleted && it.sourceId.isNullOrEmpty() && !isEmpty(it) }
        var wrote = false
        if (fresh.isNotEmpty()) {
            phone.assignSourceIds(fresh.associate { it.rawContactId to Contact.newUid() })
            current = phone.readAll()
            wrote = true
        }

        val token = phone.accountToken()
        // A base of another account instance is not valid. A base from an older app version has no token file:
        // it belongs to the current account. A base without any of its contacts in the phone store cannot be right
        // either: the phone store lost its data. The mirror then starts without a base and puts all local contacts back.
        // A deleted raw contact keeps its source id, so a real delete of all contacts does not count as lost data.
        var base = if (storedToken(token) == token) readBase() else emptyList()
        val storedUids = base.mapTo(HashSet()) { it.uid }
        if (base.isNotEmpty() && current.none { it.sourceId.orEmpty() in storedUids }) base = emptyList()
        val baseByUid = base.associateBy { it.uid }

        val seen = HashSet<String>()
        val phoneContacts = ArrayList<Contact>()
        for (raw in current) {
            if (raw.deleted) continue
            // A raw contact without a source id has no content. The mirror leaves it alone.
            val uid = raw.sourceId?.takeIf { it.isNotEmpty() } ?: continue
            if (!seen.add(uid)) continue
            val known = baseByUid[uid]
            val content = VCard.canonical(raw.contact.copy(uid = uid)).copy(extras = known?.extras ?: emptyList())
            val rev = if (known != null && known.sameContentAs(content)) known.rev else clock()
            phoneContacts += content.copy(rev = rev)
        }

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
            wrote = phone.write(result, current) || wrote
            val dirty = current.filter { it.dirty && !it.deleted }
            phone.clearDirty(dirty)
            writeBase(result, token)
            // After a write the fingerprint is stale. The next pass reads again and then keeps the new fingerprint.
            if (!wrote && dirty.isEmpty()) remember(fingerprint, result)
            return changed
        }
    }

    /** Keeps the state of a pass that changed nothing in the phone store, when the local contacts equal [result]. */
    private fun remember(fingerprint: String, result: List<Contact>) {
        val after = local.snapshot()
        if (!after.contacts.sameContentAs(result)) return
        lastFingerprint = fingerprint
        lastLocalVersion = after.version
    }

    private fun isEmpty(raw: PhoneContact): Boolean =
        VCard.canonical(raw.contact.copy(uid = EMPTY_UID)).sameContentAs(Contact(uid = EMPTY_UID))

    private fun readBase(): List<Contact> =
        AtomicFiles.read(baseFile)?.let { VCard.parse(it).contacts } ?: emptyList()

    private fun readToken(): String? = AtomicFiles.read(tokenFile)?.toString(Charsets.UTF_8)

    /**
     * The token that the mirror base belongs to. A base without a token file comes from an older app version.
     * It belongs to the account that exists now, so this adopts [current] as its token.
     */
    private fun storedToken(current: String): String? {
        val stored = readToken()
        if (stored != null || !baseFile.isFile) return stored
        AtomicFiles.write(tokenFile, current.toByteArray(Charsets.UTF_8))
        return current
    }

    /** Writes the files only when their content changes. The base comes first: a base without its token is not used. */
    private fun writeBase(result: List<Contact>, token: String) {
        val bytes = VCard.serialize(result)
        if (!bytes.contentEquals(AtomicFiles.read(baseFile))) AtomicFiles.write(baseFile, bytes)
        if (readToken() != token) AtomicFiles.write(tokenFile, token.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val EMPTY_UID = "urn:uuid:empty"
    }
}
