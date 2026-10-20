package dev.drivecontacts.core

import dev.drivecontacts.core.model.Contact
import dev.drivecontacts.core.model.Kind
import dev.drivecontacts.core.model.Organization
import dev.drivecontacts.core.model.PostalAddress
import dev.drivecontacts.core.model.StructuredName
import dev.drivecontacts.core.model.TypedValue
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Lenient vCard 2.1, 3.0 and 4.0 reader. See docs/spec.md section 2.3.
 * It is a pure function: the same bytes always give the same contacts and UIDs.
 */
internal object VCardParser {
    private class Param(val name: String, val values: List<String>)

    /** The part of a logical line before the first colon outside quotes. */
    private class Head(val text: String, val group: String, val name: String, val params: List<Param>) {
        fun param(name: String): String? = params.firstOrNull { it.name == name }?.values?.firstOrNull()
        val types: List<String> get() = params.filter { it.name == "TYPE" }.flatMap { it.values }
        val quotedPrintable: Boolean = param("ENCODING").equals("QUOTED-PRINTABLE", ignoreCase = true)
    }

    /** One property line. [value] is decoded: quoted-printable and charset are already applied. */
    private class Line(val head: Head, val value: String) {
        val group: String get() = head.group
        val name: String get() = head.name
        val types: List<String> get() = head.types
        fun param(name: String): String? = head.param(name)
        val raw: String get() = extraLine(this)
    }

