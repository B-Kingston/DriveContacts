package dev.cardvault.core

import dev.cardvault.core.model.Contact

/** Three-way merge of contact lists. See docs/spec.md sections 4.1 to 4.3. */
internal object ThreeWayMerge {
    fun merge(base: List<Contact>, local: List<Contact>, remote: List<Contact>): MergeResult {
        val b = index(base)
        val l = index(local)
        val r = index(remote)
        val uids = (b.keys + l.keys + r.keys).sorted()
        val contacts = ArrayList<Contact>()
        val conflicts = ArrayList<Conflict>()
        for (uid in uids) {
            val bc = b[uid]
            val lc = l[uid]
            val rc = r[uid]
            when {
                lc == null && rc == null -> {}
                lc != null && rc == null -> when {
                    bc == null -> contacts += lc
                    lc.sameContentAs(bc) -> {}
                    else -> {
                        contacts += lc
                        conflicts += Conflict(uid, ConflictKind.DELETED_REMOTELY, bc, lc, null, emptyList())
                    }
                }
                lc == null && rc != null -> when {
                    bc == null -> contacts += rc
                    rc.sameContentAs(bc) -> {}
                    else -> {
                        contacts += rc
                        conflicts += Conflict(uid, ConflictKind.DELETED_LOCALLY, bc, null, rc, emptyList())
                    }
                }
                else -> {
                    val fields = ArrayList<Field>()
                    contacts += mergeFields(bc ?: Contact(uid = uid), lc!!, rc!!, fields)
                    if (fields.isNotEmpty()) conflicts += Conflict(uid, ConflictKind.FIELDS, bc, lc, rc, fields)
                }
            }
        }
        return MergeResult(contacts, conflicts)
    }

    fun resolveFields(conflict: Conflict, current: Contact, useLocal: Set<Field>, now: Long): Contact {
        val local = conflict.local ?: return current.copy(rev = now)
        var c = current
        for (f in conflict.fields) {
            if (f !in useLocal) continue
            c = when (f) {
                Field.FORMATTED_NAME -> c.copy(formattedName = local.formattedName)
                Field.NAME -> c.copy(name = local.name)
                Field.NICKNAME -> c.copy(nickname = local.nickname)
                Field.ORGANIZATION -> c.copy(organization = local.organization)
                Field.TITLE -> c.copy(title = local.title)
                Field.BIRTHDAY -> c.copy(birthday = local.birthday)
                Field.NOTE -> c.copy(note = local.note)
            }
        }
        return c.copy(rev = now)
    }

    // The first card wins when a list holds one UID twice.
    private fun index(list: List<Contact>): Map<String, Contact> {
        val map = LinkedHashMap<String, Contact>()
        for (c in list) map.putIfAbsent(c.uid, c)
        return map
    }

    private fun mergeFields(b: Contact, l: Contact, r: Contact, conflicts: MutableList<Field>): Contact {
        fun <T> scalar(field: Field, bv: T, lv: T, rv: T): T = when {
            lv == bv -> rv
            rv == bv -> lv
            lv == rv -> lv
            else -> {
                conflicts += field
                rv
            }
        }

        val merged = Contact(
            uid = r.uid,
            formattedName = scalar(Field.FORMATTED_NAME, b.formattedName, l.formattedName, r.formattedName),
            name = scalar(Field.NAME, b.name, l.name, r.name),
            nickname = scalar(Field.NICKNAME, b.nickname, l.nickname, r.nickname),
            organization = scalar(Field.ORGANIZATION, b.organization, l.organization, r.organization),
            title = scalar(Field.TITLE, b.title, l.title, r.title),
            birthday = scalar(Field.BIRTHDAY, b.birthday, l.birthday, r.birthday),
            phones = list(b.phones, l.phones, r.phones),
            emails = list(b.emails, l.emails, r.emails),
            addresses = list(b.addresses, l.addresses, r.addresses),
            urls = list(b.urls, l.urls, r.urls),
            note = scalar(Field.NOTE, b.note, l.note, r.note),
            extras = list(b.extras, l.extras, r.extras),
        )
        val rev = when {
            merged.sameContentAs(r) -> r.rev
            merged.sameContentAs(l) -> l.rev
            else -> maxOf(l.rev, r.rev)
        }
        return merged.copy(rev = rev)
    }

    private fun <T> list(b: List<T>, l: List<T>, r: List<T>): List<T> {
        val removedLocally = minus(b, l)
        val addedLocally = minus(l, b)
        return minus(r, removedLocally) + minus(addedLocally, r)
    }

    // Multiset difference: each item of [other] removes one equal item of [items].
    private fun <T> minus(items: List<T>, other: List<T>): List<T> {
        if (other.isEmpty()) return items
        val counts = HashMap<T, Int>()
        for (o in other) counts.merge(o, 1, Int::plus)
        val out = ArrayList<T>(items.size)
        for (i in items) {
            val n = counts[i] ?: 0
            if (n > 0) counts[i] = n - 1 else out += i
        }
        return out
    }
}
