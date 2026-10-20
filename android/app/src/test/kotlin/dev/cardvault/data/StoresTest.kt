package dev.cardvault.data

import dev.cardvault.core.Conflict
import dev.cardvault.core.ConflictKind
import dev.cardvault.core.Field
import dev.cardvault.core.model.Contact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoresTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var time = 5_000L
    private val clock: () -> Long = { time += 1000; time }
    private var edits = 0

    private fun repo(dir: File) = LocalContactsRepository(dir, clock) { edits++ }

    private fun c(name: String, nickname: String = "") =
        Contact(uid = "urn:uuid:" + name.lowercase(), formattedName = name, nickname = nickname)

    @Test
    fun contactsAreSortedByNameIgnoringCaseAndSurviveRestart() {
        val dir = tmp.newFolder()
        val r = repo(dir)
        runBlocking {
            r.upsert(c("bob"))
            r.upsert(c("Alice"))
            r.upsert(c("Carol"))
        }
        assertEquals(listOf("Alice", "bob", "Carol"), r.contacts.value.map { it.formattedName })
        assertEquals(listOf("Alice", "bob", "Carol"), repo(dir).contacts.value.map { it.formattedName })
        assertEquals(3, edits)
    }

    @Test
    fun compareAndSetFailsAfterAnotherChangeAndDoesNotSignalAnEdit() {
        val r = repo(tmp.newFolder())
        val first = r.snapshot()
        assertTrue(r.compareAndSet(first.version, listOf(c("Alice"))))
        assertFalse(r.compareAndSet(first.version, listOf(c("Bob"))))
        assertEquals(listOf("Alice"), r.contacts.value.map { it.formattedName })
        assertEquals(0, edits)
    }

    @Test
    fun deleteOfUnknownUidIsNotAnEdit() {
        val r = repo(tmp.newFolder())
        runBlocking { r.delete("urn:uuid:none") }
        assertEquals(0, edits)
    }

    @Test
    fun newConflictReplacesOldOneAndMissingContactsAreDropped() {
        val dir = tmp.newFolder()
        val r = repo(dir)
        val store = ConflictStore(dir, r, clock)
        runBlocking {
            r.upsert(c("Alice"))
            r.upsert(c("Bob"))
            val a1 = Conflict("urn:uuid:alice", ConflictKind.FIELDS, null, c("Alice"), c("Alice", "x"), listOf(Field.NICKNAME))
            val b1 = Conflict("urn:uuid:bob", ConflictKind.DELETED_REMOTELY, null, c("Bob"), null, emptyList())
            store.replaceFrom(listOf(a1, b1), emptyList(), emptyList())
            r.delete("urn:uuid:bob")
            val a2 = a1.copy(fields = listOf(Field.TITLE))
            store.replaceFrom(listOf(a2), listOf(c("Alice")), listOf(c("Alice")))
        }
        assertEquals(listOf(Field.TITLE), store.conflicts.value.single().fields)
        assertEquals(store.conflicts.value, ConflictStore(dir, r, clock).conflicts.value)
    }

    @Test
    fun resolveFieldsWritesLocalEditAndClosesConflict() {
        val dir = tmp.newFolder()
        val r = repo(dir)
        val store = ConflictStore(dir, r, clock)
        runBlocking {
            val current = c("Alice", "R")
            r.upsert(current)
            val conflict = Conflict(current.uid, ConflictKind.FIELDS, c("Alice"), c("Alice", "L"), current, listOf(Field.NICKNAME))
            store.replaceFrom(listOf(conflict), listOf(c("Alice", "L")), listOf(current))
            edits = 0
            store.resolveFields(current.uid, setOf(Field.NICKNAME))
        }
        assertEquals("L", r.contacts.value.single().nickname)
        assertTrue(store.conflicts.value.isEmpty())
        assertEquals(1, edits)
        assertNull(runBlocking { store.lastConflictFiles() })
    }

    @Test
    fun resolveDeleteRemovesTheContact() {
        val dir = tmp.newFolder()
        val r = repo(dir)
        val store = ConflictStore(dir, r, clock)
        runBlocking {
            r.upsert(c("Alice"))
            store.replaceFrom(
                listOf(Conflict("urn:uuid:alice", ConflictKind.DELETED_LOCALLY, c("Alice"), null, c("Alice", "x"), emptyList())),
                emptyList(),
                emptyList(),
            )
            store.resolveDelete("urn:uuid:alice")
        }
        assertTrue(r.contacts.value.isEmpty())
        assertTrue(store.conflicts.value.isEmpty())
    }

    @Test
    fun syncStateAndBaseSurviveRestart() {
        val dir = tmp.newFolder()
        val s = SyncStateStore(dir)
        s.update { it.copy(fileUid = "f", baseRevisionUid = "r1", lastError = "boom") }
        s.writeBase("abc".toByteArray())
        val again = SyncStateStore(dir)
        assertEquals("f", again.state.fileUid)
        assertEquals("boom", again.state.lastError)
        assertEquals("abc", again.readBase().decodeToString())
    }
}
