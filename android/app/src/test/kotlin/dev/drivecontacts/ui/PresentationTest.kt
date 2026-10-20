package dev.drivecontacts.ui

import dev.drivecontacts.core.DiffRow
import dev.drivecontacts.core.Field
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
import java.util.Locale

class PresentationTest {
    private val collator = displayCollator(Locale.ENGLISH)

    private val anna = Contact(
        uid = "u1",
        formattedName = "Anna Müller",
        name = StructuredName(family = "Müller", given = "Anna"),
        organization = Organization(name = "Acme"),
        phones = listOf(TypedValue(Kind.MOBILE, "+49 (170) 123-4567")),
        emails = listOf(TypedValue(Kind.WORK, "Anna.Mueller@Example.org")),
    )

    @Test
    fun blankQueryMatchesAll() {
        assertTrue(matchesQuery(anna, ""))
        assertTrue(matchesQuery(anna, "   "))
    }

    @Test
    fun nameMatchIgnoresCase() {
        assertTrue(matchesQuery(anna, "MÜLLER"))
        assertTrue(matchesQuery(anna, "acme"))
        assertFalse(matchesQuery(anna, "bob"))
    }

    @Test
    fun emailMatchIgnoresCase() {
        assertTrue(matchesQuery(anna, "MUELLER@example"))
    }

    @Test
    fun phoneMatchesByDigitsAcrossPunctuation() {
        assertTrue(matchesQuery(anna, "170123"))
        assertTrue(matchesQuery(anna, "170 123"))
        assertTrue(matchesQuery(anna, "(170)-123"))
        assertFalse(matchesQuery(anna, "1700"))
    }

    @Test
    fun mixedQueryDoesNotMatchByDigitsOnly() {
        assertFalse(matchesQuery(anna, "bob 170"))
    }

    @Test
    fun filterKeepsOrder() {
        val bob = Contact(uid = "u2", formattedName = "Bob")
        val entries = indexContacts(listOf(anna, bob), collator)
        assertEquals(listOf(bob), filterEntries(entries, "bo"))
        assertEquals(listOf(anna, bob), filterEntries(entries, ""))
    }

    @Test
    fun searchIgnoresAccents() {
        val jose = Contact(uid = "u3", formattedName = "José Núñez")
        assertTrue(matchesQuery(jose, "jose"))
        assertTrue(matchesQuery(jose, "NUNEZ"))
        assertTrue(matchesQuery(jose, "josé"))
        assertTrue(matchesQuery(anna, "muller"))
        assertFalse(matchesQuery(jose, "joseph"))
    }

    @Test
    fun displayOrderUsesCollatorThenUid() {
        fun named(uid: String, name: String) = Contact(uid = uid, formattedName = name)
        val contacts = listOf(
            named("u5", "Zoe"), named("u4", "Eve"), named("u3", "Émile"), named("u2", "adam"), named("u1", "adam"),
        )
        val order = indexContacts(contacts, collator).map { it.contact.uid }
        assertEquals(listOf("u1", "u2", "u3", "u4", "u5"), order)
    }

    @Test
    fun formattedNameKeptWhenSet() {
        assertEquals("Dr. A", autoFormattedName(" Dr. A ", StructuredName(given = "Anna")))
    }

    @Test
    fun formattedNameFilledFromPartsInOrder() {
        val name = StructuredName(family = "Müller", given = "Anna", additional = "Maria", prefix = "Dr.", suffix = "Jr.")
        assertEquals("Dr. Anna Maria Müller Jr.", autoFormattedName("  ", name))
    }

    @Test
    fun formattedNameFillSkipsEmptyParts() {
        assertEquals("Anna", autoFormattedName("", StructuredName(given = "Anna")))
        assertEquals("", autoFormattedName("", StructuredName()))
    }

    @Test
    fun displayNameFallsBack() {
        assertEquals("Acme", displayName(Contact(uid = "x", organization = Organization(name = "Acme"))))
        assertEquals("a@b.c", displayName(Contact(uid = "x", emails = listOf(TypedValue(Kind.HOME, "a@b.c")))))
        assertEquals("Anna Lee", displayName(Contact(uid = "x", name = StructuredName(given = "Anna", family = "Lee"))))
        assertEquals("", displayName(Contact(uid = "x")))
        assertEquals("", displayName(null))
    }

