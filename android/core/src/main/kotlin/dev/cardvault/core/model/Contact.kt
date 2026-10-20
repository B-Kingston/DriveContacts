package dev.cardvault.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Kind of a phone number, email address or postal address. See docs/spec.md section 2.2. */
@Serializable
enum class Kind { MOBILE, HOME, WORK, MAIN, FAX_HOME, FAX_WORK, FAX_OTHER, PAGER, OTHER, CUSTOM }

@Serializable
data class StructuredName(
    val family: String = "",
    val given: String = "",
    val additional: String = "",
    val prefix: String = "",
    val suffix: String = "",
) {
    val isEmpty: Boolean get() = family.isEmpty() && given.isEmpty() && additional.isEmpty() && prefix.isEmpty() && suffix.isEmpty()
}

@Serializable
data class Organization(
    val name: String = "",
    val department: String = "",
) {
    val isEmpty: Boolean get() = name.isEmpty() && department.isEmpty()
}

/** A phone number or an email address. [customLabel] is "" unless [kind] is [Kind.CUSTOM]. */
@Serializable
data class TypedValue(
    val kind: Kind,
    val value: String,
    val customLabel: String = "",
)

/** A postal address. [customLabel] is "" unless [kind] is [Kind.CUSTOM]. */
@Serializable
data class PostalAddress(
    val kind: Kind,
    val customLabel: String = "",
    val poBox: String = "",
    val extended: String = "",
    val street: String = "",
    val locality: String = "",
    val region: String = "",
    val postalCode: String = "",
    val country: String = "",
)

/**
 * One contact. Absent text is "", never null.
 * [rev] is the last change time in epoch milliseconds. It is not part of content equality ([sameContentAs]).
 */
@Serializable
data class Contact(
    val uid: String,
    val rev: Long = 0L,
    val formattedName: String = "",
    val name: StructuredName = StructuredName(),
    val nickname: String = "",
    val organization: Organization = Organization(),
    val title: String = "",
    val birthday: String = "",
    val phones: List<TypedValue> = emptyList(),
    val emails: List<TypedValue> = emptyList(),
    val addresses: List<PostalAddress> = emptyList(),
    val urls: List<String> = emptyList(),
    val note: String = "",
    val extras: List<String> = emptyList(),
) {
    /** Content equality: all fields except [rev]. */
    fun sameContentAs(other: Contact): Boolean = copy(rev = 0L) == other.copy(rev = 0L)

    companion object {
        /** A new random UID in the form `urn:uuid:<lowercase UUID v4>`. */
        fun newUid(): String = "urn:uuid:" + UUID.randomUUID().toString().lowercase()
    }
}
