package dev.cardvault.sync

import dev.cardvault.core.Conflict
import dev.cardvault.core.Merge
import dev.cardvault.core.VCard
import dev.cardvault.core.model.Contact
import dev.cardvault.data.ConflictStore
import dev.cardvault.data.LocalContactsRepository
import dev.cardvault.data.SyncStateStore
import dev.cardvault.phone.ContactsMirror
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncOutcome {
    /**
     * [uploaded] counts the revisions or files that this sync uploaded. [downloaded] counts the remote revisions
     * that it merged. [conflicts] counts the conflicts that the merges found. [finishedAt] is the clock value at the end.
     */
    data class Success(val uploaded: Int, val downloaded: Int, val conflicts: Int, val finishedAt: Long) : SyncOutcome

    data class Failure(val error: Throwable) : SyncOutcome
}

/** The Drive sync of docs/spec.md section 5. One sync runs at a time. */
class SyncEngine(
    private val remote: RemoteContactsStore,
    private val local: LocalContactsRepository,
    private val conflicts: ConflictStore,
    private val state: SyncStateStore,
    private val mirror: ContactsMirror?,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()

    private class Counters {
        var uploaded = 0
        var downloaded = 0
        var conflicts = 0
    }

    suspend fun sync(): SyncOutcome = mutex.withLock {
        val counters = Counters()
        try {
            run(counters)
            val now = clock()
            state.update { it.copy(lastSyncAt = now, lastError = null) }
            SyncOutcome.Success(counters.uploaded, counters.downloaded, counters.conflicts, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { state.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) } }
            SyncOutcome.Failure(e)
        }
    }

    private suspend fun run(counters: Counters) {
        runMirror()
        var restarts = 0
        var retries = 0
        var forceRead = false
        while (true) {
            ensureFile(counters)
            if (!readRemote(counters, forceRead)) {
                check(++restarts <= MAX_RESTARTS) { "The remote file keeps disappearing" }
                continue
            }
            forceRead = false

            val bytes = VCard.serialize(local.snapshot().contacts)
            if (bytes.contentEquals(state.readBase())) return
            val current = state.state
            try {
                val uploaded = remote.uploadRevision(current.fileUid, current.baseRevisionUid, bytes)
                state.writeBase(bytes)
                state.update { it.copy(baseRevisionUid = uploaded.activeRevisionUid) }
                counters.uploaded++
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val active = try {
                    remote.getFile(current.fileUid)
                } catch (c: CancellationException) {
                    throw c
                } catch (probe: Exception) {
                    e.addSuppressed(probe)
                    throw e
                }
                if (active != null && active.activeRevisionUid == current.baseRevisionUid) throw e
                if (retries >= MAX_UPLOAD_RETRIES) throw e
                retries++
                forceRead = true
            }
        }
    }

    // Step 1.
    private suspend fun ensureFile(counters: Counters) {
        if (state.state.fileUid.isNotEmpty()) return
        val folder = try {
            remote.ensureFolder()
        } catch (e: RemoteNameConflictException) {
            remote.ensureFolder()
        }
        state.update { it.copy(folderUid = folder.folderUid, scopeId = folder.scopeId) }
        val found = remote.findFile(folder.folderUid)
        if (found != null) {
            adopt(found.fileUid)
            return
        }
        val bytes = VCard.serialize(local.snapshot().contacts)
        try {
            val created = remote.createFile(folder.folderUid, bytes)
            state.writeBase(bytes)
            state.update { it.copy(fileUid = created.fileUid, baseRevisionUid = created.activeRevisionUid) }
            counters.uploaded++
        } catch (e: RemoteNameConflictException) {
            val existing = e.existingNodeUid ?: remote.findFile(folder.folderUid)?.fileUid ?: throw e
            adopt(existing)
        }
    }

    // The base stays empty, so step 3 downloads the file and the first merge is a two-way merge.
    private fun adopt(fileUid: String) {
        state.writeBase(ByteArray(0))
        state.update { it.copy(fileUid = fileUid, baseRevisionUid = "") }
    }

    // Steps 2 and 3. Returns false when the remote file is gone. The caller then starts again at step 1.
    private suspend fun readRemote(counters: Counters, force: Boolean): Boolean {
        val s = state.state
        var maybeChanged = force || s.baseRevisionUid.isEmpty()
        var cursor = s.eventCursor
        if (cursor.isEmpty()) {
            cursor = remote.latestCursor(s.scopeId)
        } else {
            val events = remote.eventsSince(s.scopeId, cursor)
            for (event in events) {
                when (event) {
                    is RemoteEvent.NodeChanged ->
                        if (event.nodeUid == s.fileUid || event.nodeUid == s.folderUid) maybeChanged = true
                    is RemoteEvent.ContinuityLost -> maybeChanged = true
                    is RemoteEvent.CursorAdvanced -> Unit
                }
            }
            cursor = events.lastOrNull()?.eventId ?: cursor
        }

        if (maybeChanged) {
            val file = remote.getFile(s.fileUid)
            if (file == null) {
                state.writeBase(ByteArray(0))
                state.update { it.copy(fileUid = "", baseRevisionUid = "") }
                return false
            }
            if (file.activeRevisionUid != s.baseRevisionUid) merge(file, counters)
        }
        state.update { it.copy(eventCursor = cursor) }
        return true
    }

    private suspend fun merge(file: RemoteFile, counters: Counters) {
        val baseContacts = VCard.parse(state.readBase()).contacts
        val snapshot = local.snapshot()
        val remoteBytes = remote.download(file.activeRevisionUid)
        val remoteContacts = VCard.parse(remoteBytes).contacts

        val first = Merge.merge3(baseContacts, snapshot.contacts, remoteContacts)
        val found = LinkedHashMap<String, Conflict>()
        first.conflicts.forEach { found[it.uid] = it }

        var merged = first.contacts
        var expected = snapshot
        var localChanged = false
        if (!sameBytes(merged, snapshot.contacts)) {
            localChanged = true
            var attempts = 0
            while (!local.compareAndSet(expected.version, merged)) {
                check(++attempts <= MAX_CAS_RETRIES) { "The local contacts keep changing" }
                // The user edited while the download ran. Merge again with the snapshot as base.
                val now = local.snapshot()
                val again = Merge.merge3(expected.contacts, now.contacts, merged)
                again.conflicts.forEach { found[it.uid] = it }
                merged = again.contacts
                expected = now
            }
        }
        conflicts.replaceFrom(found.values.toList(), snapshot.contacts, remoteContacts)
        counters.conflicts += found.size
        counters.downloaded++
        state.writeBase(remoteBytes)
        state.update { it.copy(baseRevisionUid = file.activeRevisionUid) }
        if (localChanged) runMirror()
    }

    private fun sameBytes(a: List<Contact>, b: List<Contact>): Boolean =
        VCard.serialize(a).contentEquals(VCard.serialize(b))

    // A phone problem (for example a revoked permission) must not stop the Drive sync.
    private suspend fun runMirror() {
        try {
            mirror?.mirror()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val MAX_UPLOAD_RETRIES = 3
        const val MAX_RESTARTS = 3
        const val MAX_CAS_RETRIES = 5
    }
}
