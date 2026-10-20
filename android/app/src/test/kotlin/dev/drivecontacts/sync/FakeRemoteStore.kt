package dev.drivecontacts.sync

import dev.drivecontacts.core.VCard
import dev.drivecontacts.core.model.Contact
import java.io.IOException

/** An in-memory Drive. It rejects stale revisions like the real server and records its calls. */
class FakeRemoteStore : RemoteContactsStore {
    val revisions = LinkedHashMap<String, ByteArray>()
    var activeRev: String? = null
    var fileUid: String? = null
    var trashed = false

    var ensureFolderCalls = 0
    var getFileCalls = 0
    var downloadCalls = 0
    var uploadCalls = 0

    var beforeDownload: suspend () -> Unit = {}
    var beforeUpload: suspend () -> Unit = {}

    private var eventSeq = 0
    private var revSeq = 0
    private var fileSeq = 0
    private val events = ArrayList<Pair<Int, (String) -> RemoteEvent>>()

    private fun live(): RemoteFile? =
        if (fileUid != null && !trashed) RemoteFile(fileUid!!, activeRev!!) else null

    private fun emit(make: (String) -> RemoteEvent) {
        eventSeq++
        events += eventSeq to make
    }

    private fun addRevision(bytes: ByteArray): String {
        val id = "rev-${++revSeq}"
        revisions[id] = bytes
        activeRev = id
        return id
    }

    /** Another device uploads [contacts] as a new revision. */
    fun externalUpload(contacts: List<Contact>, withEvent: Boolean = true) {
        addRevision(VCard.serialize(contacts))
        if (withEvent) emit { RemoteEvent.NodeChanged(it, fileUid!!, "folder-1") }
    }

    fun trash() {
        trashed = true
        emit { RemoteEvent.NodeChanged(it, fileUid!!, "folder-1") }
    }

    fun emitContinuityLost() = emit { RemoteEvent.ContinuityLost(it) }

    fun contacts(): List<Contact> = VCard.parse(revisions[activeRev]!!).contacts

    override suspend fun ensureFolder(): RemoteFolder {
        ensureFolderCalls++
        return RemoteFolder("folder-1", "scope-1")
    }

    override suspend fun findFile(folderUid: String): RemoteFile? = live()

    override suspend fun createFile(folderUid: String, content: ByteArray): RemoteFile {
        live()?.let { throw RemoteNameConflictException(it.fileUid) }
        fileUid = "file-${++fileSeq}"
        trashed = false
        addRevision(content)
        emit { RemoteEvent.NodeChanged(it, fileUid!!, folderUid) }
        return live()!!
    }

    override suspend fun getFile(fileUid: String): RemoteFile? {
        getFileCalls++
        return live()?.takeIf { it.fileUid == fileUid }
    }

    override suspend fun download(revisionUid: String): ByteArray {
        beforeDownload()
        downloadCalls++
        return revisions.getValue(revisionUid)
    }

    override suspend fun uploadRevision(fileUid: String, currentActiveRevisionUid: String, content: ByteArray): RemoteFile {
        beforeUpload()
        uploadCalls++
        if (currentActiveRevisionUid != activeRev) throw IOException("HTTP 409")
        addRevision(content)
        emit { RemoteEvent.NodeChanged(it, fileUid, "folder-1") }
        return live()!!
    }

    override suspend fun latestCursor(scopeId: String): String = "e$eventSeq"

    override suspend fun eventsSince(scopeId: String, cursor: String): List<RemoteEvent> {
        val after = cursor.removePrefix("e").toInt()
        return events.filter { it.first > after }.map { (n, make) -> make("e$n") }
    }
}
