package dev.cardvault.phone

import dev.cardvault.core.model.Contact
import dev.cardvault.core.model.Kind
import dev.cardvault.core.model.TypedValue
import dev.cardvault.data.LocalContactsRepository
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
}