    @Test
    fun subtitleIsEmptyWhenSameAsName() {
        assertEquals("", contactSubtitle(Contact(uid = "x", organization = Organization(name = "Acme"))))
        assertEquals("+49 (170) 123-4567", contactSubtitle(anna))
    }

    @Test
    fun avatarLetterUsesFirstLetterOrHash() {
        assertEquals("Ä", avatarLetter("  ärger"))
        assertEquals("#", avatarLetter(""))
        assertEquals("#", avatarLetter("--"))
    }

    @Test
    fun saveDropsEmptyItemsAndFillsName() {
        val form = Contact(
            uid = "u",
            name = StructuredName(given = " Anna ", family = "Lee"),
            phones = listOf(TypedValue(Kind.MOBILE, "  "), TypedValue(Kind.HOME, " 123 ")),
            emails = listOf(TypedValue(Kind.WORK, "")),
            addresses = listOf(PostalAddress(kind = Kind.HOME), PostalAddress(kind = Kind.WORK, locality = "Paris")),
            urls = listOf("", " https://x.org "),
            note = " keep \n",
            extras = listOf("X-FOO:1"),
        )
        val saved = prepareForSave(form)
        assertEquals("Anna Lee", saved.formattedName)
        assertEquals(listOf(TypedValue(Kind.HOME, "123")), saved.phones)
        assertTrue(saved.emails.isEmpty())
        assertEquals(1, saved.addresses.size)
        assertEquals("Paris", saved.addresses[0].locality)
        assertEquals(listOf("https://x.org"), saved.urls)
        assertEquals(" keep \n", saved.note)
        assertEquals(listOf("X-FOO:1"), saved.extras)
    }

    @Test
    fun customKindWithoutLabelBecomesOther() {
        val saved = prepareForSave(
            Contact(uid = "u", phones = listOf(TypedValue(Kind.CUSTOM, "1", customLabel = " "), TypedValue(Kind.CUSTOM, "2", customLabel = " Boat "))),
        )
        assertEquals(TypedValue(Kind.OTHER, "1"), saved.phones[0])
        assertEquals(TypedValue(Kind.CUSTOM, "2", customLabel = "Boat"), saved.phones[1])
    }

    @Test
    fun labelIsClearedWhenKindIsNotCustom() {
        val saved = prepareForSave(Contact(uid = "u", emails = listOf(TypedValue(Kind.HOME, "a@b.c", customLabel = "old"))))
        assertEquals("", saved.emails[0].customLabel)
    }

    @Test
    fun emptyFormHasNoContent() {
        assertFalse(hasContent(prepareForSave(Contact(uid = "u", phones = listOf(TypedValue(Kind.HOME, " "))))))
        assertTrue(hasContent(Contact(uid = "u", note = "x")))
        assertTrue(hasContent(prepareForSave(Contact(uid = "u", name = StructuredName(given = "A")))))
    }

    @Test
    fun fieldTextShowsEachField() {
        val c = Contact(
            uid = "u", formattedName = "FN", name = StructuredName(given = "A", family = "B"), nickname = "nick",
            organization = Organization("Org", "Dep"), title = "T", birthday = "2000-01-02", note = "N",
        )
        assertEquals("FN", fieldText(c, Field.FORMATTED_NAME))
        assertEquals("A B", fieldText(c, Field.NAME))
        assertEquals("nick", fieldText(c, Field.NICKNAME))
        assertEquals("Org, Dep", fieldText(c, Field.ORGANIZATION))
        assertEquals("T", fieldText(c, Field.TITLE))
        assertEquals("2000-01-02", fieldText(c, Field.BIRTHDAY))
        assertEquals("N", fieldText(c, Field.NOTE))
        assertEquals("", fieldText(null, Field.NOTE))
    }

