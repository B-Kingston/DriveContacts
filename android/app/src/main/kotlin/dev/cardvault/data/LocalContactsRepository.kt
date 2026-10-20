package dev.cardvault.data

import dev.cardvault.ContactsRepository
import dev.cardvault.core.VCard
import dev.cardvault.core.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** The local contacts at one moment. [version] grows with each change. */
data class LocalSnapshot(val contacts: List<Contact>, val version: Long)

/** True when both lists hold the same UIDs with equal content. The `rev` is ignored. */
fun List<Contact>.sameContentAs(other: List<Contact>): Boolean {
    if (size != other.size) return false
    val byUid = other.associateBy { it.uid }
    if (byUid.size != other.size) return false
    return all { c -> byUid[c.uid]?.sameContentAs(c) == true }
}

/**
 * The local contacts. [dir] is the app files directory. The contacts live in `contacts/local.vcf`.
 * Edits from the user (upsert, delete, addAll) call [onLocalEdit]. Writes from the sync ([compareAndSet]) do not.
 */
class LocalContactsRepository(
    dir: File,
    private val clock: () -> Long,
    private val onLocalEdit: () -> Unit,
) : ContactsRepository {
    private val file = File(dir, "contacts/local.vcf")
    private val lock = Any()
    private var version = 0L
    private val state = MutableStateFlow(load())

    override val contacts: StateFlow<List<Contact>> = state.asStateFlow()

    fun snapshot(): LocalSnapshot = synchronized(lock) { LocalSnapshot(state.value, version) }

    /** Replaces all contacts when no change happened since [expectedVersion]. Returns false otherwise. */
    fun compareAndSet(expectedVersion: Long, contacts: List<Contact>): Boolean = synchronized(lock) {
        if (version != expectedVersion) return false
        commit(contacts)
        true
    }

    override suspend fun upsert(contact: Contact) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val stamped = contact.copy(rev = clock())
                commit(state.value.filter { it.uid != stamped.uid } + stamped)
            }
        }
        onLocalEdit()
    }

    override suspend fun delete(uid: String) {
        val removed = withContext(Dispatchers.IO) {
            synchronized(lock) {
                val rest = state.value.filter { it.uid != uid }
                if (rest.size == state.value.size) {
                    false
                } else {
                    commit(rest)
                    true
                }
            }
        }
        if (removed) onLocalEdit()
    }

    /** Adds imported contacts. The caller has already given them new UIDs and a rev. */
    suspend fun addAll(contacts: List<Contact>) {
        if (contacts.isEmpty()) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val added = contacts.map { it.uid }.toSet()
                commit(state.value.filter { it.uid !in added } + contacts)
            }
        }
        onLocalEdit()
    }

    // The caller holds the lock. The file is written before the state changes.
    private fun commit(list: List<Contact>) {
        val sorted = list.sortedWith(ORDER)
        AtomicFiles.write(file, VCard.serialize(sorted))
        version++
        state.value = sorted
    }

    private fun load(): List<Contact> {
        val bytes = AtomicFiles.read(file) ?: return emptyList()
        return VCard.parse(bytes).contacts.sortedWith(ORDER)
    }

    private companion object {
        val ORDER = compareBy<Contact>({ it.formattedName.lowercase() }, { it.uid })
    }
}
