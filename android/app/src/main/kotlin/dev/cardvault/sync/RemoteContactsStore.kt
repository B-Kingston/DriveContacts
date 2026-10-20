package dev.cardvault.sync

/** The Drive folder `My files/Cardvault`. [scopeId] is the event scope (volume) of the folder. */
data class RemoteFolder(val folderUid: String, val scopeId: String)

/** The Drive file `contacts.vcf` and its active revision. */
data class RemoteFile(val fileUid: String, val activeRevisionUid: String)

sealed interface RemoteEvent {
    val eventId: String

    /** A node changed or was deleted. */
    data class NodeChanged(override val eventId: String, val nodeUid: String, val parentUid: String?) : RemoteEvent

    /** No node changes; only the cursor moved. */
    data class CursorAdvanced(override val eventId: String) : RemoteEvent

    /** The server cannot give the events since the cursor. The client must read the remote again. */
    data class ContinuityLost(override val eventId: String) : RemoteEvent
}

/** A node with the same name exists. [existingNodeUid] is the existing node, when the server told it. */
class RemoteNameConflictException(val existingNodeUid: String?, cause: Throwable? = null) :
    Exception("A node with the same name exists", cause)

/**
 * Access to the contacts file in Proton Drive (docs/spec.md section 5).
 * The implementation over the Proton Drive SDK is `dev.cardvault.drive.DriveRemoteStore`.
 * All functions throw on network or API failure.
 */
interface RemoteContactsStore {
    /** Finds the folder `Cardvault` (not trashed) in My files, or creates it. */
    suspend fun ensureFolder(): RemoteFolder

    /** Finds `contacts.vcf` (not trashed) in the folder, or returns null. */
    suspend fun findFile(folderUid: String): RemoteFile?

    /** Creates `contacts.vcf` with [content]. Throws [RemoteNameConflictException] when the name is taken. */
    suspend fun createFile(folderUid: String, content: ByteArray): RemoteFile

    /** Returns the file, or null when the node is absent or trashed. */
    suspend fun getFile(fileUid: String): RemoteFile?

    /** Downloads the content of one revision. */
    suspend fun download(revisionUid: String): ByteArray

    /**
     * Uploads [content] as a new revision. The server rejects it when [currentActiveRevisionUid] is no longer the
     * active revision; this function then throws.
     */
    suspend fun uploadRevision(fileUid: String, currentActiveRevisionUid: String, content: ByteArray): RemoteFile

    /** The latest event id of the scope. */
    suspend fun latestCursor(scopeId: String): String

    /** All events after [cursor], in order. */
    suspend fun eventsSince(scopeId: String, cursor: String): List<RemoteEvent>
}