    @Test
    fun visibleRowIndicesHidesEmptyUnchangedRows() {
        val rows = listOf(
            DiffRow("A", emptyList(), emptyList(), false),
            DiffRow("B", listOf("x"), listOf("x"), false),
            DiffRow("C", emptyList(), listOf("y"), true),
        )
        assertEquals(listOf(1, 2), visibleRowIndices(rows))
        assertEquals(listOf(0, 1, 2), visibleRowIndices(rows, listOf(listOf("old"), emptyList(), emptyList())))
    }

    @Test
    fun alignBaseMatchesByLabel() {
        val rows = listOf(DiffRow("A", listOf("1"), listOf("2"), true), DiffRow("B", emptyList(), emptyList(), false))
        val base = listOf(DiffRow("B", listOf("b"), emptyList(), true))
        assertEquals(listOf(emptyList(), listOf("b")), alignBase(rows, base))
    }

    @Test
    fun relativeTimeBoundaries() {
        val now = 10_000_000_000L
        assertEquals(RelativeTime.Now, relativeTime(now, now))
        assertEquals(RelativeTime.Now, relativeTime(now, now - 59_999))
        assertEquals(RelativeTime.Minutes(1), relativeTime(now, now - 60_000))
        assertEquals(RelativeTime.Minutes(59), relativeTime(now, now - 59 * 60_000L))
        assertEquals(RelativeTime.Hours(1), relativeTime(now, now - 60 * 60_000L))
        assertEquals(RelativeTime.Hours(23), relativeTime(now, now - 23 * 3_600_000L - 59 * 60_000L))
        assertEquals(RelativeTime.Days(1), relativeTime(now, now - 24 * 3_600_000L))
    }

    @Test
    fun futureTimeCountsAsNow() {
        assertEquals(RelativeTime.Now, relativeTime(1_000L, 5_000_000L))
    }

    private val edited = Contact(
        uid = "u",
        name = StructuredName(given = "Anna"),
        nickname = "ann",
        phones = listOf(TypedValue(Kind.MOBILE, "1")),
    )

    @Test
    fun mergeEditKeepsRemoteChangeOfUntouchedField() {
        val current = edited.copy(note = "from sync")
        val draft = edited.copy(name = StructuredName(given = "Annie"))
        val merged = mergeEdit(edited, current, draft)
        assertEquals("Annie", merged.name.given)
        assertEquals("from sync", merged.note)
    }

    @Test
    fun mergeEditLetsUserWinOnSameField() {
        val current = edited.copy(nickname = "remote")
        val draft = edited.copy(nickname = "mine")
        assertEquals("mine", mergeEdit(edited, current, draft).nickname)
    }

    @Test
    fun mergeEditKeepsListItemsAddedOnBothSides() {
        val current = edited.copy(phones = edited.phones + TypedValue(Kind.WORK, "2"))
        val draft = edited.copy(phones = edited.phones + TypedValue(Kind.HOME, "3"))
        val merged = mergeEdit(edited, current, draft)
        assertEquals(setOf("1", "2", "3"), merged.phones.map { it.value }.toSet())
        assertEquals(3, merged.phones.size)
    }

    @Test
    fun mergeEditUsesFormForNewOrGoneContact() {
        val draft = edited.copy(note = "x")
        val cleaned = prepareForSave(draft)
        assertEquals(cleaned, mergeEdit(null, null, draft))
        assertEquals(cleaned, mergeEdit(edited, null, draft))
    }

    @Test
    fun mergeEditDeletesItemThatIsStoredWithSpaces() {
        val current = edited.copy(phones = listOf(TypedValue(Kind.MOBILE, " 1 ")))
        val draft = edited.copy(phones = emptyList())
        assertTrue(mergeEdit(edited, current, draft).phones.isEmpty())
    }

    @Test
    fun mergeEditChangesItemThatIsStoredWithSpaces() {
        val current = edited.copy(phones = listOf(TypedValue(Kind.MOBILE, " 1 ")))
        val draft = edited.copy(phones = listOf(TypedValue(Kind.MOBILE, "2")))
        assertEquals(listOf(TypedValue(Kind.MOBILE, "2")), mergeEdit(edited, current, draft).phones)
    }
}
