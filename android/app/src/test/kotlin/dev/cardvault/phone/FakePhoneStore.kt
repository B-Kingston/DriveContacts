package dev.cardvault.phone

import dev.cardvault.core.model.Contact

/** An in-memory phone store with the semantics of the write contract. */
class FakePhoneStore : PhoneContactsStore {
    var available = true
    val raws = ArrayList<PhoneContact>()
    private var nextId = 1L

    override fun isAvailable() = available

    override suspend fun readAll(): List<PhoneContact> = raws.toList()

    override suspend fun write(target: List<Contact>, current: List<PhoneContact>, assignedUids: Map<Long, String>) {
        val targetUids = target.mapTo(HashSet()) { it.uid }
        val keep = HashMap<String, PhoneContact>()
        for (raw in current) {
            val uid = raw.sourceId ?: assignedUids[raw.rawContactId]
            if (!raw.deleted && uid == null) continue
            if (!raw.deleted && uid != null && uid in targetUids && uid !in keep) keep[uid] = raw
        }
        val untouched = current.filter { !it.deleted && (it.sourceId ?: assignedUids[it.rawContactId]) == null }
        val out = target.map { c ->
            PhoneContact(keep[c.uid]?.rawContactId ?: nextId++, c.uid, false, c.copy(extras = emptyList(), rev = 0L))
        }
        raws.clear()
        raws += out + untouched
    }

    val cleared = ArrayList<Long>()

    override suspend fun clearDirty(rawContactIds: Collection<Long>) {
        cleared += rawContactIds
        for (i in raws.indices) if (raws[i].rawContactId in rawContactIds) raws[i] = raws[i].copy(dirty = false)
    }

    /** A raw contact that was created in the phone: no source id. */
    fun addNew(contact: Contact): Long {
        val id = nextId++
        raws += PhoneContact(id, null, false, contact.copy(uid = ""), dirty = true)
        return id
    }

    fun edit(uid: String, change: (Contact) -> Contact) {
        val i = raws.indexOfFirst { it.sourceId == uid }
        raws[i] = raws[i].copy(contact = change(raws[i].contact), dirty = true)
    }

    fun markDeleted(uid: String) {
        val i = raws.indexOfFirst { it.sourceId == uid }
        raws[i] = raws[i].copy(deleted = true)
    }
}
