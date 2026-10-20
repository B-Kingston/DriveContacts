package dev.drivecontacts.core

import dev.drivecontacts.core.model.Contact
import dev.drivecontacts.core.model.Kind
import dev.drivecontacts.core.model.Organization
import dev.drivecontacts.core.model.PostalAddress
import dev.drivecontacts.core.model.StructuredName
import dev.drivecontacts.core.model.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDifferTest {
    private fun row(rows: List<DiffRow>, label: String) = rows.single { it.label == label }

    @Test
    fun rowLabelsAreInModelOrder() {
        assertEquals(
            listOf("Name", "Structured name", "Nickname", "Organization", "Title", "Birthday", "Phones", "Emails", "Addresses", "Websites", "Note", "Other properties"),
            Diff.rows(Contact("u"), Contact("u")).map { it.label },
        )
    }

    @Test
    fun emptyScalarsGiveEmptyListsAndUnchanged() {
        val rows = Diff.rows(Contact("u"), Contact("u"))
        assertTrue(rows.all { it.left.isEmpty() && it.right.isEmpty() && !it.changed })
    }

    @Test
    fun itemFormats() {
        val c = Contact(
            "u", formattedName = "F",
            name = StructuredName(family = "Fam", given = "Giv", prefix = "Dr", suffix = "Jr"),
            organization = Organization("Org", "Dept"),
            phones = listOf(
                TypedValue(Kind.MOBILE, "1"), TypedValue(Kind.HOME, "2"), TypedValue(Kind.WORK, "3"), TypedValue(Kind.MAIN, "4"),
                TypedValue(Kind.FAX_HOME, "5"), TypedValue(Kind.FAX_WORK, "6"), TypedValue(Kind.FAX_OTHER, "7"), TypedValue(Kind.PAGER, "8"),
                TypedValue(Kind.OTHER, "9"), TypedValue(Kind.CUSTOM, "10", "Boat"),
            ),
            emails = listOf(TypedValue(Kind.WORK, "a@x")),
            addresses = listOf(PostalAddress(Kind.HOME, poBox = "PB", extended = "Ext", street = "St", locality = "Loc", region = "Reg", postalCode = "Zip", country = "Co")),
            urls = listOf("https://x"), extras = listOf("g.X-A:1"),
        )
        val rows = Diff.rows(null, c)
        assertEquals(listOf("F"), row(rows, "Name").right)
        assertEquals(listOf("Dr Giv Fam Jr"), row(rows, "Structured name").right)
        assertEquals(listOf("Org, Dept"), row(rows, "Organization").right)
        assertEquals(
            listOf("Mobile: 1", "Home: 2", "Work: 3", "Main: 4", "Home fax: 5", "Work fax: 6", "Other fax: 7", "Pager: 8", "Other: 9", "Boat: 10"),
            row(rows, "Phones").right,
        )
        assertEquals(listOf("Work: a@x"), row(rows, "Emails").right)
        assertEquals(listOf("Home: St, Ext, PB, Loc, Reg, Zip, Co"), row(rows, "Addresses").right)
        assertEquals(listOf("https://x"), row(rows, "Websites").right)
        assertEquals(listOf("g.X-A:1"), row(rows, "Other properties").right)
        assertTrue(rows.filter { it.right.isNotEmpty() }.all { it.left.isEmpty() && it.changed })
    }

    @Test
    fun organizationWithoutDepartmentHasNoComma() {
        val rows = Diff.rows(Contact("u", organization = Organization("", "Dept")), Contact("u", organization = Organization("Org")))
        assertEquals(listOf("Dept"), row(rows, "Organization").left)
        assertEquals(listOf("Org"), row(rows, "Organization").right)
    }

    @Test
    fun onlyDifferingRowsAreChanged() {
        val rows = Diff.rows(Contact("u", title = "a", note = "n"), Contact("u", title = "b", note = "n"))
        assertTrue(row(rows, "Title").changed)
        assertFalse(row(rows, "Note").changed)
    }

    @Test
    fun statusAndUnchangedAreHandled() {
        val same = Contact("same", formattedName = "S", rev = 1)
        val changed = Contact("chg", formattedName = "C", title = "a")
        val d = Diff.diff(
            listOf(same, changed, Contact("gone", formattedName = "G")),
            listOf(same.copy(rev = 2), changed.copy(title = "b"), Contact("new", formattedName = "N")),
        )
        assertEquals(listOf("chg" to DiffStatus.CHANGED, "gone" to DiffStatus.REMOVED, "new" to DiffStatus.ADDED), d.map { it.uid to it.status })
    }

    @Test
    fun sortedByDisplayNameIgnoringCaseThenUid() {
        val d = Diff.diff(
            emptyList(),
            listOf(Contact("3", formattedName = "bob"), Contact("2", formattedName = "Alice"), Contact("1", formattedName = "alice"), Contact("0", formattedName = "Bob")),
        )
        assertEquals(listOf("1", "2", "0", "3"), d.map { it.uid })
    }

    @Test
    fun displayNamePrefersRightThenLeftThenPlaceholder() {
        val d = Diff.diff(
            listOf(Contact("a", formattedName = "Old"), Contact("b", formattedName = "Old"), Contact("c")),
            listOf(Contact("a", formattedName = "New"), Contact("b", formattedName = "", title = "t"), Contact("c", title = "t")),
        ).associate { it.uid to it.displayName }
        assertEquals("New", d["a"])
        assertEquals("Old", d["b"])
        assertEquals("(No name)", d["c"])
    }
}
