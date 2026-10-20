package dev.drivecontacts.ui

import dev.drivecontacts.core.DiffRow
import dev.drivecontacts.core.Field
import dev.drivecontacts.core.Merge
import dev.drivecontacts.core.VCard
import dev.drivecontacts.core.model.Contact
import dev.drivecontacts.core.model.Kind
import dev.drivecontacts.core.model.PostalAddress
import dev.drivecontacts.core.model.StructuredName
import dev.drivecontacts.core.model.TypedValue
import java.text.CollationKey
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

// Pure presentation functions. They do not use Android classes, so JVM tests can run them.

/** The kinds that the kind dropdown offers for each list. */
val PHONE_KINDS: List<Kind> = listOf(
    Kind.MOBILE, Kind.HOME, Kind.WORK, Kind.MAIN, Kind.FAX_HOME, Kind.FAX_WORK, Kind.FAX_OTHER, Kind.PAGER, Kind.OTHER, Kind.CUSTOM,
)
val EMAIL_KINDS: List<Kind> = listOf(Kind.MOBILE, Kind.HOME, Kind.WORK, Kind.OTHER, Kind.CUSTOM)
val ADDRESS_KINDS: List<Kind> = listOf(Kind.HOME, Kind.WORK, Kind.OTHER, Kind.CUSTOM)

