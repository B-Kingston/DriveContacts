package dev.cardvault.phone

import android.content.ContentResolver
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization as OrgRow
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName as NameRow
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import dev.cardvault.core.model.Contact
import dev.cardvault.core.model.Kind
import dev.cardvault.core.model.Organization
import dev.cardvault.core.model.PostalAddress
import dev.cardvault.core.model.StructuredName
import dev.cardvault.core.model.TypedValue

/** One `ContactsContract.Data` row: the MIME type and the `data1`..`data10` columns by column name. */
class DataRow(val mimeType: String, val values: Map<String, String?>)

/** Maps data rows to a core [Contact] and back (docs/spec.md section 6). Used by the mirror and by the import. */
object ContactRowMapper {
    /** The columns that [readRows] reads. */
    val DATA_COLUMNS: List<String> = listOf(
        Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5,
        Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10,
    )

    private const val MAX_IDS_PER_QUERY = 400

    /** Builds a contact from the rows of one raw contact. The result has no extras. */
    fun toContact(uid: String, rev: Long, rows: List<DataRow>): Contact {
        fun rowsOf(mime: String) = rows.filter { it.mimeType == mime }
        fun DataRow.text(column: String) = values[column].orEmpty()
        fun DataRow.number(column: String) = values[column]?.toIntOrNull()

        val nameRow = rowsOf(NameRow.CONTENT_ITEM_TYPE).firstOrNull()
        val name = StructuredName(
            family = nameRow?.text(NameRow.FAMILY_NAME).orEmpty(),
            given = nameRow?.text(NameRow.GIVEN_NAME).orEmpty(),
            additional = nameRow?.text(NameRow.MIDDLE_NAME).orEmpty(),
            prefix = nameRow?.text(NameRow.PREFIX).orEmpty(),
            suffix = nameRow?.text(NameRow.SUFFIX).orEmpty(),
        )
        val orgRow = rowsOf(OrgRow.CONTENT_ITEM_TYPE).firstOrNull()
        val organization = Organization(
            name = orgRow?.text(OrgRow.COMPANY).orEmpty(),
            department = orgRow?.text(OrgRow.DEPARTMENT).orEmpty(),
        )
        val phones = rowsOf(Phone.CONTENT_ITEM_TYPE).mapNotNull { row ->
            val value = row.text(Phone.NUMBER)
            if (value.isEmpty()) return@mapNotNull null
            val (kind, label) = phoneKind(row.number(Phone.TYPE), row.text(Phone.LABEL))
            TypedValue(kind = kind, value = value, customLabel = label)
        }
        val emails = rowsOf(Email.CONTENT_ITEM_TYPE).mapNotNull { row ->
            val value = row.text(Email.ADDRESS)
            if (value.isEmpty()) return@mapNotNull null
            val (kind, label) = emailKind(row.number(Email.TYPE), row.text(Email.LABEL))
            TypedValue(kind = kind, value = value, customLabel = label)
        }
        val addresses = rowsOf(StructuredPostal.CONTENT_ITEM_TYPE).mapNotNull { row ->
            val (kind, label) = addressKind(row.number(StructuredPostal.TYPE), row.text(StructuredPostal.LABEL))
            val address = PostalAddress(
                kind = kind,
                customLabel = label,
                poBox = row.text(StructuredPostal.POBOX),
                extended = row.text(StructuredPostal.NEIGHBORHOOD),
                street = row.text(StructuredPostal.STREET),
                locality = row.text(StructuredPostal.CITY),
                region = row.text(StructuredPostal.REGION),
                postalCode = row.text(StructuredPostal.POSTCODE),
                country = row.text(StructuredPostal.COUNTRY),
            )
            address.takeIf { a ->
                listOf(a.poBox, a.extended, a.street, a.locality, a.region, a.postalCode, a.country).any { it.isNotEmpty() }
            }
        }
        val urls = rowsOf(Website.CONTENT_ITEM_TYPE).map { it.text(Website.URL) }.filter { it.isNotEmpty() }
        val birthday = rowsOf(Event.CONTENT_ITEM_TYPE)
            .firstOrNull { it.number(Event.TYPE) == Event.TYPE_BIRTHDAY && it.text(Event.START_DATE).isNotEmpty() }
            ?.let { normalizeDate(it.text(Event.START_DATE)) }
            .orEmpty()
        val formatted = nameRow?.text(NameRow.DISPLAY_NAME).orEmpty().ifEmpty {
            listOf(name.prefix, name.given, name.additional, name.family, name.suffix)
                .filter { it.isNotEmpty() }.joinToString(" ")
                .ifEmpty { organization.name }
                .ifEmpty { emails.firstOrNull()?.value.orEmpty() }
                .ifEmpty { phones.firstOrNull()?.value.orEmpty() }
        }
        return Contact(
            uid = uid,
            rev = rev,
            formattedName = formatted,
            name = name,
            nickname = rowsOf(Nickname.CONTENT_ITEM_TYPE).firstOrNull()?.text(Nickname.NAME).orEmpty(),
            organization = organization,
            title = orgRow?.text(OrgRow.TITLE).orEmpty(),
            birthday = birthday,
            phones = phones,
            emails = emails,
            addresses = addresses,
            urls = urls,
            note = rowsOf(Note.CONTENT_ITEM_TYPE).firstOrNull()?.text(Note.NOTE).orEmpty(),
        )
    }

