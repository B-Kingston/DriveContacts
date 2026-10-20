package dev.drivecontacts.core

import dev.drivecontacts.core.model.Contact
import dev.drivecontacts.core.model.Kind
import dev.drivecontacts.core.model.Organization
import dev.drivecontacts.core.model.StructuredName
import dev.drivecontacts.core.model.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeWayMergeTest {
    private fun phone(v: String) = TypedValue(Kind.MOBILE, v)

    @Test
    fun resultIsSortedByUid() {
        val r = Merge.merge3(emptyList(), listOf(Contact("b"), Contact("a")), listOf(Contact("c")))
        assertEquals(listOf("a", "b", "c"), r.contacts.map { it.uid })
    }

    @Test
    fun multisetListMergeHandlesDuplicates() {
        val b = Contact("u", phones = listOf(phone("1"), phone("1")))
        val l = Contact("u", phones = listOf(phone("1")))
        val r = Contact("u", phones = listOf(phone("1"), phone("1"), phone("2")))
        val m = Merge.merge3(listOf(b), listOf(l), listOf(r)).contacts.single()
        assertEquals(listOf(phone("1"), phone("2")), m.phones)
    }

    @Test
    fun allScalarFieldsMergeIndependently() {
        val b = Contact("u")
        val l = Contact("u", formattedName = "L", name = StructuredName(given = "L"), nickname = "L")
        val r = Contact("u", organization = Organization("R"), title = "R", birthday = "2000-01-01", note = "R")
        val m = Merge.merge3(listOf(b), listOf(l), listOf(r))
        assertTrue(m.conflicts.isEmpty())
        val c = m.contacts.single()
        assertEquals("L", c.formattedName)
        assertEquals("L", c.name.given)
        assertEquals("L", c.nickname)
        assertEquals("R", c.organization.name)
        assertEquals("R", c.title)
        assertEquals("2000-01-01", c.birthday)
        assertEquals("R", c.note)
    }

    @Test
    fun conflictListsEveryConflictingFieldWithProvisionalRemote() {
        val b = Contact("u", title = "b", note = "b", nickname = "b")
        val l = Contact("u", title = "l", note = "l", nickname = "l2")
        val r = Contact("u", title = "r", note = "r", nickname = "b")
        val m = Merge.merge3(listOf(b), listOf(l), listOf(r))
        val conflict = m.conflicts.single()
        assertEquals(ConflictKind.FIELDS, conflict.kind)
        assertEquals(listOf(Field.TITLE, Field.NOTE), conflict.fields)
        assertEquals(l, conflict.local)
        assertEquals(r, conflict.remote)
        assertEquals(b, conflict.base)
        val c = m.contacts.single()
        assertEquals("r", c.title)
        assertEquals("r", c.note)
        assertEquals("l2", c.nickname)
    }

    @Test
    fun deletionConflictsCarrySides() {
        val b = Contact("u", title = "b")
        val changed = b.copy(title = "x")
        val del = Merge.merge3(listOf(b), emptyList(), listOf(changed)).conflicts.single()
        assertEquals(ConflictKind.DELETED_LOCALLY, del.kind)
        assertEquals(null, del.local)
        assertEquals(changed, del.remote)
        val del2 = Merge.merge3(listOf(b), listOf(changed), emptyList()).conflicts.single()
        assertEquals(ConflictKind.DELETED_REMOTELY, del2.kind)
        assertEquals(null, del2.remote)
    }

    @Test
    fun revOnlyChangeCountsAsUnchanged() {
        val b = Contact("u", rev = 1, title = "b")
        val m = Merge.merge3(listOf(b), emptyList(), listOf(b.copy(rev = 99)))
        assertTrue(m.contacts.isEmpty())
        assertTrue(m.conflicts.isEmpty())
    }

    @Test
    fun revFollowsTheSideWithTheMergedContent() {
        val b = Contact("u", rev = 1)
        val m = Merge.merge3(listOf(b), listOf(b.copy(rev = 5, title = "l")), listOf(b.copy(rev = 3, note = "r"))).contacts.single()
        assertEquals(5L, m.rev)
        val m2 = Merge.merge3(listOf(b), listOf(b.copy(rev = 5)), listOf(b.copy(rev = 3, note = "r"))).contacts.single()
        assertEquals(3L, m2.rev)
        val m3 = Merge.merge3(listOf(b), listOf(b.copy(rev = 5, note = "x")), listOf(b.copy(rev = 3))).contacts.single()
        assertEquals(5L, m3.rev)
    }

    @Test
    fun resolveFieldsCopiesOnlyChosenFieldsAndSetsRev() {
        val conflict = Conflict(
            "u", ConflictKind.FIELDS, null,
            Contact("u", title = "L", note = "L", nickname = "L"),
            Contact("u", title = "R", note = "R", nickname = "R"),
            listOf(Field.TITLE, Field.NOTE),
        )
        val current = Contact("u", rev = 1, title = "R", note = "R", nickname = "edited")
        val out = Merge.resolveFields(conflict, current, setOf(Field.TITLE, Field.NICKNAME), now = 42)
        assertEquals(Contact("u", rev = 42, title = "L", note = "R", nickname = "edited"), out)
    }

    @Test
    fun resolveFieldsWithoutLocalOnlyTouchesRev() {
        val conflict = Conflict("u", ConflictKind.DELETED_LOCALLY, null, null, Contact("u"), emptyList())
        val current = Contact("u", rev = 1, title = "t")
        assertEquals(current.copy(rev = 7), Merge.resolveFields(conflict, current, Field.values().toSet(), 7))
    }

    @Test
    fun resolveFieldsCoversEveryField() {
        val local = Contact(
            "u", formattedName = "f", name = StructuredName(given = "g"), nickname = "n",
            organization = Organization("o"), title = "t", birthday = "b", note = "x",
        )
        val conflict = Conflict("u", ConflictKind.FIELDS, null, local, Contact("u"), Field.values().toList())
        val out = Merge.resolveFields(conflict, Contact("u"), Field.values().toSet(), 1)
        assertEquals(local.copy(rev = 1), out)
    }
}
