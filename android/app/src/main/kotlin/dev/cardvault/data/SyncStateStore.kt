package dev.cardvault.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The Drive sync state (docs/spec.md section 5). Empty strings mean "unknown". */
@Serializable
data class SyncState(
    val folderUid: String = "",
    val fileUid: String = "",
    val scopeId: String = "",
    val eventCursor: String = "",
    val baseRevisionUid: String = "",
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
)

/** Stores `sync-state.json` and the base bytes `contacts/base.vcf` in [dir] (the app files directory). */
class SyncStateStore(dir: File) {
    private val stateFile = File(dir, "sync-state.json")
    private val baseFile = File(dir, "contacts/base.vcf")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    var state: SyncState = load()
        private set

    /** Applies [change] and saves the result. */
    @Synchronized
    fun update(change: (SyncState) -> SyncState): SyncState {
        val next = change(state)
        AtomicFiles.write(stateFile, json.encodeToString(SyncState.serializer(), next).toByteArray())
        state = next
        return next
    }

    /** The exact remote bytes of the base revision. Empty when there is no base. */
    fun readBase(): ByteArray = AtomicFiles.read(baseFile) ?: ByteArray(0)

    fun writeBase(bytes: ByteArray) = AtomicFiles.write(baseFile, bytes)

    private fun load(): SyncState {
        val bytes = AtomicFiles.read(stateFile) ?: return SyncState()
        return runCatching { json.decodeFromString(SyncState.serializer(), bytes.decodeToString()) }
            .getOrDefault(SyncState())
    }
}
