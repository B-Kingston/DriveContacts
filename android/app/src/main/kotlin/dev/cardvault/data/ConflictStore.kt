package dev.cardvault.data

import dev.cardvault.ConflictRepository
import dev.cardvault.core.Conflict
import dev.cardvault.core.Field
import dev.cardvault.core.Merge
import dev.cardvault.core.VCard
import dev.cardvault.core.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Open conflicts (docs/spec.md 4.3). [dir] is the app files directory.
 * Files: `contacts/conflicts.json`, `contacts/conflict-local.vcf`, `contacts/conflict-remote.vcf`.
 */
class ConflictStore(
    dir: File,
    private val local: LocalContactsRepository,
    private val clock: () -> Long,
) : ConflictRepository {
    private val listFile = File(dir, "contacts/conflicts.json")
    private val localFile = File(dir, "contacts/conflict-local.vcf")
    private val remoteFile = File(dir, "contacts/conflict-remote.vcf")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Conflict.serializer())
    private val lock = Any()
    private val state = MutableStateFlow(load())

    override val conflicts: StateFlow<List<Conflict>> = state.asStateFlow()

    /**
     * Stores the conflicts of one merge. A new conflict replaces the old conflict of the same UID.
     * A conflict whose contact no longer exists locally is removed.
     * [localContacts] and [remoteContacts] are the two inputs of the merge. They are kept only when [newConflicts] is not empty.
     */
    suspend fun replaceFrom(newConflicts: List<Conflict>, localContacts: List<Contact>, remoteContacts: List<Contact>) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val existing = local.snapshot().contacts.mapTo(HashSet()) { it.uid }
                val merged = LinkedHashMap<String, Conflict>()
                state.value.filter { it.uid in existing }.forEach { merged[it.uid] = it }
                newConflicts.forEach { merged[it.uid] = it }
                if (newConflicts.isNotEmpty()) {
                    AtomicFiles.write(localFile, VCard.serialize(localContacts))
                    AtomicFiles.write(remoteFile, VCard.serialize(remoteContacts))
                }
                save(merged.values.toList())
            }
        }
    }

    override suspend fun lastConflictFiles(): Pair<List<Contact>, List<Contact>>? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (state.value.isEmpty()) return@synchronized null
            val l = AtomicFiles.read(localFile) ?: return@synchronized null
            val r = AtomicFiles.read(remoteFile) ?: return@synchronized null
            VCard.parse(l).contacts to VCard.parse(r).contacts
        }
    }

    override suspend fun resolveFields(uid: String, useLocal: Set<Field>) {
        val conflict = state.value.firstOrNull { it.uid == uid } ?: return
        val current = local.snapshot().contacts.firstOrNull { it.uid == uid }
        if (current != null) {
            local.upsert(Merge.resolveFields(conflict, current, useLocal, clock()))
        }
        remove(uid)
    }

    override suspend fun resolveKeep(uid: String) = remove(uid)

    override suspend fun resolveDelete(uid: String) {
        local.delete(uid)
        remove(uid)
    }

    private suspend fun remove(uid: String) {
        withContext(Dispatchers.IO) {
            synchronized(lock) { save(state.value.filter { it.uid != uid }) }
        }
    }

    // The caller holds the lock. The conflict files go away with the last conflict.
    private fun save(list: List<Conflict>) {
        AtomicFiles.write(listFile, json.encodeToString(serializer, list).toByteArray())
        if (list.isEmpty()) {
            AtomicFiles.delete(localFile)
            AtomicFiles.delete(remoteFile)
        }
        state.value = list
    }

    private fun load(): List<Conflict> {
        val bytes = AtomicFiles.read(listFile) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, bytes.decodeToString()) }.getOrDefault(emptyList())
    }
}
