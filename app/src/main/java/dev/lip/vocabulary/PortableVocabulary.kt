package dev.lip.vocabulary

import android.icu.lang.UCharacter
import android.icu.lang.UCharacterEnums.ECharacterCategory
import android.icu.text.Normalizer2
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Fixed messages only: never include a private term, JSON excerpt or provider filename. */
class VocabularyException internal constructor(message: String) : IllegalArgumentException(message)

/** Pure interchange codec. No files, providers, history access, learning or model changes. */
class PortableVocabulary(private val unicode: Unicode = AndroidUnicode16) {
    data class Entry(val term: String, val language: String = "en", val weight: Int = 1, val source: String = "manual")

    /** Injectable only to test parser/merge logic; compatibility requires real Unicode16 evidence. */
    interface Unicode {
        val version: String
        fun nfc(value: String): String
        fun fullCaseFold(value: String): String
        fun category(codePoint: Int): Category
    }
    enum class Category { LETTER, OTHER, NON_LETTER }

    companion object {
        const val FORMAT = "lip.learned-words"
        const val VERSION = 1
        const val UNICODE_VERSION = "16.0.0"
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_ENTRIES = 100_000
        private val languages = setOf("en", "ja", "zh", "es", "und")
        private val sources = setOf("manual", "gmail", "outlook", "whatsapp", "import")
        private fun requireValue(ok: Boolean, message: String) { if (!ok) throw VocabularyException(message) }
        private fun validScalars(value: String): Boolean {
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (c.isHighSurrogate()) {
                    if (i >= value.length || !value[i++].isLowSurrogate()) return false
                } else if (c.isLowSurrogate()) return false
            }
            return true
        }
        // CPython str.strip(): Unicode16 bidi WS/B/S or category Zs. ICU White_Space
        // alone misses U+001C..U+001F; JVM trim()/isWhitespace() are not this contract.
        private fun pythonSpace(cp: Int): Boolean = cp in 0x09..0x0d || cp in 0x1c..0x20 ||
            cp == 0x85 || cp == 0xa0 || cp == 0x1680 || cp in 0x2000..0x200a ||
            cp == 0x2028 || cp == 0x2029 || cp == 0x202f || cp == 0x205f || cp == 0x3000
        private fun strip(value: String): String {
            var first = 0
            var last = value.length
            while (first < last && pythonSpace(value.codePointAt(first))) first += Character.charCount(value.codePointAt(first))
            while (last > first && pythonSpace(value.codePointBefore(last))) last -= Character.charCount(value.codePointBefore(last))
            return value.substring(first, last)
        }
        private fun codePointCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val x = a.codePointAt(i)
                val y = b.codePointAt(j)
                if (x != y) return x.compareTo(y)
                i += Character.charCount(x)
                j += Character.charCount(y)
            }
            return (a.length - i).compareTo(b.length - j)
        }
    }

    private fun contract() = requireValue(unicode.version == UNICODE_VERSION,
        "Unsupported Unicode runtime; original file was not changed")

    private fun validated(entry: Entry): Entry {
        requireValue(validScalars(entry.term), "Invalid Unicode term")
        requireValue(entry.term.codePointCount(0, entry.term.length) <= 256, "Input term length is outside bounds")
        val term = unicode.nfc(strip(entry.term))
        requireValue(validScalars(term), "Invalid Unicode term")
        requireValue(term.codePointCount(0, term.length) in 1..128, "Term length is outside bounds")
        requireValue(term.toByteArray(Charsets.UTF_8).size <= 512, "Term byte length is outside bounds")
        var letter = false
        var i = 0
        while (i < term.length) {
            val cp = term.codePointAt(i)
            val category = unicode.category(cp)
            requireValue(category != Category.OTHER && cp != 13 && cp != 10 && cp != 9,
                "Control characters are not allowed")
            letter = letter || category == Category.LETTER
            i += Character.charCount(cp)
        }
        requireValue(letter, "Term must contain a letter")
        requireValue('@' !in term && "://" !in term && !term.regionMatches(0, "www.", 0, 4, ignoreCase = true),
            "Addresses and URLs require exclusion before import")
        requireValue(entry.language in languages, "Unsupported vocabulary language")
        requireValue(entry.weight in 1..1_000_000, "Invalid vocabulary weight")
        requireValue(entry.source in sources, "Unsupported source category")
        return entry.copy(term = term)
    }

    private data class Identity(val language: String, val folded: String)
    private fun identity(entry: Entry) = Identity(entry.language, unicode.nfc(unicode.fullCaseFold(entry.term)))

    fun decode(bytes: ByteArray): List<Entry> {
        requireValue(bytes.size <= MAX_BYTES, "Vocabulary file exceeds size limit")
        contract()
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw VocabularyException("Invalid vocabulary JSON") }
        return Parser(text).document()
    }

    /** Existing display spelling/source wins; max weight makes repeated imports idempotent. */
    fun merge(existing: Iterable<Entry>, incoming: Iterable<Entry>): List<Entry> {
        contract()
        val rows = LinkedHashMap<Identity, Entry>()
        var work = 0
        for (sequence in listOf(existing, incoming)) for (raw in sequence) {
            requireValue(work++ < MAX_ENTRIES * 2, "Vocabulary merge work exceeds limit")
            val entry = validated(raw)
            val key = identity(entry)
            val previous = rows[key]
            if (previous == null) rows[key] = entry
            else if (entry.weight > previous.weight) rows[key] = previous.copy(weight = entry.weight)
            requireValue(rows.size <= MAX_ENTRIES, "Merged vocabulary exceeds limit")
        }
        return rows.entries.sortedWith { a, b ->
            val language = codePointCompare(a.key.language, b.key.language)
            if (language != 0) language else codePointCompare(a.key.folded, b.key.folded)
        }.map { it.value }
    }

    /** Deterministic Python-reference-compatible UTF-8 JSON, including a final LF. */
    fun encode(entries: Iterable<Entry>): ByteArray {
        val rows = merge(emptyList(), entries)
        val output = StringBuilder()
        var utf8Bytes = 0
        fun append(value: String) {
            utf8Bytes += value.toByteArray(Charsets.UTF_8).size
            requireValue(utf8Bytes <= MAX_BYTES, "Encoded vocabulary exceeds size limit")
            output.append(value)
        }
        append("{\n  \"format\": \"$FORMAT\",\n  \"version\": $VERSION,\n  \"unicode_version\": \"$UNICODE_VERSION\",\n  \"entries\": [")
        if (rows.isNotEmpty()) append("\n")
        for ((index, row) in rows.withIndex()) {
            append("    {\n      \"term\": ${quote(row.term)},\n      \"language\": ${quote(row.language)},\n      \"weight\": ${row.weight},\n      \"source\": ${quote(row.source)}\n    }")
            append(if (index == rows.lastIndex) "\n" else ",\n")
        }
        append(if (rows.isEmpty()) "]\n}\n" else "  ]\n}\n")
        return output.toString().toByteArray(Charsets.UTF_8)
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> append(c) // Validated terms have no category C/control scalars.
        }
        append('"')
    }

    /** Schema-shaped parser: no arbitrary nested object/array allocation or recursive descent. */
    private inner class Parser(private val text: String) {
        private var at = 0
        private fun refuse(): Nothing = throw VocabularyException("Invalid vocabulary JSON")
        private fun whitespace() { while (at < text.length && text[at] in " \r\n\t") at++ }
        private fun take(c: Char): Boolean = if (at < text.length && text[at] == c) { at++; true } else false
        private fun expect(c: Char) { if (!take(c)) refuse() }
        private fun objectFields(consume: (String) -> Unit): Set<String> {
            whitespace(); expect('{'); whitespace()
            val keys = HashSet<String>()
            if (take('}')) return keys
            while (true) {
                whitespace()
                val key = string(64)
                requireValue(keys.add(key), "Duplicate JSON property")
                whitespace(); expect(':'); whitespace(); consume(key); whitespace()
                if (take('}')) return keys
                expect(',')
            }
        }
        fun document(): List<Entry> {
            var format: String? = null
            var version: Int? = null
            var unicodeVersion: String? = null
            var entries: List<Entry>? = null
            val fields = objectFields { key -> when (key) {
                "format" -> format = string(64)
                "version" -> version = integer()
                "unicode_version" -> unicodeVersion = string(64)
                "entries" -> entries = entries()
                else -> throw VocabularyException("Unknown vocabulary document fields")
            } }
            whitespace(); if (at != text.length) refuse()
            requireValue(fields == setOf("format", "version", "unicode_version", "entries"), "Unknown vocabulary document fields")
            requireValue(format == FORMAT && version == VERSION, "Unsupported vocabulary format/version")
            requireValue(unicodeVersion == UNICODE_VERSION, "Unsupported vocabulary Unicode contract")
            return entries ?: refuse()
        }
        private fun entries(): List<Entry> {
            expect('['); whitespace()
            val rows = ArrayList<Entry>()
            val seen = HashSet<Identity>()
            if (take(']')) return rows
            while (true) {
                requireValue(rows.size < MAX_ENTRIES, "Vocabulary entry count exceeds limit")
                var term: String? = null
                var language: String? = null
                var weight: Int? = null
                var source: String? = null
                val fields = objectFields { key -> when (key) {
                    "term" -> term = string(512)
                    "language" -> language = string(64)
                    "weight" -> weight = integer()
                    "source" -> source = string(64)
                    else -> throw VocabularyException("Invalid vocabulary entry fields")
                } }
                requireValue(fields == setOf("term", "language", "weight", "source"), "Invalid vocabulary entry fields")
                val entry = validated(Entry(term ?: refuse(), language ?: refuse(), weight ?: refuse(), source ?: refuse()))
                requireValue(seen.add(identity(entry)), "Duplicate normalized vocabulary entry")
                rows.add(entry)
                whitespace(); if (take(']')) return rows
                expect(','); whitespace()
            }
        }
        private fun integer(): Int {
            val start = at
            take('-')
            if (at >= text.length) refuse()
            if (take('0')) { if (at < text.length && text[at] in '0'..'9') refuse() }
            else {
                if (text[at] !in '1'..'9') refuse()
                while (at < text.length && text[at] in '0'..'9') {
                    if (at - start > 11) refuse()
                    at++
                }
            }
            if (at < text.length && text[at] in ".eE") refuse()
            return text.substring(start, at).toIntOrNull() ?: refuse()
        }
        private fun hex(): Char {
            if (at > text.length - 4) refuse()
            var number = 0
            repeat(4) {
                val c = text[at++]
                val digit = when (c) { in '0'..'9' -> c - '0'; in 'a'..'f' -> c - 'a' + 10; in 'A'..'F' -> c - 'A' + 10; else -> refuse() }
                number = (number shl 4) or digit
            }
            return number.toChar()
        }
        private fun string(limit: Int): String {
            expect('"')
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') {
                    if (!validScalars(out.toString())) refuse()
                    return out.toString()
                }
                if (c < ' ') refuse()
                if (c != '\\') out.append(c) else {
                    if (at >= text.length) refuse()
                    when (val escaped = text[at++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n')
                        'r' -> out.append('\r'); 't' -> out.append('\t'); 'u' -> out.append(hex())
                        else -> refuse()
                    }
                }
                if (out.length > limit) refuse()
            }
            refuse()
        }
    }
}

