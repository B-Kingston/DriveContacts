package dev.cardvault.core

import dev.cardvault.core.model.Contact
import dev.cardvault.core.model.Kind
import dev.cardvault.core.model.Organization
import dev.cardvault.core.model.PostalAddress
import dev.cardvault.core.model.StructuredName
import dev.cardvault.core.model.TypedValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardWriterTest {
    private fun write(vararg c: Contact) = String(VCard.serialize(c.toList()), Charsets.UTF_8)

    @Test
    fun fullCardPropertyOrderAndFormat() {
        val c = Contact(
            uid = "u1",
            rev = 1709634030000L,
            formattedName = "Ada Lovelace",
            name = StructuredName(family = "Lovelace", given = "Ada"),
            nickname = "Ada",
            organization = Organization("Org", "Dept"),
            title = "Boss",
            birthday = "1815-12-10",
            phones = listOf(TypedValue(Kind.MOBILE, "1"), TypedValue(Kind.CUSTOM, "2", "Boat")),
            emails = listOf(TypedValue(Kind.MOBILE, "a@x"), TypedValue(Kind.MAIN, "b@x")),
            addresses = listOf(PostalAddress(Kind.WORK, street = "S", locality = "L", country = "C")),
            urls = listOf("https://x"),
            note = "n",
            extras = listOf("X-FOO:bar"),
        )
        val expected = listOf(
            "BEGIN:VCARD", "VERSION:4.0", "UID:u1", "REV:20240305T102030Z", "FN:Ada Lovelace", "N:Lovelace;Ada;;;",
            "NICKNAME:Ada", "ORG:Org;Dept", "TITLE:Boss", "BDAY:1815-12-10",
            "TEL;TYPE=cell:1", "cv1.TEL:2", "cv1.X-ABLabel:Boat",
            "EMAIL;TYPE=x-mobile:a@x", "EMAIL;TYPE=x-other:b@x",
            "ADR;TYPE=work:;;S;L;;;C", "URL:https://x", "NOTE:n", "X-FOO:bar", "END:VCARD",
        ).joinToString("") { it + "\r\n" }
        assertEquals(expected, write(c))
    }

    @Test
    fun emptyPropertiesAreNotWritten() {
        val out = write(Contact(uid = "u"))
        assertEquals("BEGIN:VCARD\r\nVERSION:4.0\r\nUID:u\r\nREV:19700101T000000Z\r\nEND:VCARD\r\n", out)
    }

    @Test
    fun orgWithoutDepartmentHasNoSemicolon() {
        assertTrue(write(Contact("u", organization = Organization("Org"))).contains("\r\nORG:Org\r\n"))
        assertTrue(write(Contact("u", organization = Organization("", "Dept"))).contains("\r\nORG:;Dept\r\n"))
    }

    @Test
    fun customLabelGroupsAreNumberedPerCardInOutputOrder() {
        val c = Contact(
            "u",
            phones = listOf(TypedValue(Kind.CUSTOM, "1", "A")),
            emails = listOf(TypedValue(Kind.CUSTOM, "e", "B")),
            addresses = listOf(PostalAddress(Kind.CUSTOM, "C", street = "s")),
        )
        val out = write(c, Contact("v", phones = listOf(TypedValue(Kind.CUSTOM, "2", "D"))))
        assertTrue(out.contains("cv1.TEL:1\r\ncv1.X-ABLabel:A\r\ncv2.EMAIL:e\r\ncv2.X-ABLabel:B\r\ncv3.ADR:;;s;;;;\r\ncv3.X-ABLabel:C\r\n"))
        assertTrue(out.contains("cv1.TEL:2\r\ncv1.X-ABLabel:D\r\n"))
    }

    @Test
    fun escaping() {
        val out = write(Contact("u", note = "a\\b;c,d\ne\r\nf\rg"))
        assertTrue(out, out.contains("NOTE:a\\\\b\\;c\\,d\\ne\\nf\\ng\r\n"))
    }

    @Test
    fun cardsAreSortedByUidOrdinal() {
        val out = write(Contact("b"), Contact("B"), Contact("a"))
        val uids = Regex("UID:(.*)\r\n").findAll(out).map { it.groupValues[1] }.toList()
        assertEquals(listOf("B", "a", "b"), uids)
    }

    @Test
    fun outputIsDeterministic() {
        val list = listOf(Contact("2", formattedName = "x"), Contact("1", formattedName = "y"))
        assertArrayEquals(VCard.serialize(list), VCard.serialize(list.reversed()))
    }

    @Test
    fun foldingAt75OctetsNeverSplitsUtf8() {
        for (unit in listOf("a", "é", "日", "😀")) {
            for (prefix in 0..4) {
                val note = "x".repeat(prefix) + unit.repeat(100)
                val bytes = VCard.serialize(listOf(Contact("u", note = note)))
                val text = String(bytes, Charsets.UTF_8)
                val physical = text.split("\r\n").dropLast(1)
                for (line in physical) assertTrue("line too long: ${line.toByteArray().size}", line.toByteArray(Charsets.UTF_8).size <= 75)
                assertTrue(physical.drop(1).any { it.startsWith(" ") })
                // Unfolding restores the value. UTF-8 decoding would give U+FFFD if a sequence was split.
                assertTrue(!text.contains('\uFFFD'))
                assertEquals(note, VCard.parse(bytes).contacts.single().note)
            }
        }
    }

    @Test
    fun firstLineIsExactly75OctetsWhenValueIsLong() {
        val bytes = VCard.serialize(listOf(Contact("u", note = "y".repeat(200))))
        val lines = String(bytes, Charsets.UTF_8).split("\r\n")
        val noteLine = lines.indexOfFirst { it.startsWith("NOTE:") }
        assertEquals(75, lines[noteLine].length)
        assertEquals(75, lines[noteLine + 1].length)
    }

    @Test
    fun roundTripKeepsAllFields() {
        val c = Contact(
            uid = "u", rev = 1709634030000L, formattedName = "Zoë; \"Z\"", name = StructuredName("Z", "Zoë", "M", "Dr", "Jr"),
            nickname = "z, zz", organization = Organization("A;B", "C,D"), title = "T", birthday = "--02-03",
            phones = listOf(TypedValue(Kind.FAX_OTHER, "1"), TypedValue(Kind.CUSTOM, "2", "Sat; phone")),
            emails = listOf(TypedValue(Kind.WORK, "e@x")),
            addresses = listOf(PostalAddress(Kind.HOME, poBox = "1", extended = "2", street = "3", locality = "4", region = "5", postalCode = "6", country = "7")),
            urls = listOf("https://a/b?c=1,2;3"), note = "l1\nl2", extras = listOf("X-A:1", "g.X-B:2"),
        )
        assertEquals(c, VCard.parse(VCard.serialize(listOf(c))).contacts.single())
    }
}
