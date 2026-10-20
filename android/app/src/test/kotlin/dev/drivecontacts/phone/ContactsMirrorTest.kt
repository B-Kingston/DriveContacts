package dev.drivecontacts.phone

import dev.drivecontacts.core.model.Contact
import dev.drivecontacts.core.model.Kind
import dev.drivecontacts.core.model.TypedValue
import dev.drivecontacts.data.LocalContactsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContactsMirrorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var time = 1_000_000L
    private val clock: () -> Long = { time += 1000; time }
    private val phone = FakePhoneStore()
    private lateinit var dir: File
    private lateinit var local: LocalContactsRepository
    private lateinit var mirror: ContactsMirror

    private fun setUp() {
        dir = tmp.newFolder()
        local = LocalContactsRepository(dir, clock) {}
        mirror = ContactsMirror(phone, local, dir, clock)
    }

    private fun alice(note: String = "") = Contact(uid = "urn:uuid:alice", formattedName = "Alice", note = note)

    private fun run() = runBlocking { mirror.mirror() }

    @Test
    fun firstPassCopiesLocalContactsToPhone() {
        setUp()
        runBlocking { local.upsert(alice("hi")) }
        assertFalse(run())
        assertEquals("hi", phone.raws.single().contact.note)
        assertEquals("urn:uuid:alice", phone.raws.single().sourceId)
    }

    @Test
    fun phoneEditReachesLocal() {
        setUp()
        runBlocking { local.upsert(alice()) }
        run()
        val number = TypedValue(Kind.MOBILE, "+1 555 0100")
        phone.edit("urn:uuid:alice") { it.copy(phones = listOf(number)) }
        assertTrue(run())
        assertEquals(listOf(number), local.snapshot().contacts.single().phones)
    }

    @Test
    fun localEditReachesPhone() {
        setUp()
        runBlocking { local.upsert(alice()) }
        run()
        runBlocking { local.upsert(alice("changed")) }
        assertFalse(run())
        assertEquals("changed", phone.raws.single().contact.note)
    }

    @Test
    fun phoneDeleteRemovesLocalContactAndPurgesRow() {
        setUp()
        runBlocking { local.upsert(alice()) }
        run()
        phone.markDeleted("urn:uuid:alice")
        assertTrue(run())
        assertTrue(local.snapshot().contacts.isEmpty())
        assertTrue(phone.raws.isEmpty())
    }

    @Test
    fun newPhoneContactGetsUidAndIsWrittenBack() {
        setUp()
        val rawId = phone.addNew(Contact(uid = "", formattedName = "Bob"))
        assertTrue(run())
        val added = local.snapshot().contacts.single()
        assertEquals("Bob", added.formattedName)
        assertTrue(added.uid.startsWith("urn:uuid:"))
        val raw = phone.raws.single()
        assertEquals(added.uid, raw.sourceId)
        assertEquals(rawId, raw.rawContactId)
        assertNull(phone.raws.firstOrNull { it.sourceId == null })
    }

    @Test
    fun extrasSurviveAPhoneEdit() {
        setUp()
        runBlocking { local.upsert(alice().copy(extras = listOf("X-FOO:bar"))) }
        run()
        phone.edit("urn:uuid:alice") { it.copy(note = "from phone") }
        assertTrue(run())
        val result = local.snapshot().contacts.single()
        assertEquals("from phone", result.note)
        assertEquals(listOf("X-FOO:bar"), result.extras)
    }

    @Test
    fun unavailablePhoneStoreChangesNothing() {
        setUp()
        runBlocking { local.upsert(alice()) }
        phone.available = false
        assertFalse(run())
        assertTrue(phone.raws.isEmpty())
    }

    @Test
    fun emptyNewRawContactIsNeitherImportedNorDeleted() {
        setUp()
        val id = phone.addNew(Contact(uid = ""))
        assertFalse(run())
        assertTrue(local.snapshot().contacts.isEmpty())
        assertEquals(listOf(id), phone.raws.map { it.rawContactId })
    }

    @Test
    fun phoneDeleteOfContactWithoutContentSticks() {
        // Seen on a device: the contact came back after the delete because the mirror base lost it.
        setUp()
        runBlocking { local.upsert(Contact(uid = "urn:uuid:blank")) }
        run()
        phone.markDeleted("urn:uuid:blank")
        assertTrue(run())
        assertTrue(local.snapshot().contacts.isEmpty())
        assertTrue(phone.raws.isEmpty())
        run()
        assertTrue(phone.raws.isEmpty())
    }

    @Test
    fun dirtyRowsAreClearedAfterThePass() {
        setUp()
        runBlocking { local.upsert(alice()) }
        run()
        phone.edit("urn:uuid:alice") { it.copy(note = "x") }
        run()
        assertFalse(phone.raws.single().dirty)
    }

    private fun bob() = Contact(uid = "urn:uuid:bob", formattedName = "Bob")

    @Test
    fun emptiedPhoneStoreKeepsAllContactsAndRestoresThem() {
        setUp()
        runBlocking {
            local.upsert(alice())
            local.upsert(bob())
        }
        run()
        phone.raws.clear()
        run()
        assertEquals(setOf("urn:uuid:alice", "urn:uuid:bob"), local.snapshot().contacts.map { it.uid }.toSet())
        assertEquals(setOf("urn:uuid:alice", "urn:uuid:bob"), phone.raws.map { it.sourceId }.toSet())
    }

    @Test
    fun baseOfAnotherAccountInstanceIsNotUsed() {
        setUp()
        runBlocking { local.upsert(alice()) }
        run()
        // The account was removed and made again: its rows are gone, and the user has added a contact since.
        phone.token = "token-2"
        phone.raws.clear()
        phone.addNew(Contact(uid = "", formattedName = "Carol"))
        run()
        assertEquals(setOf("Alice", "Carol"), local.snapshot().contacts.map { it.formattedName }.toSet())
        assertEquals(setOf("Alice", "Carol"), phone.raws.map { it.contact.formattedName }.toSet())
        assertEquals("token-2", File(dir, "contacts/mirror-base.account").readText())
    }

    @Test
    fun phoneStoreWithOnlyNewRowsIsALostStore() {
        setUp()
        runBlocking { local.upsert(alice()) }
        run()
        phone.raws.clear()
        phone.addNew(Contact(uid = "", formattedName = "Carol"))
        run()
        assertEquals(setOf("Alice", "Carol"), local.snapshot().contacts.map { it.formattedName }.toSet())
        assertEquals(setOf("Alice", "Carol"), phone.raws.map { it.contact.formattedName }.toSet())
    }

    @Test
    fun baseWithoutTokenFileIsKeptAfterAnUpdate() {
        setUp()
        runBlocking { local.upsert(alice()) }
        run()
        File(dir, "contacts/mirror-base.account").delete()
        // A local edit that the phone does not have yet: with an empty base, the old phone value would win.
        runBlocking { local.upsert(alice("changed")) }
        run()
        assertEquals("changed", local.snapshot().contacts.single().note)
        assertEquals("changed", phone.raws.single().contact.note)
        assertEquals("token-1", File(dir, "contacts/mirror-base.account").readText())
    }

    @Test
    fun failedReadChangesNothing() {
        setUp()
        runBlocking { local.upsert(alice()) }
        run()
        phone.failReads = true
        assertTrue(runCatching { run() }.isFailure)
        phone.failReads = false
        assertEquals(listOf("urn:uuid:alice"), local.snapshot().contacts.map { it.uid })
        assertEquals(listOf("urn:uuid:alice"), phone.raws.map { it.sourceId })
    }

    @Test
    fun failedWriteDoesNotDuplicateANewPhoneContact() {
        setUp()
        phone.addNew(Contact(uid = "", formattedName = "Carol"))
        phone.failWrites = true
        assertTrue(runCatching { run() }.isFailure)
        phone.failWrites = false
        run()
        assertEquals(listOf("Carol"), local.snapshot().contacts.map { it.formattedName })
        assertEquals(1, phone.raws.size)
    }

    @Test
    fun baseFileIsNotRewrittenWhenUnchanged() {
        setUp()
        runBlocking { local.upsert(alice()) }
        run()
        val base = File(dir, "contacts/mirror-base.vcf")
        base.setLastModified(12_345_000L)
        // The edit changes the fingerprint but not the content.
        phone.edit("urn:uuid:alice") { it }
        run()
        assertEquals(12_345_000L, base.lastModified())
    }

    @Test
    fun passWithoutChangesSkipsTheReadOfTheRows() {
        setUp()
        runBlocking { local.upsert(alice()) }
        run()
        run()
        val reads = phone.reads
        assertFalse(run())
        assertEquals(reads, phone.reads)
        runBlocking { local.upsert(alice("changed")) }
        run()
        assertTrue(phone.reads > reads)
        assertEquals("changed", phone.raws.single().contact.note)
    }
}
