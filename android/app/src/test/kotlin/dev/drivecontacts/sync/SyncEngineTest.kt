package dev.drivecontacts.sync

import dev.drivecontacts.core.ConflictKind
import dev.drivecontacts.core.Field
import dev.drivecontacts.core.VCard
import dev.drivecontacts.core.model.Contact
import dev.drivecontacts.data.ConflictStore
import dev.drivecontacts.data.LocalContactsRepository
import dev.drivecontacts.data.SyncStateStore
import dev.drivecontacts.phone.ContactsMirror
import dev.drivecontacts.phone.PhoneContact
import dev.drivecontacts.phone.PhoneContactsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var time = 1_000_000L
    private val clock: () -> Long = { time += 1000; time }
    private val edits = AtomicInteger()
    private val remote = FakeRemoteStore()
    private lateinit var local: LocalContactsRepository
    private lateinit var conflicts: ConflictStore
    private lateinit var state: SyncStateStore
    private lateinit var engine: SyncEngine

    private fun setUp() {
        val dir = tmp.newFolder()
        local = LocalContactsRepository(dir, clock) { edits.incrementAndGet() }
        conflicts = ConflictStore(dir, local, clock)
        state = SyncStateStore(dir)
        engine = SyncEngine(remote, local, conflicts, state, null, clock)
    }

    private fun contact(name: String, nickname: String = "") =
        Contact(uid = "urn:uuid:" + name.lowercase(), formattedName = name, nickname = nickname)

    private fun names(list: List<Contact>) = list.map { it.formattedName }.sorted()

    private fun sync(): SyncOutcome.Success {
        val outcome = runBlocking { engine.sync() }
        if (outcome is SyncOutcome.Failure) throw AssertionError("sync failed", outcome.error)
        return outcome as SyncOutcome.Success
    }

    @Test
    fun firstSyncCreatesFolderAndFile() {
        setUp()
        runBlocking { local.upsert(contact("Alice")) }
        val outcome = sync()
        assertEquals(1, outcome.uploaded)
        assertEquals(1, remote.ensureFolderCalls)
        assertEquals(listOf("Alice"), names(remote.contacts()))
        assertEquals("file-1", state.state.fileUid)
        assertEquals(remote.activeRev, state.state.baseRevisionUid)
        assertArrayEquals(remote.revisions[remote.activeRev], state.readBase())
        assertNotNull(state.state.lastSyncAt)
    }

    @Test
    fun existingRemoteFileIsAdoptedWithTwoWayMerge() {
        setUp()
        runBlocking {
            local.upsert(contact("Alice"))
            remote.createFile("folder-1", VCard.serialize(listOf(contact("Bob"))))
        }
        val outcome = sync()
        assertEquals(1, outcome.downloaded)
        assertEquals(1, outcome.uploaded)
        assertEquals(listOf("Alice", "Bob"), names(local.snapshot().contacts))
        assertEquals(listOf("Alice", "Bob"), names(remote.contacts()))
    }

    @Test
    fun noEventsMeansNoRemoteReads() {
        setUp()
        runBlocking { local.upsert(contact("Alice")) }
        sync()
        val outcome = sync()
        assertEquals(0, remote.getFileCalls)
        assertEquals(0, remote.downloadCalls)
        assertEquals(0, remote.uploadCalls)
        assertEquals(0, outcome.uploaded)
    }

    @Test
    fun eventForFileGivesDownloadMergeAndUpload() {
        setUp()
        runBlocking { local.upsert(contact("Alice")) }
        sync()
        runBlocking { local.upsert(contact("Carol")) }
        remote.externalUpload(remote.contacts() + contact("Bob"))
        val outcome = sync()
        assertEquals(1, outcome.downloaded)
        assertEquals(1, outcome.uploaded)
        assertEquals(listOf("Alice", "Bob", "Carol"), names(local.snapshot().contacts))
        assertEquals(listOf("Alice", "Bob", "Carol"), names(remote.contacts()))
    }

    @Test
    fun staleRevisionIsRejectedThenMergedAndRetried() {
        setUp()
        runBlocking { local.upsert(contact("Alice")) }
        sync()
        runBlocking { local.upsert(contact("Carol")) }
        remote.externalUpload(remote.contacts() + contact("Bob"), withEvent = false)
        val outcome = sync()
        assertEquals(2, remote.uploadCalls)
        assertEquals(1, outcome.downloaded)
        assertEquals(listOf("Alice", "Bob", "Carol"), names(remote.contacts()))
        assertEquals(listOf("Alice", "Bob", "Carol"), names(local.snapshot().contacts))
    }

    @Test
    fun moreThanThreeRejectionsFailWithConsistentState() {
        setUp()
        runBlocking { local.upsert(contact("Alice")) }
        sync()
        runBlocking { local.upsert(contact("Carol")) }
        var n = 0
        remote.beforeUpload = { remote.externalUpload(remote.contacts() + contact("Other${n++}"), withEvent = false) }
        val outcome = runBlocking { engine.sync() }
        assertTrue(outcome is SyncOutcome.Failure)
        assertEquals(4, remote.uploadCalls)
        assertNotNull(state.state.lastError)
        assertArrayEquals(remote.revisions[state.state.baseRevisionUid], state.readBase())
        assertTrue("Carol" in names(local.snapshot().contacts))

        remote.beforeUpload = {}
        sync()
        assertTrue("Carol" in names(remote.contacts()))
        assertEquals(names(local.snapshot().contacts), names(remote.contacts()))
        assertEquals(null, state.state.lastError)
    }

    @Test
    fun trashedFileIsRecreatedWithLocalContacts() {
        setUp()
        runBlocking { local.upsert(contact("Alice")) }
        sync()
        remote.trash()
        val outcome = sync()
        assertEquals(1, outcome.uploaded)
        assertEquals("file-2", state.state.fileUid)
        assertEquals(listOf("Alice"), names(remote.contacts()))
        assertEquals(listOf("Alice"), names(local.snapshot().contacts))
    }

    @Test
    fun continuityLostReadsTheRemoteAgain() {
        setUp()
        runBlocking { local.upsert(contact("Alice")) }
        sync()
        remote.externalUpload(remote.contacts() + contact("Bob"), withEvent = false)
        remote.emitContinuityLost()
        val outcome = sync()
        assertEquals(1, remote.getFileCalls)
        assertEquals(1, outcome.downloaded)
        assertEquals(listOf("Alice", "Bob"), names(local.snapshot().contacts))
    }

    @Test
    fun localEditDuringDownloadSurvivesTheMerge() {
        setUp()
        runBlocking { local.upsert(contact("Alice")) }
        sync()
        remote.externalUpload(remote.contacts() + contact("Bob"))
        remote.beforeDownload = {
            remote.beforeDownload = {}
            local.upsert(contact("Xavier"))
        }
        val outcome = sync()
        assertEquals(1, outcome.downloaded)
        assertEquals(listOf("Alice", "Bob", "Xavier"), names(local.snapshot().contacts))
        assertEquals(listOf("Alice", "Bob", "Xavier"), names(remote.contacts()))
    }

    @Test
    fun conflictsKeepLocalValuesAndUploadProvisionalValues() {
        setUp()
        runBlocking { local.upsert(contact("Alice")) }
        sync()
        runBlocking { local.upsert(contact("Alice", nickname = "L")) }
        remote.externalUpload(listOf(contact("Alice", nickname = "R")))
        val outcome = sync()
        assertEquals(1, outcome.conflicts)

        val stored = conflicts.conflicts.value.single()
        assertEquals(ConflictKind.FIELDS, stored.kind)
        assertEquals(listOf(Field.NICKNAME), stored.fields)
        assertEquals("L", stored.local?.nickname)
        assertEquals("R", stored.remote?.nickname)
        assertEquals("R", local.snapshot().contacts.single().nickname)
        assertEquals("R", remote.contacts().single().nickname)
        assertNotNull(runBlocking { conflicts.lastConflictFiles() })
    }

    @Test
    fun conflictFilesHoldTheInputsOfTheSecondMergePass() {
        setUp()
        runBlocking { local.upsert(contact("Alice")) }
        sync()
        remote.externalUpload(listOf(contact("Alice", nickname = "R")))
        remote.beforeDownload = {
            remote.beforeDownload = {}
            local.upsert(contact("Alice", nickname = "L"))
        }
        val outcome = sync()
        assertEquals(1, outcome.conflicts)
        val (localFile, remoteFile) = runBlocking { conflicts.lastConflictFiles() }!!
        assertEquals("L", localFile.single().nickname)
        assertEquals("R", remoteFile.single().nickname)
    }

    @Test
    fun phoneMirrorFailureIsReportedButDriveSyncGoesOn() {
        setUp()
        val failing = object : PhoneContactsStore {
            override fun isAvailable() = true
            override suspend fun accountToken(): String = fail()
            override suspend fun fingerprint(): String = fail()
            override suspend fun readAll(): List<PhoneContact> = fail()
            override suspend fun assignSourceIds(uids: Map<Long, String>) = fail()
            override suspend fun write(target: List<Contact>, current: List<PhoneContact>): Boolean = fail()
            override suspend fun clearDirty(rawContacts: Collection<PhoneContact>) = fail()
            private fun fail(): Nothing = throw IllegalStateException("no permission")
        }
        val dir = tmp.newFolder()
        val mirrored = LocalContactsRepository(dir, clock) {}
        val engine = SyncEngine(
            remote, mirrored, ConflictStore(dir, mirrored, clock), SyncStateStore(dir),
            ContactsMirror(failing, mirrored, dir, clock), clock,
        ) { "Phone: " + it.message }
        runBlocking { mirrored.upsert(contact("Alice")) }
        val outcome = runBlocking { engine.sync() } as SyncOutcome.Success
        assertEquals("Phone: no permission", outcome.phoneError)
        assertEquals(1, outcome.uploaded)
        assertEquals(listOf("Alice"), names(remote.contacts()))
        assertEquals("Phone: no permission", SyncStateStore(dir).state.lastError)
    }
}
