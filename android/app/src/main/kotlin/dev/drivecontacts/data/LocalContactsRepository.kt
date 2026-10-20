package dev.drivecontacts.data

import dev.drivecontacts.ContactsRepository
import dev.drivecontacts.core.VCard
import dev.drivecontacts.core.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

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
 *
 * A background thread reads the file when the object is made, so the constructor does not block the caller.
 * [contacts] is empty and [loaded] is false until then. [snapshot], [compareAndSet], [upsert], [delete] and
 * [addAll] wait for the load, so they never see a false empty list. They block, so call them off the main thread.
 * When the read fails, [loadError] is set, [loaded] stays false, and these functions throw instead of writing
 * over the file.
 */
class LocalContactsRepository(
    dir: File,
    private val clock: () -> Long,
    private val onLocalEdit: () -> Unit,
) : ContactsRepository {
    private val file = File(dir, "contacts/local.vcf")
    private val lock = Any()
    private var version = 0L
    private val state = MutableStateFlow(emptyList<Contact>())
    private val loadedFlag = MutableStateFlow(false)
    private val loadDone = CountDownLatch(1)

    @Volatile
    private var failure: Throwable? = null
    private val loadErrorFlag = MutableStateFlow<String?>(null)

    override val contacts: StateFlow<List<Contact>> = state.asStateFlow()

    override val loaded: StateFlow<Boolean> = loadedFlag.asStateFlow()

    override val loadError: StateFlow<String?> = loadErrorFlag.asStateFlow()

    init {
        thread(name = "drivecontacts-load-contacts", isDaemon = true) {
            try {
                val list = load()
                synchronized(lock) { state.value = list }
                loadedFlag.value = true
            } catch (e: Throwable) {
                failure = e
                loadErrorFlag.value = e.message ?: e.javaClass.simpleName
            } finally {
                loadDone.countDown()
            }
        }
    }

    private fun awaitLoaded() {
        loadDone.await()
        failure?.let { throw IllegalStateException("The contacts file could not be read", it) }
    }

    fun snapshot(): LocalSnapshot {
        awaitLoaded()
        return synchronized(lock) { LocalSnapshot(state.value, version) }
    }

    /** Replaces all contacts when no change happened since [expectedVersion]. Returns false otherwise. */
    fun compareAndSet(expectedVersion: Long, contacts: List<Contact>): Boolean {
        awaitLoaded()
        return synchronized(lock) {
            if (version != expectedVersion) return false
            commit(contacts)
            true
        }
    }

    override suspend fun upsert(contact: Contact) {
        withContext(Dispatchers.IO) {
            awaitLoaded()
            synchronized(lock) {
                val stamped = contact.copy(rev = clock())
                commit(state.value.filter { it.uid != stamped.uid } + stamped)
            }
        }
        onLocalEdit()
    }

    override suspend fun update(uid: String, transform: (current: Contact?) -> Contact?) {
        val changed = withContext(Dispatchers.IO) {
            awaitLoaded()
            synchronized(lock) {
                val result = transform(state.value.firstOrNull { it.uid == uid }) ?: return@synchronized false
                require(result.uid == uid) { "The transform changed the UID" }
                commit(state.value.filter { it.uid != uid } + result.copy(rev = clock()))
                true
            }
        }
        if (changed) onLocalEdit()
    }

    override suspend fun delete(uid: String) {
        val removed = withContext(Dispatchers.IO) {
            awaitLoaded()
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
            awaitLoaded()
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
