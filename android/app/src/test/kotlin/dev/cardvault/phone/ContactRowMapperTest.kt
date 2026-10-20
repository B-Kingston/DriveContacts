package dev.cardvault.phone

import dev.cardvault.core.model.Contact
import dev.cardvault.core.model.Kind
import dev.cardvault.core.model.Organization
import dev.cardvault.core.model.PostalAddress
import dev.cardvault.core.model.StructuredName
import dev.cardvault.core.model.TypedValue
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
}