    /** The data rows for [contact]. Empty parts give no row. */
    fun toRows(contact: Contact): List<DataRow> {
        val rows = ArrayList<DataRow>()
        val n = contact.name
        if (contact.formattedName.isNotEmpty() || !n.isEmpty) {
            rows += DataRow(
                NameRow.CONTENT_ITEM_TYPE,
                mapOf(
                    NameRow.DISPLAY_NAME to contact.formattedName,
                    NameRow.FAMILY_NAME to n.family,
                    NameRow.GIVEN_NAME to n.given,
                    NameRow.MIDDLE_NAME to n.additional,
                    NameRow.PREFIX to n.prefix,
                    NameRow.SUFFIX to n.suffix,
                ),
            )
        }
        if (contact.nickname.isNotEmpty()) {
            rows += DataRow(Nickname.CONTENT_ITEM_TYPE, mapOf(Nickname.NAME to contact.nickname, Nickname.TYPE to Nickname.TYPE_DEFAULT.toString()))
        }
        if (!contact.organization.isEmpty || contact.title.isNotEmpty()) {
            rows += DataRow(
                OrgRow.CONTENT_ITEM_TYPE,
                mapOf(
                    OrgRow.COMPANY to contact.organization.name,
                    OrgRow.DEPARTMENT to contact.organization.department,
                    OrgRow.TITLE to contact.title,
                    OrgRow.TYPE to OrgRow.TYPE_WORK.toString(),
                ),
            )
        }
        for (p in contact.phones) {
            val (type, label) = phoneType(p.kind, p.customLabel)
            rows += DataRow(Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to p.value, Phone.TYPE to type.toString(), Phone.LABEL to label))
        }
        for (e in contact.emails) {
            val (type, label) = emailType(e.kind, e.customLabel)
            rows += DataRow(Email.CONTENT_ITEM_TYPE, mapOf(Email.ADDRESS to e.value, Email.TYPE to type.toString(), Email.LABEL to label))
        }
        for (a in contact.addresses) {
            val (type, label) = addressType(a.kind, a.customLabel)
            rows += DataRow(
                StructuredPostal.CONTENT_ITEM_TYPE,
                mapOf(
                    StructuredPostal.POBOX to a.poBox,
                    StructuredPostal.NEIGHBORHOOD to a.extended,
                    StructuredPostal.STREET to a.street,
                    StructuredPostal.CITY to a.locality,
                    StructuredPostal.REGION to a.region,
                    StructuredPostal.POSTCODE to a.postalCode,
                    StructuredPostal.COUNTRY to a.country,
                    StructuredPostal.TYPE to type.toString(),
                    StructuredPostal.LABEL to label,
                ),
            )
        }
        for (url in contact.urls) {
            rows += DataRow(Website.CONTENT_ITEM_TYPE, mapOf(Website.URL to url, Website.TYPE to Website.TYPE_OTHER.toString()))
        }
        if (contact.birthday.isNotEmpty()) {
            rows += DataRow(
                Event.CONTENT_ITEM_TYPE,
                mapOf(Event.START_DATE to contact.birthday, Event.TYPE to Event.TYPE_BIRTHDAY.toString()),
            )
        }
        if (contact.note.isNotEmpty()) {
            rows += DataRow(Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to contact.note))
        }
        return rows
    }

    /** What the phone store keeps of [contact]: the contact after a round trip through the rows, without extras. */
    fun normalize(contact: Contact): Contact =
        toContact(contact.uid, contact.rev, toRows(contact))

    /** Reads the data rows of the raw contacts [rawContactIds], in row order. [dataUri] is the Data URI to query. */
    fun readRows(resolver: ContentResolver, dataUri: android.net.Uri, rawContactIds: List<Long>): Map<Long, List<DataRow>> {
        val result = HashMap<Long, MutableList<DataRow>>()
        val projection = (listOf(Data.RAW_CONTACT_ID, Data.MIMETYPE) + DATA_COLUMNS).toTypedArray()
        for (chunk in rawContactIds.chunked(MAX_IDS_PER_QUERY)) {
            val selection = "${Data.RAW_CONTACT_ID} IN (${chunk.joinToString(",")})"
            resolver.query(dataUri, projection, selection, null, "${Data._ID} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(1) ?: continue
                    val values = HashMap<String, String?>()
                    DATA_COLUMNS.forEachIndexed { i, column -> values[column] = cursor.getString(2 + i) }
                    result.getOrPut(cursor.getLong(0)) { ArrayList() } += DataRow(mime, values)
                }
            }
        }
        return result
    }

    private fun normalizeDate(raw: String): String {
        val date = raw.substringBefore('T').trim()
        return when {
            DIGITS8.matches(date) -> "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6)}"
            DIGITS_NO_YEAR.matches(date) -> "--${date.substring(2, 4)}-${date.substring(4)}"
            else -> date
        }
    }

    private val DIGITS8 = Regex("\\d{8}")
    private val DIGITS_NO_YEAR = Regex("--\\d{4}")

    private val phoneTypeNames = mapOf(
        Phone.TYPE_CALLBACK to "Callback",
        Phone.TYPE_CAR to "Car",
        Phone.TYPE_COMPANY_MAIN to "Company main",
        Phone.TYPE_ISDN to "ISDN",
        Phone.TYPE_RADIO to "Radio",
        Phone.TYPE_TELEX to "Telex",
        Phone.TYPE_TTY_TDD to "TTY TDD",
        Phone.TYPE_WORK_MOBILE to "Work mobile",
        Phone.TYPE_WORK_PAGER to "Work pager",
        Phone.TYPE_ASSISTANT to "Assistant",
        Phone.TYPE_MMS to "MMS",
    )

    private fun phoneKind(type: Int?, label: String): Pair<Kind, String> = when (type) {
        Phone.TYPE_MOBILE -> Kind.MOBILE to ""
        Phone.TYPE_HOME -> Kind.HOME to ""
        Phone.TYPE_WORK -> Kind.WORK to ""
        Phone.TYPE_MAIN -> Kind.MAIN to ""
        Phone.TYPE_FAX_HOME -> Kind.FAX_HOME to ""
        Phone.TYPE_FAX_WORK -> Kind.FAX_WORK to ""
        Phone.TYPE_OTHER_FAX -> Kind.FAX_OTHER to ""
        Phone.TYPE_PAGER -> Kind.PAGER to ""
        Phone.TYPE_OTHER, null -> Kind.OTHER to ""
        Phone.TYPE_CUSTOM -> customOrOther(label)
        else -> customOrOther(label.ifEmpty { type?.let { phoneTypeNames[it] }.orEmpty() })
    }

    private fun emailKind(type: Int?, label: String): Pair<Kind, String> = when (type) {
        Email.TYPE_HOME -> Kind.HOME to ""
        Email.TYPE_WORK -> Kind.WORK to ""
        Email.TYPE_MOBILE -> Kind.MOBILE to ""
        Email.TYPE_CUSTOM -> customOrOther(label)
        else -> Kind.OTHER to ""
    }

    private fun addressKind(type: Int?, label: String): Pair<Kind, String> = when (type) {
        StructuredPostal.TYPE_HOME -> Kind.HOME to ""
        StructuredPostal.TYPE_WORK -> Kind.WORK to ""
        StructuredPostal.TYPE_CUSTOM -> customOrOther(label)
        else -> Kind.OTHER to ""
    }

    private fun customOrOther(label: String): Pair<Kind, String> =
        if (label.isEmpty()) Kind.OTHER to "" else Kind.CUSTOM to label

    private fun phoneType(kind: Kind, label: String): Pair<Int, String> = when (kind) {
        Kind.MOBILE -> Phone.TYPE_MOBILE to ""
        Kind.HOME -> Phone.TYPE_HOME to ""
        Kind.WORK -> Phone.TYPE_WORK to ""
        Kind.MAIN -> Phone.TYPE_MAIN to ""
        Kind.FAX_HOME -> Phone.TYPE_FAX_HOME to ""
        Kind.FAX_WORK -> Phone.TYPE_FAX_WORK to ""
        Kind.FAX_OTHER -> Phone.TYPE_OTHER_FAX to ""
        Kind.PAGER -> Phone.TYPE_PAGER to ""
        Kind.OTHER -> Phone.TYPE_OTHER to ""
        Kind.CUSTOM -> Phone.TYPE_CUSTOM to label
    }

    private fun emailType(kind: Kind, label: String): Pair<Int, String> = when (kind) {
        Kind.HOME -> Email.TYPE_HOME to ""
        Kind.WORK -> Email.TYPE_WORK to ""
        Kind.MOBILE -> Email.TYPE_MOBILE to ""
        Kind.CUSTOM -> Email.TYPE_CUSTOM to label
        else -> Email.TYPE_OTHER to ""
    }

    private fun addressType(kind: Kind, label: String): Pair<Int, String> = when (kind) {
        Kind.HOME -> StructuredPostal.TYPE_HOME to ""
        Kind.WORK -> StructuredPostal.TYPE_WORK to ""
        Kind.CUSTOM -> StructuredPostal.TYPE_CUSTOM to label
        else -> StructuredPostal.TYPE_OTHER to ""
    }
}
