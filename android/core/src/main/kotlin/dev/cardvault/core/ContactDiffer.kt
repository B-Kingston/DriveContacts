package dev.cardvault.core

import dev.cardvault.core.model.Contact
import dev.cardvault.core.model.Kind
import dev.cardvault.core.model.PostalAddress
import dev.cardvault.core.model.TypedValue

/** Side-by-side diff of contacts. See docs/spec.md section 4.4. */
internal object ContactDiffer {
    private const val NO_NAME = "(No name)"

    fun diff(left: List<Contact>, right: List<Contact>): List<ContactDiff> {
        val l = LinkedHashMap<String, Contact>()
        for (c in left) l.putIfAbsent(c.uid, c)
        val r = LinkedHashMap<String, Contact>()
        for (c in right) r.putIfAbsent(c.uid, c)

        val out = ArrayList<ContactDiff>()
        for (uid in l.keys + r.keys) {
            val lc = l[uid]
            val rc = r[uid]
            val status = when {
                lc == null -> DiffStatus.ADDED
                rc == null -> DiffStatus.REMOVED
                lc.sameContentAs(rc) -> continue
                else -> DiffStatus.CHANGED
            }
            val name = listOfNotNull(rc?.formattedName, lc?.formattedName).firstOrNull { it.isNotBlank() } ?: NO_NAME
            out += ContactDiff(uid, name, status, rows(lc, rc))
        }
        return out.sortedWith(compareBy<ContactDiff> { it.displayName.lowercase() }.thenBy { it.uid })
    }

    fun rows(left: Contact?, right: Contact?): List<DiffRow> {
        fun row(label: String, f: (Contact) -> List<String>): DiffRow {
            val l = left?.let(f) ?: emptyList()
            val r = right?.let(f) ?: emptyList()
            return DiffRow(label, l, r, l != r)
        }
        return listOf(
            row("Name") { scalar(it.formattedName) },
            row("Structured name") { c ->
                val n = c.name
                scalar(listOf(n.prefix, n.given, n.additional, n.family, n.suffix).filter { it.isNotEmpty() }.joinToString(" "))
            },
            row("Nickname") { scalar(it.nickname) },
            row("Organization") { c ->
                scalar(listOf(c.organization.name, c.organization.department).filter { it.isNotEmpty() }.joinToString(", "))
            },
            row("Title") { scalar(it.title) },
            row("Birthday") { scalar(it.birthday) },
            row("Phones") { c -> c.phones.map(::typedItem) },
            row("Emails") { c -> c.emails.map(::typedItem) },
            row("Addresses") { c -> c.addresses.map(::addressItem) },
            row("Websites") { it.urls },
            row("Note") { scalar(it.note) },
            row("Other properties") { it.extras },
        )
    }

    private fun scalar(v: String): List<String> = if (v.isEmpty()) emptyList() else listOf(v)

    private fun typedItem(t: TypedValue): String = "${kindName(t.kind, t.customLabel)}: ${t.value}"

    private fun addressItem(a: PostalAddress): String {
        val parts = listOf(a.street, a.extended, a.poBox, a.locality, a.region, a.postalCode, a.country).filter { it.isNotEmpty() }
        return "${kindName(a.kind, a.customLabel)}: ${parts.joinToString(", ")}"
    }

    private fun kindName(kind: Kind, label: String): String = when (kind) {
        Kind.MOBILE -> "Mobile"
        Kind.HOME -> "Home"
        Kind.WORK -> "Work"
        Kind.MAIN -> "Main"
        Kind.FAX_HOME -> "Home fax"
        Kind.FAX_WORK -> "Work fax"
        Kind.FAX_OTHER -> "Other fax"
        Kind.PAGER -> "Pager"
        Kind.OTHER -> "Other"
        Kind.CUSTOM -> label.ifEmpty { "Other" }
    }
}
