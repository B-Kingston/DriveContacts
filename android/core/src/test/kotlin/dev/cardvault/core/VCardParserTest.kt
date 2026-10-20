package dev.cardvault.core

import dev.cardvault.core.model.Contact
import dev.cardvault.core.model.Kind
import dev.cardvault.core.model.Organization
import dev.cardvault.core.model.StructuredName
import dev.cardvault.core.model.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardParserTest {
    private fun parse(s: String) = VCard.parse(s.toByteArray(Charsets.UTF_8))
    private fun one(s: String): Contact = parse(s).contacts.single()
    private fun card(vararg lines: String) = ("BEGIN:VCARD\r\nVERSION:3.0\r\n" + lines.joinToString("") { it + "\r\n" } + "END:VCARD\r\n")

    @Test
    fun missingUidGetsRandomUrnUuid() {
        val a = one(card("FN:A"))
        val b = one(card("FN:A"))
        assertTrue(a.uid.matches(Regex("urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        assertNotEquals(a.uid, b.uid)
    }

    @Test
    fun duplicateUidKeepsFirstAndRenamesLater() {
        val r = parse(card("UID:x", "FN:A") + card("UID:x", "FN:B") + card("UID:x", "FN:C"))
        assertEquals("x", r.contacts[0].uid)
        assertEquals(3, r.contacts.map { it.uid }.toSet().size)
        assertEquals(listOf("A", "B", "C"), r.contacts.map { it.formattedName })
    }

    @Test
    fun fnDerivationOrder() {
        assertEquals("Dr. A B C Jr", one(card("N:C;A;B;Dr.;Jr")).formattedName)
        assertEquals("Ada Lovelace", one(card("N:Lovelace;Ada;;;")).formattedName)
        assertEquals("Org", one(card("ORG:Org;Dept", "EMAIL:e@x")).formattedName)
        assertEquals("e@x", one(card("EMAIL:e@x", "TEL:1")).formattedName)
        assertEquals("123", one(card("TEL:123")).formattedName)
        assertEquals("", one(card("NOTE:only a note")).formattedName)
    }

    @Test
    fun explicitFnWinsOverDerivation() {
        assertEquals("Nick", one(card("FN:Nick", "N:Lovelace;Ada;;;")).formattedName)
    }

    @Test
    fun droppedBinaryPropertiesAreCounted() {
        val r = parse(
            card(
                "FN:A",
                "PHOTO;ENCODING=b;TYPE=JPEG:AAAA",
                "LOGO;VALUE=uri:data:image/png;base64,AAAA",
                "SOUND;BASE64:AAAA",
                "KEY;ENCODING=b:AAAA",
                "PHOTO;VALUE=uri:https://example.com/a.jpg",
            ),
        )
        assertEquals(4, r.droppedProperties)
        assertEquals(listOf("PHOTO;VALUE=uri:https://example.com/a.jpg"), r.contacts.single().extras)
    }

    @Test
    fun quotedPrintableSoftBreakAndCharset() {
        val s = "BEGIN:VCARD\nVERSION:2.1\nUID:u\nFN;ENCODING=QUOTED-PRINTABLE;CHARSET=ISO-8859-1:J=FCrgen =\nM=FCller\nEND:VCARD\n"
        assertEquals("Jürgen Müller", one(s).formattedName)
    }

    @Test
    fun bareQuotedPrintableParameter() {
        val s = "BEGIN:VCARD\nVERSION:2.1\nUID:u\nNOTE;QUOTED-PRINTABLE:a=3Db=0D=0Ac\nEND:VCARD\n"
        assertEquals("a=b\nc", one(s).note)
    }

    @Test
    fun foldedLinesAndAllLineEndings() {
        for (nl in listOf("\r\n", "\n", "\r")) {
            val s = listOf("BEGIN:VCARD", "UID:u", "FN:Long", " Name", "\tHere", "END:VCARD").joinToString(nl) + nl
            assertEquals("LongNameHere", one(s).formattedName)
        }
    }

    @Test
    fun quotedParameterWithSpecialCharacters() {
        val c = one(card("UID:u", "FN:A", "TEL;X-NOTE=\"a:b;c,d\";TYPE=work:555"))
        assertEquals(listOf(TypedValue(Kind.WORK, "555")), c.phones)
    }

    @Test
    fun bareTypesAndPref() {
        val c = one(card("UID:u", "FN:A", "TEL;CELL;PREF:1", "TEL;PREF:2", "TEL;VOICE:3", "TEL;FAX:4", "TEL;WORK;FAX:5", "TEL;HOME;FAX:6", "TEL;PAGER:7", "TEL;TYPE=pref,home:8", "TEL;PREF=1:9"))
        assertEquals(
            listOf(Kind.MOBILE, Kind.MAIN, Kind.OTHER, Kind.FAX_WORK, Kind.FAX_WORK, Kind.FAX_HOME, Kind.PAGER, Kind.HOME, Kind.OTHER),
            c.phones.map { it.kind },
        )
    }

    @Test
    fun appleLabelsMapToKindsAndUnknownLabelsAreCustom() {
        val c = one(
            card(
                "UID:u", "FN:A",
                "g1.TEL:1", "g1.X-ABLabel:_\$!<Mobile>!\$_",
                "g2.TEL:2", "g2.X-ABLabel:_\$!<OtherFAX>!\$_",
                "g3.TEL:3", "g3.X-ABLabel:_\$!<Anniversary>!\$_",
                "g4.TEL:4", "g4.X-ABLabel:Boat",
                "TEL;TYPE=X-Plane:5",
            ),
        )
        assertEquals(
            listOf(
                TypedValue(Kind.MOBILE, "1"), TypedValue(Kind.FAX_OTHER, "2"),
                TypedValue(Kind.CUSTOM, "3", "Anniversary"), TypedValue(Kind.CUSTOM, "4", "Boat"), TypedValue(Kind.CUSTOM, "5", "Plane"),
            ),
            c.phones,
        )
        assertTrue(c.extras.isEmpty())
    }

    @Test
    fun orphanLabelStaysAsExtra() {
        val c = one(card("UID:u", "FN:A", "g1.X-ABDATE:2000-01-01", "g1.X-ABLabel:Day"))
        assertEquals(listOf("g1.X-ABDATE:2000-01-01", "g1.X-ABLabel:Day"), c.extras)
    }

    @Test
    fun telUriLosesPrefix() {
        val c = one(card("UID:u", "FN:A", "TEL;VALUE=uri:tel:+1555", "TEL:TEL:+1666"))
        assertEquals(listOf("+1555", "+1666"), c.phones.map { it.value })
    }

    @Test
    fun nicknameJoinAndOrgComponents() {
        val c = one(card("UID:u", "FN:A", "NICKNAME:a,b\\,c,d", "ORG:Org;Dept;Team;Extra"))
        assertEquals("a, b,c, d", c.nickname)
        assertEquals(Organization("Org", "Dept"), c.organization)
    }

    @Test
    fun birthdayNormalization() {
        fun bday(v: String) = one(card("UID:u", "FN:A", "BDAY:$v")).birthday
        assertEquals("1980-02-03", bday("19800203"))
        assertEquals("--02-03", bday("--0203"))
        assertEquals("1980-02-03", bday("1980-02-03"))
        assertEquals("--02-03", bday("--02-03"))
        assertEquals("1980-02-03", bday("19800203T101010Z"))
        assertEquals("1980-02-03", bday("1980-02-03T10:10:10"))
        assertEquals("circa 1980", bday("circa 1980"))
    }

    @Test
    fun revFormats() {
        fun rev(v: String) = one(card("UID:u", "FN:A", "REV:$v")).rev
        val expected = 1709634030000L // 2024-03-05T10:20:30Z
        assertEquals(expected, rev("20240305T102030Z"))
        assertEquals(expected, rev("2024-03-05T10:20:30Z"))
        assertEquals(expected, rev("2024-03-05T10:20:30.000Z"))
        assertEquals(expected, rev("2024-03-05T11:20:30+01:00"))
        assertEquals(0L, rev("garbage"))
    }

    @Test
    fun missingEndAtEndOfFileIsStillRead() {
        val r = parse("BEGIN:VCARD\r\nUID:u\r\nFN:A\r\nTEL:1")
        assertEquals(1, r.contacts.size)
        assertEquals("1", r.contacts[0].phones.single().value)
    }

    @Test
    fun beginWithoutEndStartsNewCard() {
        val r = parse("BEGIN:VCARD\nUID:1\nFN:A\nBEGIN:VCARD\nUID:2\nFN:B\nEND:VCARD\n")
        assertEquals(listOf("A", "B"), r.contacts.map { it.formattedName })
    }

    @Test
    fun emptyCardsAndGarbageGiveNoContacts() {
        val r = parse("junk\nBEGIN:VCARD\nVERSION:3.0\nEND:VCARD\nmore junk\n")
        assertTrue(r.contacts.isEmpty())
        assertTrue(parse("").contacts.isEmpty())
    }

    @Test
    fun repeatedSingleValueFieldsKeepTheSecondAsExtra() {
        val c = one(card("UID:u", "FN:A", "TITLE:One", "TITLE:Two"))
        assertEquals("One", c.title)
        assertEquals(listOf("TITLE:Two"), c.extras)
    }

    @Test
    fun structuredNameAndAddressPaddedWhenShort() {
        val c = one(card("UID:u", "FN:A", "N:Doe;John", "ADR:;;Street"))
        assertEquals(StructuredName(family = "Doe", given = "John"), c.name)
        assertEquals("Street", c.addresses.single().street)
        assertEquals(Kind.OTHER, c.addresses.single().kind)
    }
}