private fun digitsOnly(text: String): String = text.filter { it in '0'..'9' }

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Lower case text without accents, so that "jose" finds "José". */
fun normalizeForSearch(text: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

/** True when the query has only digits and phone punctuation, and at least one digit. */
private fun isPhoneLikeQuery(query: String): Boolean =
    query.any { it in '0'..'9' } && query.all { it in '0'..'9' || it in "+-() ." }

/**
 * A contact with the keys that list display and search need.
 * The keys are computed once, so a keystroke does not normalize the text of every contact again.
 */
class ListEntry internal constructor(
    val contact: Contact,
    internal val sortKey: CollationKey,
    private val texts: List<String>,
    private val phoneDigits: List<String>,
) {
    /**
     * Search match. The match ignores case and accents.
     * A blank query matches. A phone-like query (digits and phone punctuation) also matches phone numbers by their digits.
     */
    fun matches(query: String): Boolean {
        val q = normalizeForSearch(query.trim())
        if (q.isEmpty()) return true
        if (texts.any { it.contains(q) }) return true
        if (isPhoneLikeQuery(q)) {
            val digits = digitsOnly(q)
            if (phoneDigits.any { it.contains(digits) }) return true
        }
        return false
    }
}

/** The collator for the order of the list: the current locale, accents count, case does not. */
fun displayCollator(locale: Locale = Locale.getDefault()): Collator =
    Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

fun listEntry(contact: Contact, collator: Collator): ListEntry {
    val texts = buildList {
        add(contact.formattedName)
        add(contact.name.given)
        add(contact.name.family)
        add(contact.name.additional)
        add(contact.nickname)
        add(contact.organization.name)
        contact.emails.forEach { add(it.value) }
        contact.phones.forEach { add(it.value) }
    }.map(::normalizeForSearch)
    return ListEntry(
        contact = contact,
        sortKey = collator.getCollationKey(displayName(contact)),
        texts = texts,
        phoneDigits = contact.phones.map { digitsOnly(it.value) },
    )
}

/** The entries in display order: by name with [collator], then by UID. */
fun indexContacts(contacts: List<Contact>, collator: Collator = displayCollator()): List<ListEntry> =
    contacts.map { listEntry(it, collator) }
        .sortedWith(compareBy<ListEntry> { it.sortKey }.thenBy { it.contact.uid })

/** The contacts of the entries that match the query. The order of [entries] stays. */
fun filterEntries(entries: List<ListEntry>, query: String): List<Contact> =
    if (query.isBlank()) entries.map { it.contact } else entries.filter { it.matches(query) }.map { it.contact }

/** Search match of one contact. See [ListEntry.matches]. */
fun matchesQuery(contact: Contact, query: String): Boolean = listEntry(contact, displayCollator()).matches(query)

/** Joins the name parts in display order with single spaces. */
fun joinNameParts(name: StructuredName): String =
    listOf(name.prefix, name.given, name.additional, name.family, name.suffix)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/** Gives [formattedName] when it is not blank. Otherwise gives the name parts joined. */
fun autoFormattedName(formattedName: String, name: StructuredName): String =
    formattedName.trim().ifEmpty { joinNameParts(name) }

/** The name that the list shows. Gives "" when the contact has nothing to show. */
fun displayName(contact: Contact?): String {
    if (contact == null) return ""
    val candidates = sequenceOf(
        contact.formattedName.trim(),
        joinNameParts(contact.name),
        contact.organization.name.trim(),
        contact.emails.firstOrNull()?.value?.trim().orEmpty(),
        contact.phones.firstOrNull()?.value?.trim().orEmpty(),
    )
    return candidates.firstOrNull { it.isNotEmpty() } ?: ""
}

/** The second line of a list row: organization, first phone or first email. */
fun contactSubtitle(contact: Contact): String {
    val candidates = sequenceOf(
        contact.phones.firstOrNull()?.value?.trim().orEmpty(),
        contact.emails.firstOrNull()?.value?.trim().orEmpty(),
        contact.organization.name.trim(),
    )
    val subtitle = candidates.firstOrNull { it.isNotEmpty() } ?: ""
    return if (subtitle == displayName(contact)) "" else subtitle
}

/** One letter for the avatar of a list row. */
fun avatarLetter(name: String): String {
    val first = name.trim().firstOrNull { it.isLetterOrDigit() } ?: return "#"
    return first.uppercaseChar().toString()
}

private fun TypedValue.normalized(): TypedValue =
    if (kind == Kind.CUSTOM) {
        val label = customLabel.trim()
        if (label.isEmpty()) copy(kind = Kind.OTHER, customLabel = "", value = value.trim())
        else copy(customLabel = label, value = value.trim())
    } else {
        copy(customLabel = "", value = value.trim())
    }

private fun PostalAddress.normalized(): PostalAddress {
    val trimmed = copy(
        poBox = poBox.trim(), extended = extended.trim(), street = street.trim(), locality = locality.trim(),
        region = region.trim(), postalCode = postalCode.trim(), country = country.trim(),
    )
    return if (kind == Kind.CUSTOM) {
        val label = customLabel.trim()
        if (label.isEmpty()) trimmed.copy(kind = Kind.OTHER, customLabel = "") else trimmed.copy(customLabel = label)
    } else {
        trimmed.copy(customLabel = "")
    }
}

private fun PostalAddress.isBlank(): Boolean =
    poBox.isEmpty() && extended.isEmpty() && street.isEmpty() && locality.isEmpty() &&
        region.isEmpty() && postalCode.isEmpty() && country.isEmpty()

/**
 * Cleans the edit form before it is saved.
 * It trims single-line text, drops empty list items, fills an empty formatted name from the name parts,
 * and turns a custom kind without a label into the kind "other".
 * The note and the extras stay unchanged.
 * The result has the content that the contact file keeps for it ([VCard.canonical]).
 */
fun prepareForSave(contact: Contact): Contact {
    val name = contact.name.copy(
        family = contact.name.family.trim(), given = contact.name.given.trim(),
        additional = contact.name.additional.trim(), prefix = contact.name.prefix.trim(),
        suffix = contact.name.suffix.trim(),
    )
    return VCard.canonical(contact.copy(
        formattedName = autoFormattedName(contact.formattedName, name),
        name = name,
        nickname = contact.nickname.trim(),
        organization = contact.organization.copy(
            name = contact.organization.name.trim(),
            department = contact.organization.department.trim(),
        ),
        title = contact.title.trim(),
        birthday = contact.birthday.trim(),
        phones = contact.phones.map { it.normalized() }.filter { it.value.isNotEmpty() },
        emails = contact.emails.map { it.normalized() }.filter { it.value.isNotEmpty() },
        addresses = contact.addresses.map { it.normalized() }.filterNot { it.isBlank() },
        urls = contact.urls.map { it.trim() }.filter { it.isNotEmpty() },
    ))
}

/**
 * The contact to store for a saved edit form.
 * [original] is the contact when the form opened, [current] is the contact now, [draft] is the form.
 * The function cleans all three the same way ([prepareForSave]), so spaces in stored values do not look like edits.
 * A field that the user did not change keeps its current value, so a change from a sync is not lost.
 * A field that the user changed gets the form value. A new contact, or one that is gone, gets the cleaned form.
 */
fun mergeEdit(original: Contact?, current: Contact?, draft: Contact): Contact {
    val form = prepareForSave(draft)
    if (original == null || current == null) return form
    val merged = Merge.merge3(listOf(prepareForSave(original)), listOf(prepareForSave(current)), listOf(form))
    return merged.contacts.firstOrNull { it.uid == form.uid } ?: form
}

/** True when the contact has at least one value that a user can see. */
fun hasContent(contact: Contact): Boolean =
    contact.formattedName.isNotEmpty() || !contact.name.isEmpty || contact.nickname.isNotEmpty() ||
        !contact.organization.isEmpty || contact.title.isNotEmpty() || contact.birthday.isNotEmpty() ||
        contact.phones.isNotEmpty() || contact.emails.isNotEmpty() || contact.addresses.isNotEmpty() ||
        contact.urls.isNotEmpty() || contact.note.isNotEmpty()

/** The value of a conflicting field as text for the choice list. */
fun fieldText(contact: Contact?, field: Field): String {
    if (contact == null) return ""
    return when (field) {
        Field.FORMATTED_NAME -> contact.formattedName
        Field.NAME -> joinNameParts(contact.name)
        Field.NICKNAME -> contact.nickname
        Field.ORGANIZATION ->
            listOf(contact.organization.name, contact.organization.department).filter { it.isNotEmpty() }.joinToString(", ")
        Field.TITLE -> contact.title
        Field.BIRTHDAY -> contact.birthday
        Field.NOTE -> contact.note
    }
}

/**
 * Indexes of the rows that the side-by-side table shows.
 * A row with no value on any side, including the optional [baseColumn], and no change is hidden.
 */
fun visibleRowIndices(rows: List<DiffRow>, baseColumn: List<List<String>>? = null): List<Int> =
    rows.indices.filter { i ->
        val row = rows[i]
        row.changed || row.left.isNotEmpty() || row.right.isNotEmpty() || baseColumn?.get(i)?.isNotEmpty() == true
    }

/** For each row, the text of the same row in [baseRows]. A row that [baseRows] does not have gives an empty list. */
fun alignBase(rows: List<DiffRow>, baseRows: List<DiffRow>): List<List<String>> {
    val byLabel = baseRows.associate { it.label to it.left }
    return rows.map { byLabel[it.label].orEmpty() }
}

/** A coarse age of a time stamp. */
sealed interface RelativeTime {
    data object Now : RelativeTime
    data class Minutes(val count: Long) : RelativeTime
    data class Hours(val count: Long) : RelativeTime
    data class Days(val count: Long) : RelativeTime
}

/** The age of [thenMs] at [nowMs]. A time in the future counts as now. */
fun relativeTime(nowMs: Long, thenMs: Long): RelativeTime {
    val minutes = ((nowMs - thenMs) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> RelativeTime.Now
        minutes < 60 -> RelativeTime.Minutes(minutes)
        minutes < 24 * 60 -> RelativeTime.Hours(minutes / 60)
        else -> RelativeTime.Days(minutes / (24 * 60))
    }
}
