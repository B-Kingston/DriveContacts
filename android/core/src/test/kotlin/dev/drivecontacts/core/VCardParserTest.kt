package dev.drivecontacts.core

import dev.drivecontacts.core.model.Contact
import dev.drivecontacts.core.model.Kind
import dev.drivecontacts.core.model.Organization
import dev.drivecontacts.core.model.StructuredName
import dev.drivecontacts.core.model.TypedValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class VCardParserTest {
    private fun parse(s: String) = VCard.parse(s.toByteArray(Charsets.UTF_8))
    private fun one(s: String): Contact = parse(s).contacts.single()
    private fun card(vararg lines: String) = ("BEGIN:VCARD\r\nVERSION:3.0\r\n" + lines.joinToString("") { it + "\r\n" } + "END:VCARD\r\n")

    private val DERIVED_UID = Regex("urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    @Test
    fun missingUidIsDerivedFromTheCard() {
        val a = one(card("FN:A"))
        assertTrue(a.uid.matches(DERIVED_UID))
        assertEquals(a.uid, one(card("FN:A")).uid)
        assertNotEquals(a.uid, one(card("FN:B")).uid)
    }

    @Test
    fun identicalCardsWithoutUidGetDistinctUidsThatStayTheSame() {
        val file = card("FN:A") + card("FN:A") + card("FN:A")
        val first = parse(file).contacts.map { it.uid }
        assertEquals(3, first.toSet().size)
        assertEquals(first, parse(file).contacts.map { it.uid })
        assertEquals(one(card("FN:A")).uid, first[0])
    }

    @Test
    fun duplicateUidKeepsFirstAndRenamesLater() {
        val file = card("UID:x", "FN:A") + card("UID:x", "FN:B") + card("UID:x", "FN:C") + card("UID:x", "FN:C")
        val r = parse(file)
        assertEquals("x", r.contacts[0].uid)
        assertEquals(4, r.contacts.map { it.uid }.toSet().size)
        assertTrue(r.contacts.drop(1).all { it.uid.matches(DERIVED_UID) })
        assertEquals(listOf("A", "B", "C", "C"), r.contacts.map { it.formattedName })
        assertEquals(r.contacts.map { it.uid }, parse(file).contacts.map { it.uid })
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
    fun cardWithOnlyUidRoundTrips() {
        // The mirror base and local.vcf go through serialize/parse. A contact without content must not vanish there.
        val empty = Contact(uid = "urn:uuid:only-uid", rev = 1709634030000L)
        assertEquals(listOf(empty), VCard.parse(VCard.serialize(listOf(empty))).contacts)
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

    private fun quotedPrintable(text: String) =
        text.toByteArray(Charsets.UTF_8).joinToString("") { b ->
            val v = b.toInt() and 0xFF
            if (v in 32..126 && v != '='.code) v.toChar().toString() else "=%02X".format(v)
        }

    @Test
    fun quotedPrintableExtraIsDecodedAndKeepsItsBytesWhenFolded() {
        // The writer folds at 75 octets. A fold after "=" must not look like a soft break.
        val text = "vnd.android.cursor.item/nickname;" + "郎世寧".repeat(8) + ";1"
        for (prefix in 0..40) {
            val value = "a".repeat(prefix) + text
            val c = one(card("UID:u", "FN:A", "X-ANDROID-CUSTOM;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:" + quotedPrintable(value)))
            assertEquals(listOf("X-ANDROID-CUSTOM:$value"), c.extras)
            val bytes = VCard.serialize(listOf(c))
            val back = VCard.parse(bytes).contacts.single()
            assertEquals("prefix $prefix", c, back)
            assertArrayEquals("prefix $prefix", bytes, VCard.serialize(listOf(back)))
        }
    }

    @Test
    fun quotedPrintableExtraWithNewLineKeepsItAsEscape() {
        val c = one(card("UID:u", "FN:A", "X-FOO;QUOTED-PRINTABLE;CHARSET=ISO-8859-1:a=0D=0Ab=E9"))
        assertEquals(listOf("X-FOO:a\\nbé"), c.extras)
    }

    @Test
    fun charsetParameterIsNotKeptInExtras() {
        val bytes = "BEGIN:VCARD\nUID:u\nFN:A\nX-FOO;CHARSET=ISO-8859-1;TYPE=x:café\nEND:VCARD\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals(listOf("X-FOO;TYPE=x:café"), VCard.parse(bytes).contacts.single().extras)
    }

    @Test
    fun veryLongFoldedLineWithoutColonParses() {
        val folds = " x".repeat(20_000).replace(" ", "\n ")
        val r = parse("BEGIN:VCARD\nUID:u\nFN:A\njunk$folds\nNOTE:n$folds\nEND:VCARD\n")
        val c = r.contacts.single()
        assertEquals("A", c.formattedName)
        assertEquals("n" + "x".repeat(20_000), c.note)
        assertTrue(c.extras.isEmpty())
    }

    @Test
    fun veryLongQuotedPrintableValueParses() {
        val folded = "=\n" + "ab".repeat(10)
        val r = parse("BEGIN:VCARD\nUID:u\nNOTE;ENCODING=QUOTED-PRINTABLE:" + folded.repeat(5_000) + "\nEND:VCARD\n")
        assertEquals("ab".repeat(10).repeat(5_000), r.contacts.single().note)
    }

    @Test
    fun quotedPrintableSoftBreakDoesNotSwallowCardMarkers() {
        val tail = "UID:b\nFN:B\nEND:VCARD\n"
        val head = "BEGIN:VCARD\nUID:a\nNOTE;ENCODING=QUOTED-PRINTABLE:abc=\n"
        val files = listOf("END:VCARD", "end:vcard").map { "$head$it\nBEGIN:VCARD\n$tail" } +
            listOf("BEGIN:VCARD", "begin:vcard").map { "$head$it\n$tail" }
        for (s in files) {
            val r = parse(s)
            assertEquals(s, listOf("a", "b"), r.contacts.map { it.uid })
            assertEquals(s, "abc", r.contacts[0].note)
        }
    }

    @Test
    fun quotedPrintableSoftBreakDoesNotRunIntoEmptyLine() {
        val c = one("BEGIN:VCARD\nUID:a\nNOTE;ENCODING=QUOTED-PRINTABLE:abc=\n\n def\nEND:VCARD\n")
        assertEquals("abc", c.note)
    }

    @Test
    fun nestedCardAfterAgentIsDroppedAndOuterCardContinues() {
        val s = "BEGIN:VCARD\nVERSION:2.1\nUID:outer\nFN:Outer\nAGENT:\nBEGIN:VCARD\nVERSION:2.1\nUID:inner\nFN:Inner\nTEL:999\nBEGIN:VCARD\nTEL:888\nEND:VCARD\nEND:VCARD\nTEL:111\nEND:VCARD\n"
        val r = parse(s)
        val c = r.contacts.single()
        assertEquals("outer", c.uid)
        assertEquals("Outer", c.formattedName)
        assertEquals(listOf("111"), c.phones.map { it.value })
        assertTrue(c.extras.isEmpty())
    }

    @Test
    fun cardAfterNestedCardIsStillRead() {
        val r = parse("BEGIN:VCARD\nUID:a\nFN:A\nAGENT:\nBEGIN:VCARD\nFN:Inner\nEND:VCARD\nEND:VCARD\nBEGIN:VCARD\nUID:b\nFN:B\nEND:VCARD\n")
        assertEquals(listOf("A", "B"), r.contacts.map { it.formattedName })
    }

    @Test
    fun utf16WithByteOrderMarkIsDecoded() {
        val s = "BEGIN:VCARD\r\nUID:u\r\nFN:Jürgen 郎\r\nEND:VCARD\r\n"
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bom = if (charset == Charsets.UTF_16LE) byteArrayOf(0xFF.toByte(), 0xFE.toByte()) else byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            assertEquals("Jürgen 郎", VCard.parse(bom + s.toByteArray(charset)).contacts.single().formattedName)
        }
    }

    @Test
    fun utf8ByteOrderMarkIsSkipped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "BEGIN:VCARD\nUID:u\nFN:Jürgen\nEND:VCARD\n".toByteArray(Charsets.UTF_8)
        assertEquals("Jürgen", VCard.parse(bytes).contacts.single().formattedName)
    }

    @Test
    fun charsetParameterAppliesToPlainValues() {
        fun fn(charsetName: String, text: String, charset: Charset): String {
            val bytes = "BEGIN:VCARD\nUID:u\nFN;CHARSET=$charsetName:$text\nEND:VCARD\n".toByteArray(charset)
            return VCard.parse(bytes).contacts.single().formattedName
        }
        assertEquals("Jürgen", fn("ISO-8859-1", "Jürgen", Charsets.ISO_8859_1))
        assertEquals("田中", fn("Shift_JIS", "田中", Charset.forName("Shift_JIS")))
    }

    @Test
    fun unknownCharsetFallsBackToUtf8() {
        val bytes = "BEGIN:VCARD\nUID:u\nFN;CHARSET=NO-SUCH-SET:Jürgen\nEND:VCARD\n".toByteArray(Charsets.UTF_8)
        assertEquals("Jürgen", VCard.parse(bytes).contacts.single().formattedName)
    }

    @Test
    fun quotedPrintableEscapesUseTheCharsetAndLiteralBytesStay() {
        val bytes = "BEGIN:VCARD\nUID:u\nFN;ENCODING=QUOTED-PRINTABLE;CHARSET=ISO-8859-1:J=FCrgen é\nEND:VCARD\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Jürgen é", VCard.parse(bytes).contacts.single().formattedName)
    }

    @Test
    fun largeBinaryValuesAreDroppedAndCounted() {
        val base64 = "AAAA".repeat(50_000).chunked(74).joinToString("\n ")
        val r = parse("BEGIN:VCARD\nUID:u\nFN:A\nPHOTO;ENCODING=b;TYPE=JPEG:$base64\nPHOTO;VALUE=uri:data:image/png;base64,$base64\nTEL:1\nEND:VCARD\n")
        assertEquals(2, r.droppedProperties)
        val c = r.contacts.single()
        assertEquals("1", c.phones.single().value)
        assertTrue(c.extras.isEmpty())
    }

    @Test
    fun photoUriLongerThanTheProbeIsKept() {
        val url = "https://example.com/" + "a".repeat(200) + ".jpg"
        val r = parse(card("UID:u", "FN:A", "PHOTO;VALUE=uri:$url"))
        assertEquals(0, r.droppedProperties)
        assertEquals(listOf("PHOTO;VALUE=uri:$url"), r.contacts.single().extras)
    }

    // The fold of the first app version: 75 octets per line, a continuation starts with one space.
    private fun oldWriterFold(text: String): String {
        val sb = StringBuilder()
        var octets = 0
        for (ch in text) {
            if (octets + 1 > 75) {
                sb.append("\r\n ")
                octets = 1
            }
            sb.append(ch)
            octets++
        }
        return sb.toString()
    }

    @Test
    fun oldAppFileWithQuotedPrintableExtraFoldedAfterEqualsIsRecovered() {
        val text = "vnd.android.cursor.item/nickname;" + "郎世寧".repeat(8) + ";1"
        var foldsAfterEquals = 0
        for (prefix in 0..40) {
            val value = "a".repeat(prefix) + text
            val line = oldWriterFold("X-ANDROID-CUSTOM;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:" + quotedPrintable(value))
            if (line.contains("=\r\n ")) foldsAfterEquals++
            val c = one("BEGIN:VCARD\r\nVERSION:4.0\r\nUID:u\r\nFN:A\r\n$line\r\nEND:VCARD\r\n")
            assertEquals("prefix $prefix", listOf("X-ANDROID-CUSTOM:$value"), c.extras)
        }
        assertTrue(foldsAfterEquals > 0)
    }

    @Test
    fun version4LineEndingInEqualsIsNotASoftBreak() {
        // A fold, not a soft break: the space goes, the `=` stays. `=zz` is not an escape, so it stays literal.
        val c = one("BEGIN:VCARD\nVERSION:4.0\nUID:u\nNOTE;ENCODING=QUOTED-PRINTABLE:abc=\n zz\nEND:VCARD\n")
        assertEquals("abc=zz", c.note)
    }

    @Test
    fun versionIsTheLastOneSeenSoFarInTheCard() {
        // VERSION after the line: the line is read as 2.1, so the soft break joins.
        val late = one("BEGIN:VCARD\nUID:u\nNOTE;ENCODING=QUOTED-PRINTABLE:abc=\n def\nVERSION:4.0\nEND:VCARD\n")
        assertEquals("abc def", late.note)
        // A new card starts without a version.
        val r = parse("BEGIN:VCARD\nVERSION:4.0\nUID:a\nEND:VCARD\nBEGIN:VCARD\nUID:b\nNOTE;ENCODING=QUOTED-PRINTABLE:abc=\n def\nEND:VCARD\n")
        assertEquals("abc def", r.contacts[1].note)
    }

    @Test
    fun charsetIsIgnoredInVersion4Cards() {
        val v4 = "BEGIN:VCARD\nVERSION:4.0\nUID:u\nFN:A\nX-FOO;CHARSET=ISO-8859-1:café\nNOTE;CHARSET=ISO-8859-1:café\nEND:VCARD\n"
        val c = VCard.parse(v4.toByteArray(Charsets.UTF_8)).contacts.single()
        assertEquals(listOf("X-FOO:café"), c.extras)
        assertEquals("café", c.note)
        // Before a VERSION line the card is not 4.0 yet.
        val v3 = "BEGIN:VCARD\nUID:u\nNOTE;CHARSET=ISO-8859-1:café\nVERSION:4.0\nEND:VCARD\n"
        assertEquals("cafÃ©", VCard.parse(v3.toByteArray(Charsets.UTF_8)).contacts.single().note)
    }

    @Test
    fun quotedPrintableKeepsItsCharsetInVersion4Cards() {
        val bytes = "BEGIN:VCARD\nVERSION:4.0\nUID:u\nNOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=ISO-8859-1:caf=E9\nEND:VCARD\n".toByteArray(Charsets.UTF_8)
        assertEquals("café", VCard.parse(bytes).contacts.single().note)
    }

    @Test
    fun derivedUidDoesNotDependOnHowBinaryLinesAreFolded() {
        val base64 = "QUJD".repeat(100)
        fun uid(photo: String) = one("BEGIN:VCARD\nVERSION:3.0\nFN:A\n$photo\nTEL:1\nEND:VCARD\n").uid
        val flat = uid("PHOTO;ENCODING=b;TYPE=JPEG:$base64")
        assertEquals(flat, uid("PHOTO;ENCODING=b;TYPE=JPEG:" + base64.chunked(40).joinToString("\n ")))
        assertEquals(flat, uid("PHOTO;ENCODING=b;TYPE=JPEG:" + base64.chunked(76).joinToString("\r\n ")))
        assertEquals(flat, uid("PHOTO;ENCODING=b;TYPE=JPEG:" + "WFla".repeat(50)))
        assertNotEquals(flat, uid("PHOTO;ENCODING=b;TYPE=PNG:$base64"))
    }

    @Test
    fun derivedUidIsTheSameForAnyLineEndingAndFolding() {
        val a = one("BEGIN:VCARD\r\nFN:Long\r\n Name\r\nTEL:1\r\nEND:VCARD\r\n").uid
        assertEquals(a, one("BEGIN:VCARD\nFN:LongName\nTEL:1\nEND:VCARD\n").uid)
        assertEquals(a, one("BEGIN:VCARD\rFN:Long\r\tName\rTEL:1\rEND:VCARD\r").uid)
    }

    @Test
    fun agentOnTheSameLineAsNestedBeginIsDropped() {
        val s = "BEGIN:VCARD\nVERSION:2.1\nUID:outer\nFN:Outer\nAGENT:BEGIN:VCARD\nVERSION:2.1\nUID:inner\nFN:Inner\nTEL:999\nEND:VCARD\nTEL:111\nEND:VCARD\n"
        val c = parse(s).contacts.single()
        assertEquals("outer", c.uid)
        assertEquals("Outer", c.formattedName)
        assertEquals(listOf("111"), c.phones.map { it.value })
        assertTrue(c.extras.isEmpty())
    }

    @Test
    fun agentWithAnotherValueStaysAnExtra() {
        val c = one("BEGIN:VCARD\nUID:u\nFN:A\nAGENT;VALUE=uri:cid:agent@example.com\nTEL:1\nEND:VCARD\n")
        assertEquals(listOf("AGENT;VALUE=uri:cid:agent@example.com"), c.extras)
        assertEquals(listOf("1"), c.phones.map { it.value })
    }
}
