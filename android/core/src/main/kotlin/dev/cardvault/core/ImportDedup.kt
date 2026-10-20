package dev.cardvault.core

import dev.cardvault.core.model.Contact

/** New UIDs and duplicate check for imported contacts. See docs/spec.md section 7. */
internal object ImportDedup {
    private class Profile(val phones: Set<String>, val emails: Set<String>, val bare: Boolean)

    private val SPACES = Regex("\\s+")

    fun prepare(existing: List<Contact>, incoming: List<Contact>, now: Long): ImportResult {
        val byName = HashMap<String, MutableList<Profile>>()
        for (c in existing) byName.getOrPut(normalizeName(c.formattedName)) { mutableListOf() } += profile(c)

        val imported = ArrayList<Contact>()
        var skipped = 0
        for (c in incoming) {
            val name = normalizeName(c.formattedName)
            val p = profile(c)
            val candidates = byName.getOrPut(name) { mutableListOf() }
            if (candidates.any { isDuplicate(it, p) }) {
                skipped++
                continue
            }
            candidates += p
            imported += c.copy(uid = Contact.newUid(), rev = now)
        }
        return ImportResult(imported, skipped)
    }

    private fun isDuplicate(a: Profile, b: Profile): Boolean =
        (a.bare && b.bare) || a.phones.any { it in b.phones } || a.emails.any { it in b.emails }

    private fun normalizeName(s: String): String = s.trim().lowercase().replace(SPACES, " ")

    private fun profile(c: Contact): Profile = Profile(
        phones = c.phones.mapNotNull { phoneKey(it.value) }.toSet(),
        emails = c.emails.map { it.value.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
        bare = c.phones.isEmpty() && c.emails.isEmpty(),
    )

    private fun phoneKey(value: String): String? {
        val digits = value.filter { it in '0'..'9' }
        if (digits.isEmpty()) return null
        return if (digits.length > 9) digits.substring(digits.length - 9) else digits
    }
}
