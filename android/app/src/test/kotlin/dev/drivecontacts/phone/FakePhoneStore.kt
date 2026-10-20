package dev.drivecontacts.phone

import dev.drivecontacts.core.model.Contact

/** An in-memory phone store with the semantics of the store contract. */
class FakePhoneStore : PhoneContactsStore {
    var available = true
    var token = "token-1"
    val raws = ArrayList<PhoneContact>()
    private var nextId = 1L

    /** The number of calls of [readAll]. */
    var reads = 0

    /** When true, [write] throws before it changes anything. */
    var failWrites = false

    /** When true, [readAll] throws. */
    var failReads = false

    override fun isAvailable() = available

    override suspend fun readAll(): List<PhoneContact> {
        check(!failReads) { "The read failed" }
        reads++
        return raws.toList()
    }

    override suspend fun accountToken(): String = token

    override suspend fun fingerprint(): String =
        token + raws.joinToString(";") { "${it.rawContactId}:${it.version}:${it.dirty}:${it.deleted}:${it.sourceId.isNullOrEmpty()}" }

    override suspend fun assignSourceIds(uids: Map<Long, String>) {
        for (i in raws.indices) {
            val uid = uids[raws[i].rawContactId] ?: continue
            if (raws[i].sourceId.isNullOrEmpty()) raws[i] = raws[i].copy(sourceId = uid, version = raws[i].version + 1)
        }
    }

    override suspend fun write(target: List<Contact>, current: List<PhoneContact>): Boolean {
        check(!failWrites) { "The write failed" }
        // Like the real store, check the version only of the raw contacts that the write updates or deletes.
        fun assertUnchanged(raw: PhoneContact) {
            val now = raws.firstOrNull { it.rawContactId == raw.rawContactId }
            check(now == null || now.version == raw.version) { "A contact changed during the pass" }
        }
        val targetUids = target.mapTo(HashSet()) { it.uid }
        val keep = HashMap<String, PhoneContact>()
        for (raw in current) {
            val uid = raw.sourceId
            if (raw.deleted || uid.isNullOrEmpty()) continue
            if (uid in targetUids && uid !in keep) keep[uid] = raw else assertUnchanged(raw)
        }
        val untouched = current.filter { !it.deleted && it.sourceId.isNullOrEmpty() }
        val out = target.map { c ->
            val kept = keep[c.uid]
            val content = c.copy(extras = emptyList(), rev = 0L)
            when {
                kept == null -> PhoneContact(nextId++, c.uid, false, content)
                kept.contact.copy(uid = c.uid) == content -> kept
                else -> {
                    assertUnchanged(kept)
                    kept.copy(contact = content, dirty = false, version = kept.version + 1)
                }
            }
        } + untouched
        val changed = out.toSet() != raws.toSet()
        raws.clear()
        raws += out
        return changed
    }

    val cleared = ArrayList<Long>()

    override suspend fun clearDirty(rawContacts: Collection<PhoneContact>) {
        for (raw in rawContacts) {
            val i = raws.indexOfFirst { it.rawContactId == raw.rawContactId && it.version == raw.version }
            if (i < 0) continue
            cleared += raw.rawContactId
            raws[i] = raws[i].copy(dirty = false, version = raws[i].version + 1)
        }
    }

    /** A raw contact that was created in the phone: no source id. */
    fun addNew(contact: Contact): Long {
        val id = nextId++
        raws += PhoneContact(id, null, false, contact.copy(uid = ""), dirty = true)
        return id
    }

    fun edit(uid: String, change: (Contact) -> Contact) {
        val i = raws.indexOfFirst { it.sourceId == uid }
        raws[i] = raws[i].copy(contact = change(raws[i].contact), dirty = true, version = raws[i].version + 1)
    }

    fun markDeleted(uid: String) {
        val i = raws.indexOfFirst { it.sourceId == uid }
        raws[i] = raws[i].copy(deleted = true, version = raws[i].version + 1)
    }
}
