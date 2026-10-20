package dev.drivecontacts.core

import dev.drivecontacts.core.model.Contact
import dev.drivecontacts.core.model.Kind
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Canonical vCard 4.0 writer. See docs/spec.md section 2.1. */
internal object VCardWriter {
    private val REV_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private const val MAX_OCTETS = 75

    fun write(contacts: List<Contact>): ByteArray {
        val out = ByteArrayOutputStream()
        for (c in contacts.sortedBy { it.uid.trim() }) writeCard(out, c)
        return out.toByteArray()
    }

    private fun writeCard(out: ByteArrayOutputStream, c: Contact) {
        var groups = 0

        // The parser trims labels. The writer trims them too, so both sides agree on the content.
        fun typed(out: ByteArrayOutputStream, prop: String, type: String, kind: Kind, customLabel: String, value: String) {
            val label = customLabel.trim()
            if (kind == Kind.CUSTOM && label.isNotEmpty()) {
                groups++
                val g = "cv$groups"
                line(out, "$g.$prop:$value")
                line(out, "$g.X-ABLabel:${escape(label)}")
            } else {
                line(out, "$prop;TYPE=$type:$value")
            }
        }

        line(out, "BEGIN:VCARD")
        line(out, "VERSION:4.0")
        line(out, "UID:${escape(c.uid.trim())}")
        line(out, "REV:${REV_FORMAT.format(Instant.ofEpochMilli(c.rev))}")
        if (c.formattedName.isNotEmpty()) line(out, "FN:${escape(c.formattedName)}")
        val n = c.name
        if (!n.isEmpty) line(out, "N:" + listOf(n.family, n.given, n.additional, n.prefix, n.suffix).joinToString(";") { escape(it) })
        val nickname = c.nickname.trim()
        if (nickname.isNotEmpty()) line(out, "NICKNAME:${escape(nickname)}")
        if (!c.organization.isEmpty) {
            val org = escape(c.organization.name)
            line(out, if (c.organization.department.isEmpty()) "ORG:$org" else "ORG:$org;${escape(c.organization.department)}")
        }
        if (c.title.isNotEmpty()) line(out, "TITLE:${escape(c.title)}")
        val birthday = VCardParser.normalizeBirthday(c.birthday.trim())
        if (birthday.isNotEmpty()) line(out, "BDAY:${escape(birthday)}")
        for (p in c.phones) {
            if (p.value.isEmpty()) continue
            typed(out, "TEL", phoneType(p.kind), p.kind, p.customLabel, escape(p.value))
        }
        for (e in c.emails) {
            if (e.value.isEmpty()) continue
            typed(out, "EMAIL", emailType(e.kind), e.kind, e.customLabel, escape(e.value))
        }
        for (a in c.addresses) {
            val parts = listOf(a.poBox, a.extended, a.street, a.locality, a.region, a.postalCode, a.country)
            if (parts.all { it.isEmpty() }) continue
            typed(out, "ADR", addressType(a.kind), a.kind, a.customLabel, parts.joinToString(";") { escape(it) })
        }
        for (u in c.urls) if (u.isNotEmpty()) line(out, "URL:${escape(u)}")
        if (c.note.isNotEmpty()) line(out, "NOTE:${escape(c.note)}")
        for (x in c.extras) line(out, x)
        line(out, "END:VCARD")
    }

    private fun phoneType(kind: Kind): String = when (kind) {
        Kind.MOBILE -> "cell"
        Kind.HOME -> "home"
        Kind.WORK -> "work"
        Kind.MAIN -> "main"
        Kind.FAX_HOME -> "home,fax"
        Kind.FAX_WORK -> "work,fax"
        Kind.FAX_OTHER -> "fax,x-other"
        Kind.PAGER -> "pager"
        Kind.OTHER, Kind.CUSTOM -> "x-other"
    }

    private fun emailType(kind: Kind): String = when (kind) {
        Kind.MOBILE -> "x-mobile"
        Kind.HOME -> "home"
        Kind.WORK -> "work"
        else -> "x-other"
    }

    private fun addressType(kind: Kind): String = when (kind) {
        Kind.HOME -> "home"
        Kind.WORK -> "work"
        else -> "x-other"
    }

    internal fun escape(s: String): String {
        if (s.none { it == '\\' || it == '\n' || it == '\r' || it == ',' || it == ';' }) return s
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            when (val ch = s[i]) {
                '\\' -> sb.append("\\\\")
                '\r' -> {
                    sb.append("\\n")
                    if (i + 1 < s.length && s[i + 1] == '\n') i++
                }
                '\n' -> sb.append("\\n")
                ',' -> sb.append("\\,")
                ';' -> sb.append("\\;")
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    // Writes one logical line. Folds at 75 octets. Never splits a UTF-8 sequence.
    private fun line(out: ByteArrayOutputStream, text: String) {
        var octets = 0
        var limit = MAX_OCTETS
        var i = 0
        val buf = StringBuilder()
        fun flush() {
            out.write(buf.toString().toByteArray(Charsets.UTF_8))
            buf.setLength(0)
        }
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = utf8Length(cp)
            if (octets + len > limit) {
                flush()
                out.write(CRLF_SPACE)
                octets = 1
                limit = MAX_OCTETS
            }
            buf.appendCodePoint(cp)
            octets += len
            i += Character.charCount(cp)
        }
        flush()
        out.write(CRLF)
    }

    private val CRLF = byteArrayOf(13, 10)
    private val CRLF_SPACE = byteArrayOf(13, 10, 32)

    private fun utf8Length(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }
}