/** Real Android adapter. Runtime tables must exactly match the interchange contract. */
object AndroidUnicode16 : PortableVocabulary.Unicode {
    override val version: String get() {
        val v = UCharacter.getUnicodeVersion()
        return if (v.major == 16 && v.minor == 0 && v.milli == 0 && v.micro == 0) "16.0.0" else "unsupported"
    }
    override fun nfc(value: String): String = Normalizer2.getNFCInstance().normalize(value)
    override fun fullCaseFold(value: String): String = UCharacter.foldCase(value, true)
    override fun category(codePoint: Int): PortableVocabulary.Category = when (UCharacter.getType(codePoint)) {
        ECharacterCategory.UPPERCASE_LETTER.toInt(), ECharacterCategory.LOWERCASE_LETTER.toInt(),
        ECharacterCategory.TITLECASE_LETTER.toInt(), ECharacterCategory.MODIFIER_LETTER.toInt(),
        ECharacterCategory.OTHER_LETTER.toInt() -> PortableVocabulary.Category.LETTER
        ECharacterCategory.UNASSIGNED.toInt(), ECharacterCategory.CONTROL.toInt(),
        ECharacterCategory.FORMAT.toInt(), ECharacterCategory.PRIVATE_USE.toInt(),
        ECharacterCategory.SURROGATE.toInt() -> PortableVocabulary.Category.OTHER
        else -> PortableVocabulary.Category.NON_LETTER
    }
}
