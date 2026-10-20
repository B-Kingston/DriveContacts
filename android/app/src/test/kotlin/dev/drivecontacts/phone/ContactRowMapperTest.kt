package dev.drivecontacts.phone

import dev.drivecontacts.core.model.Contact
import dev.drivecontacts.core.model.Kind
import dev.drivecontacts.core.model.Organization
import dev.drivecontacts.core.model.PostalAddress
import dev.drivecontacts.core.model.StructuredName
import dev.drivecontacts.core.model.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactRowMapperTest {
    private val full = Contact(
        uid = "urn:uuid:x",
        formattedName = "Dr. Ann B. Lee",
        name = StructuredName(family = "Lee", given = "Ann", additional = "B.", prefix = "Dr.", suffix = ""),
        nickname = "Annie",
        organization = Organization("Acme", "R&D"),
        title = "Chief",
        birthday = "1980-02-03",
        phones = listOf(
            TypedValue(Kind.MOBILE, "+1 555 0100"),
            TypedValue(Kind.FAX_OTHER, "+1 555 0101"),
            TypedValue(Kind.CUSTOM, "+1 555 0102", "Boat"),
        ),
        emails = listOf(TypedValue(Kind.WORK, "ann@example.com"), TypedValue(Kind.MOBILE, "a@m.example")),
        addresses = listOf(PostalAddress(Kind.HOME, street = "1 Main St", locality = "Town", extended = "Apt 2", country = "NL")),
        urls = listOf("https://example.com"),
        note = "Hello",
    )

    @Test
    fun rowsRoundTripToTheSameContent() {
        assertEquals(full, ContactRowMapper.toContact(full.uid, full.rev, ContactRowMapper.toRows(full)))
    }

    @Test
    fun extrasAreNotKeptByThePhoneStore() {
        val withExtras = full.copy(extras = listOf("X-FOO:bar"))
        assertEquals(full, ContactRowMapper.normalize(withExtras))
    }

    @Test
    fun emptyPartsGiveNoRows() {
        assertEquals(emptyList<DataRow>(), ContactRowMapper.toRows(Contact(uid = "u")))
    }

    @Test
    fun mappedRowsLeaveUnmappedRowsAndSecondSingleRows() {
        val rows = ContactRowMapper.toRows(full).mapIndexed { i, r -> DataRow(r.mimeType, r.values, id = i + 1L) } +
            listOf(
                DataRow("vnd.android.cursor.item/im", mapOf("data1" to "ann"), id = 100L),
                DataRow("vnd.android.cursor.item/nickname", mapOf("data1" to "Second"), id = 101L),
            )
        val mapped = ContactRowMapper.mappedRows(rows).map { it.id }
        assertEquals(false, 100L in mapped)
        assertEquals(false, 101L in mapped)
        assertEquals((1L..rows.size - 2L).toSet(), mapped.toSet())
    }
}
