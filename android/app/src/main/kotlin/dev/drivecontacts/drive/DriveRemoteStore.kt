package dev.drivecontacts.drive

import dev.drivecontacts.sync.RemoteContactsStore
import dev.drivecontacts.sync.RemoteEvent
import dev.drivecontacts.sync.RemoteFile
import dev.drivecontacts.sync.RemoteFolder
import dev.drivecontacts.sync.RemoteNameConflictException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.Channels
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import me.proton.drive.sdk.ProtonDriveClient
import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError
import me.proton.drive.sdk.entity.DriveEvent
import me.proton.drive.sdk.entity.DriveEventId
import me.proton.drive.sdk.entity.FileDownloaderRequest
import me.proton.drive.sdk.entity.FileNode
import me.proton.drive.sdk.entity.FileRevisionUploaderRequest
import me.proton.drive.sdk.entity.FileUploaderRequest
import me.proton.drive.sdk.entity.FolderNode
import me.proton.drive.sdk.entity.Node
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.RevisionUid
import me.proton.drive.sdk.entity.ScopeId
import me.proton.drive.sdk.entity.UploadResult

/** [RemoteContactsStore] over the Proton Drive SDK client. [scope] runs the stream transfers of the SDK. */
class DriveRemoteStore(
    private val provider: DriveClientProvider,
    private val scope: CoroutineScope,
) : RemoteContactsStore {

    override suspend fun ensureFolder(): RemoteFolder {
        val client = provider.client()
        val root = client.getMyFilesFolder()
        findFolder(client, root)?.let { return it.toRemote() }
        val created = try {
            client.createFolder(root.uid, FOLDER_NAME)
        } catch (e: ProtonDriveSdkException) {
            if (nameConflictOf(e) == null) throw e
            return findFolder(client, root)?.toRemote() ?: throw e
        }
        return created.toRemote()
    }

    override suspend fun findFile(folderUid: String): RemoteFile? {
        val client = provider.client()
        val node = findChild(client, NodeUid(folderUid)) { it is FileNode && it.name.getOrNull() == FILE_NAME }
        return (node as? FileNode)?.toRemote()
    }

    // A name conflict with an unfinished upload (a draft) of another client is not a real file. The retry
    // overrides that draft. A conflict with a real node gives the existing node to the caller.
    override suspend fun createFile(folderUid: String, content: ByteArray): RemoteFile {
        val client = provider.client()
        val result = try {
            createUpload(client, folderUid, content, overrideDraft = false)
        } catch (e: Exception) {
            if (nameConflictData(e)?.conflictingNodeIsFileDraft != true) throw nameConflictOf(e) ?: e
            try {
                createUpload(client, folderUid, content, overrideDraft = true)
            } catch (retry: Exception) {
                throw nameConflictOf(retry) ?: retry
            }
        }
        return RemoteFile(result.nodeUid.value, result.revisionUid.value)
    }

    private suspend fun createUpload(
        client: ProtonDriveClient,
        folderUid: String,
        content: ByteArray,
        overrideDraft: Boolean,
    ): UploadResult {
        val request = FileUploaderRequest(
            parentFolderUid = NodeUid(folderUid),
            name = FILE_NAME,
            mediaType = MEDIA_TYPE,
            fileSize = content.size.toLong(),
            lastModificationTime = Instant.now(),
            overrideExistingDraftByOtherClient = overrideDraft,
        )
        return upload(client.uploader(request), content)
    }

    override suspend fun getFile(fileUid: String): RemoteFile? {
        val node = provider.client().getNode(NodeUid(fileUid))
        if (node !is FileNode || node.trashTime != null) return null
        return node.toRemote()
    }

    override suspend fun download(revisionUid: String): ByteArray {
        val client = provider.client()
        val output = ByteArrayOutputStream()
        client.downloader(FileDownloaderRequest(RevisionUid(revisionUid))).use { downloader ->
            downloader.downloadToStream(scope, Channels.newChannel(output)).use { controller ->
                controller.awaitCompletion()
                if (controller.isDownloadCompleteWithVerificationIssue()) {
                    throw IOException("The download has a verification issue")
                }
            }
        }
        return output.toByteArray()
    }

    override suspend fun uploadRevision(fileUid: String, currentActiveRevisionUid: String, content: ByteArray): RemoteFile {
        val client = provider.client()
        val request = FileRevisionUploaderRequest(
            currentActiveRevisionUid = RevisionUid(currentActiveRevisionUid),
            lastModificationTime = Instant.now(),
            size = content.size.toLong(),
        )
        val result = upload(client.uploader(request), content)
        return RemoteFile(result.nodeUid.value, result.revisionUid.value)
    }

    override suspend fun latestCursor(scopeId: String): String {
        val events = provider.client().enumerateEvents(ScopeId(scopeId), null).toList()
        events.firstOrNull { it is DriveEvent.ScopeAccessLost }?.let { throw IOException("The event scope is not accessible") }
        return events.firstNotNullOfOrNull { it as? DriveEvent.CursorAdvanced }?.id?.value
            ?: throw IOException("The server gave no event cursor")
    }

    override suspend fun eventsSince(scopeId: String, cursor: String): List<RemoteEvent> =
        provider.client().enumerateEvents(ScopeId(scopeId), DriveEventId(cursor)).toList().map { event ->
            when (event) {
                is DriveEvent.NodeUpdated -> RemoteEvent.NodeChanged(event.id.value, event.nodeUid.value, event.parentNodeUid?.value)
                is DriveEvent.NodeDeleted -> RemoteEvent.NodeChanged(event.id.value, event.nodeUid.value, event.parentNodeUid?.value)
                is DriveEvent.SharedWithMeUpdated -> RemoteEvent.CursorAdvanced(event.id.value)
                is DriveEvent.CursorAdvanced -> RemoteEvent.CursorAdvanced(event.id.value)
                is DriveEvent.ContinuityLost -> RemoteEvent.ContinuityLost(event.id.value)
                is DriveEvent.ScopeAccessLost -> throw IOException("The event scope is not accessible")
            }
        }

    private suspend fun upload(uploader: me.proton.drive.sdk.Uploader, content: ByteArray): UploadResult =
        uploader.use { up ->
            up.uploadFromStream(scope, Channels.newChannel(ByteArrayInputStream(content))).use { it.awaitCompletion() }
        }

    private suspend fun findFolder(client: ProtonDriveClient, parent: FolderNode): FolderNode? =
        findChild(client, parent.uid) { it is FolderNode && it.name.getOrNull() == FOLDER_NAME } as? FolderNode

    /** The first child of [parentUid] that is not trashed and matches [matches]. The node flow stops at the match. */
    private suspend fun findChild(client: ProtonDriveClient, parentUid: NodeUid, matches: (Node) -> Boolean): Node? {
        val uids = client.enumerateFolderChildrenNodeUids(parentUid).toList()
        if (uids.isEmpty()) return null
        return client.enumerateNodes(uids).firstOrNull { it.trashTime == null && matches(it) }
    }

    private fun FolderNode.toRemote() = RemoteFolder(uid.value, treeEventScopeId.id)

    private fun FileNode.toRemote() = RemoteFile(uid.value, activeRevision.uid.value)

    /** Returns the name-conflict exception when [error] or one of its causes is an SDK name conflict. */
    private fun nameConflictOf(error: Throwable): RemoteNameConflictException? =
        nameConflictData(error)?.let { RemoteNameConflictException(it.conflictingNodeUid?.value, error) }

    private fun nameConflictData(error: Throwable): ProtonSdkError.Data.NodeNameConflict? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is ProtonDriveSdkException) {
                var sdkError: ProtonSdkError? = cause.error
                while (sdkError != null) {
                    val data = sdkError.additionalData
                    if (data is ProtonSdkError.Data.NodeNameConflict) return data
                    sdkError = sdkError.innerError
                }
            }
            cause = cause.cause
        }
        return null
    }

    private companion object {
        const val FOLDER_NAME = "DriveContacts"
        const val FILE_NAME = "contacts.vcf"
        const val MEDIA_TYPE = "text/vcard"
    }
}
