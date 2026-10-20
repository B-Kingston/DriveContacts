package dev.cardvault.core

import dev.cardvault.core.model.Contact
import dev.cardvault.core.model.Kind
import dev.cardvault.core.model.Organization
import dev.cardvault.core.model.PostalAddress
import dev.cardvault.core.model.StructuredName
import dev.cardvault.core.model.TypedValue
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Lenient vCard 2.1, 3.0 and 4.0 reader. See docs/spec.md section 2.3. */
internal object VCardParser {
    private class Param(val name: String, val values: List<String>)

    private class Line(
        val group: String,
        val name: String,
        val params: List<Param>,
        val rawValue: String,
        val raw: String,
    ) {
        fun param(name: String): String? = params.firstOrNull { it.name == name }?.values?.firstOrNull()
        val types: List<String> get() = params.filter { it.name == "TYPE" }.flatMap { it.values }
    }

    private val KNOWN = setOf("FN", "N", "NICKNAME", "ORG", "TITLE", "BDAY", "TEL", "EMAIL", "ADR", "URL", "NOTE")
    private val BINARY = setOf("PHOTO", "LOGO", "SOUND", "KEY")
    private val SKIPPED = setOf("PRODID", "VERSION", "BEGIN", "END")
    private val ENCODING_WORDS = setOf("QUOTED-PRINTABLE", "BASE64", "B", "8BIT", "7BIT")
    private val APPLE_LABEL = Regex("^_\\\$!<(.*)>!\\\$_\$")
    private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*")
    private val BASIC_DATE = Regex("^(\\d{4})(\\d{2})(\\d{2})(T.*)?$")
    private val EXT_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(T.*)?$")
    private val NO_YEAR = Regex("^--(\\d{2})(\\d{2})$")
    private val BASIC_REV = Regex("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})(?:[.,]\\d+)?Z?$")
    private val EXT_REV = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:[.,]\\d+)?Z?$")

    fun parse(bytes: ByteArray): ParseResult {
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        val cards = mutableListOf<List<Line>>()
        var current: MutableList<Line>? = null
        for (raw in unfold(text)) {
            val line = parseLine(raw) ?: continue
            val isVcard = line.rawValue.trim().equals("VCARD", ignoreCase = true)
            if (line.name == "BEGIN" && isVcard) {
                current?.let { cards += it }
                current = mutableListOf()
            } else if (line.name == "END" && isVcard) {
                current?.let { cards += it }
                current = null
            } else {
                current?.add(line)
            }
        }
        current?.let { cards += it }

        val contacts = mutableListOf<Contact>()
        val seen = HashSet<String>()
        var dropped = 0
        for (card in cards) {
            val built = build(card)
            dropped += built.second
            val contact = built.first ?: continue
            val uid = if (contact.uid.isEmpty() || !seen.add(contact.uid)) {
                Contact.newUid().also { seen.add(it) }
            } else {
                contact.uid
            }
            contacts += contact.copy(uid = uid)
        }
        return ParseResult(contacts, dropped)
    }

    // Joins folded lines and quoted-printable soft line breaks. Skips empty lines.
    private fun unfold(text: String): List<String> {
        val physical = text.split("\r\n", "\n", "\r")
        val out = ArrayList<String>()
        var current: StringBuilder? = null
        var qpOpen = false
        fun flush() {
            current?.let { if (it.isNotEmpty()) out += it.toString() }
            current = null
            qpOpen = false
        }
        for (line in physical) {
            val cur = current
            if (cur != null && qpOpen) {
                cur.setLength(cur.length - 1)
                cur.append(line)
                qpOpen = cur.endsWith("=")
            } else if (cur != null && line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) {
                cur.append(line, 1, line.length)
                qpOpen = isQuotedPrintable(cur) && cur.endsWith("=")
            } else {
                flush()
                if (line.isEmpty()) continue
                current = StringBuilder(line)
                qpOpen = isQuotedPrintable(line) && line.endsWith("=")
            }
        }
        flush()
        return out
    }

    private fun isQuotedPrintable(line: CharSequence): Boolean {
        val colon = line.indexOf(':')
        val head = if (colon < 0) line else line.subSequence(0, colon)
        return head.toString().uppercase().contains("QUOTED-PRINTABLE")
    }

    private fun parseLine(raw: String): Line? {
        val colon = indexOutsideQuotes(raw, ':')
        if (colon < 0) return null
        val head = raw.substring(0, colon)
        val value = raw.substring(colon + 1)
        val parts = splitOutsideQuotes(head, ';')
        val first = parts[0].trim()
        if (first.isEmpty()) return null
        val dot = first.indexOf('.')
        val group = if (dot >= 0) first.substring(0, dot) else ""
        val name = (if (dot >= 0) first.substring(dot + 1) else first).uppercase()
        val params = parts.drop(1).map { part ->
            val eq = part.indexOf('=')
            if (eq < 0) {
                val bare = part.trim()
                if (bare.uppercase() in ENCODING_WORDS) Param("ENCODING", listOf(bare)) else Param("TYPE", listOf(bare))
            } else {
                val pname = part.substring(0, eq).trim().uppercase()
                val values = splitOutsideQuotes(part.substring(eq + 1), ',').map { it.trim().removeSurrounding("\"") }
                    .flatMap { if (pname == "TYPE") it.split(',').map(String::trim) else listOf(it) }
                Param(pname, values)
            }
        }.filter { p -> p.values.any { it.isNotEmpty() } }
        return Line(group, name, params, value, raw)
    }

    private fun indexOutsideQuotes(s: String, ch: Char): Int {
        var quoted = false
        for (i in s.indices) {
            val c = s[i]
            if (c == '"') quoted = !quoted else if (c == ch && !quoted) return i
        }
        return -1
    }

    private fun splitOutsideQuotes(s: String, ch: Char): List<String> {
        val out = ArrayList<String>()
        var quoted = false
        var start = 0
        for (i in s.indices) {
            val c = s[i]
            if (c == '"') quoted = !quoted else if (c == ch && !quoted) {
                out += s.substring(start, i)
                start = i + 1
            }
        }
        out += s.substring(start)
        return out
    }

    // Splits at an unescaped separator. The parts keep their escapes.
    private fun splitUnescaped(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(c).append(s[i + 1])
                i += 2
            } else if (c == sep) {
                out += sb.toString()
                sb.setLength(0)
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        out += sb.toString()
        return out
    }

    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                sb.append(if (n == 'n' || n == 'N') '\n' else n)
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** The value of the line after quoted-printable decoding. */
    private fun decoded(line: Line): String {
        val encoding = line.param("ENCODING")?.uppercase()
        if (encoding != "QUOTED-PRINTABLE") return line.rawValue
        val charset = line.param("CHARSET")?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return decodeQuotedPrintable(line.rawValue, charset).replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun decodeQuotedPrintable(s: String, charset: Charset): String {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 2 < s.length && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0) {
                out.write(hex(s[i + 1]) * 16 + hex(s[i + 2]))
                i += 3
            } else {
                val end = if (Character.isHighSurrogate(c) && i + 1 < s.length) i + 2 else i + 1
                out.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return String(out.toByteArray(), charset)
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }

    private fun text(line: Line): String = unescape(decoded(line))

    private fun build(lines: List<Line>): Pair<Contact?, Int> {
        val knownGroups = lines.filter { it.group.isNotEmpty() && it.name in KNOWN }.map { it.group }.toSet()
        val labels = HashMap<String, String>()
        for (l in lines) {
            if (l.group.isNotEmpty() && l.name == "X-ABLABEL" && l.group !in labels) labels[l.group] = text(l).trim()
        }

        var uid = ""
        var rev = 0L
        var fn = ""
        var name = StructuredName()
        var nickname = ""
        var org = Organization()
        var title = ""
        var birthday = ""
        var note = ""
        val phones = mutableListOf<TypedValue>()
        val emails = mutableListOf<TypedValue>()
        val addresses = mutableListOf<PostalAddress>()
        val urls = mutableListOf<String>()
        val extras = mutableListOf<String>()
        var dropped = 0

        for (l in lines) {
            val label = labels[l.group]?.takeIf { it.isNotEmpty() }
            when (l.name) {
                in SKIPPED -> {}
                "UID" -> if (uid.isEmpty()) uid = text(l).trim()
                "REV" -> if (rev == 0L) rev = parseRev(text(l).trim())
                "FN" -> text(l).let { if (it.isEmpty()) {} else if (fn.isEmpty()) fn = it else extras += l.raw }
                "N" -> {
                    val c = splitUnescaped(decoded(l), ';').map { unescape(it) }
                    val parsed = StructuredName(c.getOrElse(0) { "" }, c.getOrElse(1) { "" }, c.getOrElse(2) { "" }, c.getOrElse(3) { "" }, c.getOrElse(4) { "" })
                    if (parsed.isEmpty) {} else if (name.isEmpty) name = parsed else extras += l.raw
                }
                "NICKNAME" -> {
                    val parts = splitUnescaped(decoded(l), ',').map { unescape(it).trim() }.filter { it.isNotEmpty() }
                    if (parts.isNotEmpty()) nickname = (if (nickname.isEmpty()) parts else listOf(nickname) + parts).joinToString(", ")
                }
                "ORG" -> {
                    val c = splitUnescaped(decoded(l), ';').map { unescape(it) }
                    val parsed = Organization(c.getOrElse(0) { "" }, c.getOrElse(1) { "" })
                    if (parsed.isEmpty) {} else if (org.isEmpty) org = parsed else extras += l.raw
                }
                "TITLE" -> text(l).let { if (it.isEmpty()) {} else if (title.isEmpty()) title = it else extras += l.raw }
                "BDAY" -> text(l).trim().let { if (it.isEmpty()) {} else if (birthday.isEmpty()) birthday = normalizeBirthday(it) else extras += l.raw }
                "NOTE" -> text(l).let { if (it.isEmpty()) {} else if (note.isEmpty()) note = it else extras += l.raw }
                "URL" -> text(l).let { if (it.isNotEmpty()) urls += it }
                "TEL" -> {
                    val value = text(l).let { if (it.startsWith("tel:", ignoreCase = true)) it.substring(4) else it }
                    if (value.isNotEmpty()) {
                        val (kind, custom) = resolveKind(label, l.types, ::telKind, TEL_KINDS)
                        phones += TypedValue(kind = kind, value = value, customLabel = custom)
                    }
                }
                "EMAIL" -> {
                    val value = text(l)
                    if (value.isNotEmpty()) {
                        val (kind, custom) = resolveKind(label, l.types, ::emailKind, EMAIL_KINDS)
                        emails += TypedValue(kind = kind, value = value, customLabel = custom)
                    }
                }
                "ADR" -> {
                    val c = splitUnescaped(decoded(l), ';').map { unescape(it) }
                    val (kind, custom) = resolveKind(label, l.types, ::adrKind, ADR_KINDS)
                    val adr = PostalAddress(
                        kind = kind, customLabel = custom,
                        poBox = c.getOrElse(0) { "" }, extended = c.getOrElse(1) { "" }, street = c.getOrElse(2) { "" },
                        locality = c.getOrElse(3) { "" }, region = c.getOrElse(4) { "" },
                        postalCode = c.getOrElse(5) { "" }, country = c.getOrElse(6) { "" },
                    )
                    if (adr.poBox.isNotEmpty() || adr.extended.isNotEmpty() || adr.street.isNotEmpty() || adr.locality.isNotEmpty() ||
                        adr.region.isNotEmpty() || adr.postalCode.isNotEmpty() || adr.country.isNotEmpty()
                    ) addresses += adr
                }
                "X-ABLABEL" -> if (l.group.isEmpty() || l.group !in knownGroups) extras += l.raw
                in BINARY -> {
                    val value = l.rawValue.trim()
                    val isUri = l.param("ENCODING") == null && !value.startsWith("data:", ignoreCase = true) && URI_SCHEME.matches(value)
                    if (isUri) extras += l.raw else dropped++
                }
                else -> extras += l.raw
            }
        }

        if (fn.isEmpty()) {
            fn = listOf(name.prefix, name.given, name.additional, name.family, name.suffix)
                .filter { it.isNotBlank() }.joinToString(" ") { it.trim() }
            if (fn.isEmpty()) fn = org.name
            if (fn.isEmpty()) fn = emails.firstOrNull()?.value ?: ""
            if (fn.isEmpty()) fn = phones.firstOrNull()?.value ?: ""
        }

        val contact = Contact(
            uid = uid, rev = rev, formattedName = fn, name = name, nickname = nickname, organization = org,
            title = title, birthday = birthday, phones = phones, emails = emails, addresses = addresses,
            urls = urls, note = note, extras = extras,
        )
        val isEmpty = contact.sameContentAs(Contact(uid = uid))
        return Pair(if (isEmpty) null else contact, dropped)
    }

    private val TEL_KINDS = Kind.values().toSet()
    private val EMAIL_KINDS = setOf(Kind.HOME, Kind.WORK, Kind.OTHER, Kind.MOBILE, Kind.CUSTOM)
    private val ADR_KINDS = setOf(Kind.HOME, Kind.WORK, Kind.OTHER, Kind.CUSTOM)

    /**
     * Gives the kind and the custom label. A group label wins over type parameters.
     * [fromTypes] returns null when the types name no kind.
     */
    private fun resolveKind(
        label: String?,
        types: List<String>,
        fromTypes: (List<String>) -> Pair<Kind, String>?,
        allowed: Set<Kind>,
    ): Pair<Kind, String> {
        val result = if (label != null) fromLabel(label) else fromTypes(types) ?: Pair(Kind.OTHER, "")
        return if (result.first in allowed) result else Pair(Kind.OTHER, "")
    }

    private fun fromLabel(label: String): Pair<Kind, String> {
        val inner = APPLE_LABEL.matchEntire(label)?.groupValues?.get(1) ?: return Pair(Kind.CUSTOM, label)
        val kind = when (inner.lowercase()) {
            "mobile" -> Kind.MOBILE
            "home" -> Kind.HOME
            "work" -> Kind.WORK
            "main" -> Kind.MAIN
            "homefax" -> Kind.FAX_HOME
            "workfax" -> Kind.FAX_WORK
            "otherfax" -> Kind.FAX_OTHER
            "pager" -> Kind.PAGER
            "other" -> Kind.OTHER
            else -> null
        }
        return if (kind != null) Pair(kind, "") else if (inner.isEmpty()) Pair(Kind.OTHER, "") else Pair(Kind.CUSTOM, inner)
    }

    private fun customToken(types: List<String>, known: Set<String>): String? =
        types.firstOrNull { it.length > 2 && it.startsWith("X-", ignoreCase = true) && it.uppercase() !in known }?.substring(2)

    private fun telKind(types: List<String>): Pair<Kind, String>? {
        val up = types.map { it.uppercase() }.toSet()
        return when {
            "FAX" in up -> when {
                "HOME" in up -> Pair(Kind.FAX_HOME, "")
                "OTHER" in up || "X-OTHER" in up -> Pair(Kind.FAX_OTHER, "")
                else -> Pair(Kind.FAX_WORK, "")
            }
            "PAGER" in up -> Pair(Kind.PAGER, "")
            "CELL" in up || "MOBILE" in up || "X-MOBILE" in up -> Pair(Kind.MOBILE, "")
            "MAIN" in up || "X-MAIN" in up -> Pair(Kind.MAIN, "")
            "HOME" in up -> Pair(Kind.HOME, "")
            "WORK" in up -> Pair(Kind.WORK, "")
            "OTHER" in up || "X-OTHER" in up -> Pair(Kind.OTHER, "")
            else -> customToken(types, setOf("X-MOBILE", "X-MAIN", "X-OTHER"))?.let { Pair(Kind.CUSTOM, it) }
                ?: if (up == setOf("PREF")) Pair(Kind.MAIN, "") else null
        }
    }

    private fun emailKind(types: List<String>): Pair<Kind, String>? {
        val up = types.map { it.uppercase() }.toSet()
        return when {
            "CELL" in up || "MOBILE" in up || "X-MOBILE" in up -> Pair(Kind.MOBILE, "")
            "HOME" in up -> Pair(Kind.HOME, "")
            "WORK" in up -> Pair(Kind.WORK, "")
            "OTHER" in up || "X-OTHER" in up -> Pair(Kind.OTHER, "")
            else -> customToken(types, setOf("X-MOBILE", "X-OTHER"))?.let { Pair(Kind.CUSTOM, it) }
        }
    }

    private fun adrKind(types: List<String>): Pair<Kind, String>? {
        val up = types.map { it.uppercase() }.toSet()
        return when {
            "HOME" in up -> Pair(Kind.HOME, "")
            "WORK" in up -> Pair(Kind.WORK, "")
            "OTHER" in up || "X-OTHER" in up -> Pair(Kind.OTHER, "")
            else -> customToken(types, setOf("X-OTHER"))?.let { Pair(Kind.CUSTOM, it) }
        }
    }

    internal fun normalizeBirthday(v: String): String {
        BASIC_DATE.matchEntire(v)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
        NO_YEAR.matchEntire(v)?.let { return "--${it.groupValues[1]}-${it.groupValues[2]}" }
        EXT_DATE.matchEntire(v)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
        return v
    }

    internal fun parseRev(v: String): Long {
        val m = BASIC_REV.matchEntire(v) ?: EXT_REV.matchEntire(v)
        if (m != null) {
            val g = m.groupValues.drop(1).map { it.toInt() }
            return runCatching {
                java.time.LocalDateTime.of(g[0], g[1], g[2], g[3], g[4], g[5]).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrDefault(0L)
        }
        runCatching { return Instant.parse(v).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(v).toInstant().toEpochMilli() }
        val d = BASIC_DATE.matchEntire(v) ?: EXT_DATE.matchEntire(v)
        if (d != null && d.groupValues[4].isEmpty()) {
            runCatching {
                return LocalDate.of(d.groupValues[1].toInt(), d.groupValues[2].toInt(), d.groupValues[3].toInt())
                    .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            }
        }
        return 0L
    }
}