    private val KNOWN = setOf("FN", "N", "NICKNAME", "ORG", "TITLE", "BDAY", "TEL", "EMAIL", "ADR", "URL", "NOTE")
    private val BINARY = setOf("PHOTO", "LOGO", "SOUND", "KEY")
    private val SKIPPED = setOf("PRODID", "VERSION", "BEGIN", "END")
    private val ENCODING_WORDS = setOf("QUOTED-PRINTABLE", "BASE64", "B", "8BIT", "7BIT")
    private val APPLE_LABEL = Regex("^_\\\$!<(.*)>!\\\$_\$")
    private val BASIC_DATE = Regex("^(\\d{4})(\\d{2})(\\d{2})(T.*)?$")
    private val EXT_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(T.*)?$")
    private val NO_YEAR = Regex("^--(\\d{2})(\\d{2})$")
    private val BASIC_REV = Regex("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})(?:[.,]\\d+)?Z?$")
    private val EXT_REV = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:[.,]\\d+)?Z?$")
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val TAB = 9
    private const val LF = 10
    private const val CR = 13
    private const val SPACE = 32
    private const val QUOTE = 0x22
    private const val COLON = 0x3A
    private const val EQUALS = 0x3D

    /** A binary property is classified when its value has this many octets, or at the end of the line. */
    private const val BINARY_PROBE = 64

    fun parse(bytes: ByteArray): ParseResult {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFF && at(1) == 0xFE -> utf16(bytes, Charsets.UTF_16LE)
            at(0) == 0xFE && at(1) == 0xFF -> utf16(bytes, Charsets.UTF_16BE)
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> Session(bytes, honorCharset = true).run(3)
            else -> Session(bytes, honorCharset = true).run(0)
        }
    }

    // The text is Unicode already. A CHARSET parameter cannot apply to it.
    private fun utf16(bytes: ByteArray, charset: Charset): ParseResult =
        Session(String(bytes, 2, bytes.size - 2, charset).toByteArray(Charsets.UTF_8), honorCharset = false).run(0)

    /** Gives UIDs to cards. A card without UID, and each later card with a used UID, gets a UID from its content. */
    private class UidAllocator {
        private val used = HashSet<String>()
        private val derivedCount = HashMap<String, Int>()

        fun assign(uid: String, cardHash: ByteArray): String {
            if (uid.isNotEmpty() && used.add(uid)) return uid
            val key = String(cardHash, Charsets.ISO_8859_1)
            var n = derivedCount[key] ?: 0
            var derived: String
            do {
                derived = derive(cardHash, n)
                n++
            } while (!used.add(derived))
            derivedCount[key] = n
            return derived
        }

        // A name-based UUID (version 5 layout) from SHA-256 of the card hash and the occurrence number.
        private fun derive(cardHash: ByteArray, occurrence: Int): String {
            val sha = MessageDigest.getInstance("SHA-256")
            sha.update(cardHash)
            sha.update(byteArrayOf((occurrence ushr 24).toByte(), (occurrence ushr 16).toByte(), (occurrence ushr 8).toByte(), occurrence.toByte()))
            val h = sha.digest()
            h[6] = ((h[6].toInt() and 0x0F) or 0x50).toByte()
            h[8] = ((h[8].toInt() and 0x3F) or 0x80).toByte()
            val sb = StringBuilder("urn:uuid:")
            for (i in 0 until 16) {
                if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
                sb.append(HEX_DIGITS[(h[i].toInt() shr 4) and 15]).append(HEX_DIGITS[h[i].toInt() and 15])
            }
            return sb.toString()
        }
    }

    /**
     * One pass over the bytes. It cuts physical lines, joins folds and quoted-printable soft breaks into one
     * logical line in a reused buffer, and builds a contact at each END:VCARD.
     */
    private class Session(private val src: ByteArray, private val honorCharset: Boolean) {
        // The logical line in progress.
        private var buf = ByteArray(256)
        private var len = 0
        private var open = false
        private var colon = -1
        private var scanned = 0
        private var inQuote = false
        private var endsWithEquals = false
        private var head: Head? = null
        private var binaryChecked = false
        private var discard = false
        private var qpOpen = false

        // The card in progress.
        private var card: MutableList<Line>? = null
        private var depth = 0
        private var afterAgent = false

        // True when the last VERSION line of the card in progress is 4.0. vCard 4.0 has no quoted-printable and no CHARSET.
        private var version4 = false
        private val digest = MessageDigest.getInstance("SHA-256")

        private val contacts = ArrayList<Contact>()
        private val uids = UidAllocator()
        private var dropped = 0

        fun run(start: Int): ParseResult {
            val n = src.size
            var pos = start
            while (pos < n) {
                var end = pos
                while (end < n && src[end].toInt() != LF && src[end].toInt() != CR) end++
                physical(pos, end)
                pos = if (end + 1 < n && src[end].toInt() == CR && src[end + 1].toInt() == LF) end + 2 else end + 1
            }
            finishLine()
            finishCard()
            return ParseResult(contacts, dropped)
        }

        private fun physical(s: Int, e: Int) {
            if (open) {
                if (qpOpen) {
                    // A soft break does not run into an empty line or a card marker.
                    if (e > s && !isCardMarker(s, e)) {
                        if (!discard) len--
                        append(s, e)
                        return
                    }
                } else if (e > s && (src[s].toInt() == SPACE || src[s].toInt() == TAB)) {
                    append(s + 1, e)
                    return
                }
                finishLine()
            }
            if (e > s) {
                open = true
                len = 0
                colon = -1
                scanned = 0
                inQuote = false
                endsWithEquals = false
                head = null
                binaryChecked = false
                discard = false
                qpOpen = false
                append(s, e)
            }
        }

        private fun isCardMarker(s: Int, e: Int): Boolean {
            var end = e
            while (end > s && (src[end - 1].toInt() == SPACE || src[end - 1].toInt() == TAB)) end--
            return matches(src, s, end, "BEGIN:VCARD") || matches(src, s, end, "END:VCARD")
        }

        private fun append(from: Int, to: Int) {
            if (to > from) endsWithEquals = src[to - 1].toInt() == EQUALS
            if (!discard) {
                val n = to - from
                if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
                System.arraycopy(src, from, buf, len, n)
                len += n
                scan()
            }
            qpOpen = head?.quotedPrintable == true && endsWithEquals && !version4
        }

        // Looks at the new bytes only. The cost of a logical line stays linear in its length.
        private fun scan() {
            if (colon < 0) {
                var i = scanned
                while (i < len) {
                    val b = buf[i].toInt()
                    if (b == QUOTE) inQuote = !inQuote else if (b == COLON && !inQuote) {
                        colon = i
                        break
                    }
                    i++
                }
                scanned = i
                if (colon >= 0) head = parseHead(String(buf, 0, colon, Charsets.UTF_8))
            }
            val h = head
            if (h != null && !binaryChecked && h.name in BINARY && len - colon - 1 >= BINARY_PROBE) checkBinary(h)
        }

        // A binary value is dropped at once. Its text is never kept.
        private fun checkBinary(h: Head) {
            binaryChecked = true
            if (h.param("ENCODING") != null || !looksLikeUri(colon + 1, len)) discard = true
        }

        private fun looksLikeUri(from: Int, to: Int): Boolean {
            var i = from
            while (i < to && (buf[i].toInt() == SPACE || buf[i].toInt() == TAB)) i++
            if (startsWith(buf, i, to, "DATA:") || i >= to || !isLetter(buf[i].toInt())) return false
            i++
            while (i < to) {
                val b = buf[i].toInt()
                if (b == COLON) return true
                if (!isLetter(b) && b !in '0'.code..'9'.code && b != '+'.code && b != '.'.code && b != '-'.code) return false
                i++
            }
            return false
        }

        private fun finishLine() {
            if (!open) return
            open = false
            // A soft break at the end of a line has nothing to join.
            if (qpOpen && !discard) len--
            qpOpen = false
            val h = head ?: return
            if (h.name in BINARY && !binaryChecked) checkBinary(h)
            if ((h.name == "BEGIN" || h.name == "END") && isVcardWord(buf, colon + 1, len)) {
                marker(h.name == "BEGIN")
                return
            }
            val lines = card ?: return
            // A discarded line is hashed by its head only: its cut point depends on the physical line breaks.
            hash(headOnly = discard)
            if (h.name == "AGENT" && !discard && isVcardBegin(buf, colon + 1, len)) {
                // The vCard 2.1 form `AGENT:BEGIN:VCARD` opens a nested card on the same line.
                afterAgent = false
                depth++
                return
            }
            if (depth > 0) return
            if (discard) {
                dropped++
                afterAgent = false
                return
            }
            val value = decodeValue(h)
            if (h.name == "VERSION") version4 = value.trim() == "4.0"
            lines += Line(h, value)
            afterAgent = h.name == "AGENT"
        }

        // A BEGIN after an AGENT line opens a nested card (vCard 2.1). The nested lines are dropped.
        // Any other BEGIN ends the card in progress and starts a new one.
        private fun marker(begin: Boolean) {
            val lines = card
            if (begin) {
                if (lines != null && (depth > 0 || afterAgent)) {
                    if (depth == 0) lines.removeAt(lines.lastIndex)
                    afterAgent = false
                    hash()
                    depth++
                } else {
                    finishCard()
                    card = ArrayList()
                    version4 = false
                    digest.reset()
                }
            } else if (lines != null) {
                if (depth > 0) {
                    hash()
                    depth--
                } else {
                    finishCard()
                }
            }
        }

        private fun hash(headOnly: Boolean = false) {
            digest.update(buf, 0, if (headOnly) colon else len)
            digest.update(LF.toByte())
        }

        private fun finishCard() {
            val lines = card ?: return
            card = null
            depth = 0
            afterAgent = false
            val contact = build(lines) ?: return
            contacts += contact.copy(uid = uids.assign(contact.uid, digest.digest()))
        }

        private fun decodeValue(h: Head): String {
            val from = colon + 1
            // A quoted-printable value keeps its CHARSET in vCard 4.0 too: old files of this app hold such lines.
            if (h.quotedPrintable) {
                return decodeQuotedPrintable(buf, from, len, charsetOf(h)).replace("\r\n", "\n").replace('\r', '\n')
            }
            return String(buf, from, len - from, if (honorCharset && !version4) charsetOf(h) else Charsets.UTF_8)
        }
    }

    private fun charsetOf(head: Head): Charset =
        head.param("CHARSET")?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

    private fun isLetter(b: Int): Boolean = b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code

    private fun upper(b: Byte): Int = b.toInt().let { if (it in 'a'.code..'z'.code) it - 32 else it }

    private fun startsWith(b: ByteArray, from: Int, to: Int, upperPrefix: String): Boolean {
        if (to - from < upperPrefix.length) return false
        for (i in upperPrefix.indices) if (upper(b[from + i]) != upperPrefix[i].code) return false
        return true
    }

    private fun matches(b: ByteArray, from: Int, to: Int, upperWord: String): Boolean =
        to - from == upperWord.length && startsWith(b, from, to, upperWord)

    private fun trimmedRange(b: ByteArray, from: Int, to: Int): IntArray {
        var s = from
        var e = to
        while (s < e && (b[s].toInt() == SPACE || b[s].toInt() == TAB)) s++
        while (e > s && (b[e - 1].toInt() == SPACE || b[e - 1].toInt() == TAB)) e--
        return intArrayOf(s, e)
    }

    private fun isVcardWord(b: ByteArray, from: Int, to: Int): Boolean =
        trimmedRange(b, from, to).let { matches(b, it[0], it[1], "VCARD") }

    private fun isVcardBegin(b: ByteArray, from: Int, to: Int): Boolean =
        trimmedRange(b, from, to).let { matches(b, it[0], it[1], "BEGIN:VCARD") }

    private fun parseHead(text: String): Head? {
        val parts = splitOutsideQuotes(text, ';')
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
        return Head(text, group, name, params)
    }

    /**
     * The line as an extra: verbatim, but with no CHARSET and no quoted-printable encoding, because the
     * value is decoded text. A new line in the value is written as `\n`.
     */
    private fun extraLine(line: Line): String {
        val head = splitOutsideQuotes(line.head.text.trimStart(), ';').filterIndexed { i, part -> i == 0 || !isDecodedParam(part) }
        val value = if (line.value.indexOf('\n') >= 0) line.value.replace("\n", "\\n") else line.value
        return head.joinToString(";") + ":" + value
    }

    private fun isDecodedParam(part: String): Boolean {
        val eq = part.indexOf('=')
        if (eq < 0) return part.trim().equals("QUOTED-PRINTABLE", ignoreCase = true)
        val name = part.substring(0, eq).trim().uppercase()
        return name == "CHARSET" ||
            (name == "ENCODING" && part.substring(eq + 1).trim().removeSurrounding("\"").equals("QUOTED-PRINTABLE", ignoreCase = true))
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

    // Decodes the bytes in [from, to). Bytes that are not an escape stay as they are.
    private fun decodeQuotedPrintable(b: ByteArray, from: Int, to: Int, charset: Charset): String {
        val out = ByteArrayOutputStream(to - from)
        var i = from
        while (i < to) {
            val c = b[i].toInt()
            if (c == EQUALS && i + 2 < to && hex(b[i + 1]) >= 0 && hex(b[i + 2]) >= 0) {
                out.write(hex(b[i + 1]) * 16 + hex(b[i + 2]))
                i += 3
            } else {
                out.write(c)
                i++
            }
        }
        return String(out.toByteArray(), charset)
    }

    private fun hex(b: Byte): Int = when (val c = b.toInt()) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        else -> -1
    }

    private fun text(line: Line): String = unescape(line.value)

    private fun build(lines: List<Line>): Contact? {
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

        for (l in lines) {
            val label = labels[l.group]?.takeIf { it.isNotEmpty() }
            when (l.name) {
                in SKIPPED -> {}
                "UID" -> if (uid.isEmpty()) uid = text(l).trim()
                "REV" -> if (rev == 0L) rev = parseRev(text(l).trim())
                "FN" -> text(l).let { if (it.isEmpty()) {} else if (fn.isEmpty()) fn = it else extras += l.raw }
                "N" -> {
                    val c = splitUnescaped(l.value, ';').map { unescape(it) }
                    val parsed = StructuredName(c.getOrElse(0) { "" }, c.getOrElse(1) { "" }, c.getOrElse(2) { "" }, c.getOrElse(3) { "" }, c.getOrElse(4) { "" })
                    if (parsed.isEmpty) {} else if (name.isEmpty) name = parsed else extras += l.raw
                }
                "NICKNAME" -> {
                    val parts = splitUnescaped(l.value, ',').map { unescape(it).trim() }.filter { it.isNotEmpty() }
                    if (parts.isNotEmpty()) nickname = (if (nickname.isEmpty()) parts else listOf(nickname) + parts).joinToString(", ")
                }
                "ORG" -> {
                    val c = splitUnescaped(l.value, ';').map { unescape(it) }
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
                    val c = splitUnescaped(l.value, ';').map { unescape(it) }
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
                // A binary line is here only when its value is a URI. The session drops the others.
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
        // A card with a UID is a contact even without other content: parse(serialize(x)) must give x back.
        val isEmpty = uid.isEmpty() && contact.sameContentAs(Contact(uid = ""))
        return if (isEmpty) null else contact
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
