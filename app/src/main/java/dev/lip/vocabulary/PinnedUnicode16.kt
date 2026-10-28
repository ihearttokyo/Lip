package dev.lip.vocabulary

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Context-free, lazy Unicode16 tables. Never uses a newer OS table as Unicode16.
 * Java resources are packaged in the APK; missing/corrupt bytes fail closed.
 */
object PinnedUnicode16 : PortableVocabulary.Unicode {
    const val SHA256 = "e0a6fe7b32371dff4a582331bdd3591d2854ef7df9e1527fd0b5e8e669f1615c"
    private const val RESOURCE = "/dev/lip/vocabulary/unicode16.json"
    private const val MAX_TABLE_BYTES = 2 * 1024 * 1024
    private fun unavailable(): Nothing = throw VocabularyException("Pinned Unicode data unavailable; existing vocabulary was preserved")
    private val bundled: PortableVocabulary.Unicode by lazy {
        try {
            val bytes = PinnedUnicode16::class.java.getResourceAsStream(RESOURCE)?.use { it.readNBytes(MAX_TABLE_BYTES + 1) }
                ?: unavailable()
            Tables(bytes)
        } catch (_: Exception) { unavailable() }
    }
    /** Integrity controls use the same checked parser, never a fake compatibility adapter. */
    internal fun checked(bytes: ByteArray): PortableVocabulary.Unicode = Tables(bytes)
    override val version get() = bundled.version
    override fun nfc(value: String) = bundled.nfc(value)
    override fun fullCaseFold(value: String) = bundled.fullCaseFold(value)
    override fun category(codePoint: Int) = bundled.category(codePoint)

    private class Tables(bytes: ByteArray) : PortableVocabulary.Unicode {
        private val letters: Array<IntArray>
        private val others: Array<IntArray>
        private val folds: Map<Int, IntArray>
        private val classes: Map<Int, Int>
        private val decompositions: Map<Int, IntArray>
        private val compositions: Map<Long, Int>
        override val version = PortableVocabulary.UNICODE_VERSION
        init {
            try {
                if (bytes.size > MAX_TABLE_BYTES || MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) } != SHA256) unavailable()
                val table = JSONObject(bytes.toString(Charsets.UTF_8))
                if (table.getString("unicode_version") != version) unavailable()
                fun scalar(cp: Int) = cp in 0..0x10ffff && cp !in 0xd800..0xdfff
                fun ranges(rows: JSONArray): Array<IntArray> {
                    var last = -1
                    return Array(rows.length()) { index ->
                        val row = rows.getJSONArray(index)
                        if (row.length() != 2) unavailable()
                        val first = row.getInt(0); val end = row.getInt(1)
                        if (first <= last || first < 0 || first > end || end > 0x10ffff) unavailable()
                        last = end; intArrayOf(first, end)
                    }
                }
                fun mapping(rows: JSONObject): Map<Int, IntArray> = buildMap {
                    for (key in rows.keys()) {
                        val cp = key.toIntOrNull() ?: unavailable()
                        val row = rows.getJSONArray(key)
                        if (!scalar(cp) || row.length() !in 1..32 || containsKey(cp)) unavailable()
                        put(cp, IntArray(row.length()) { row.getInt(it).also { value -> if (!scalar(value)) unavailable() } })
                    }
                }
                letters = ranges(table.getJSONArray("letter_ranges")); others = ranges(table.getJSONArray("other_ranges"))
                folds = mapping(table.getJSONObject("casefold")); decompositions = mapping(table.getJSONObject("decomposition"))
                classes = buildMap {
                    val rows = table.getJSONObject("ccc")
                    for (key in rows.keys()) {
                        val cp = key.toIntOrNull() ?: unavailable(); val value = rows.getInt(key)
                        if (!scalar(cp) || value !in 1..255 || containsKey(cp)) unavailable()
                        put(cp, value)
                    }
                }
                compositions = buildMap {
                    val rows = table.getJSONObject("composition")
                    for (key in rows.keys()) {
                        val pair = key.split(','); if (pair.size != 2) unavailable()
                        val a = pair[0].toIntOrNull() ?: unavailable(); val b = pair[1].toIntOrNull() ?: unavailable()
                        val value = rows.getInt(key); val identity = pairKey(a, b)
                        if (!scalar(a) || !scalar(b) || !scalar(value) || containsKey(identity)) unavailable()
                        put(identity, value)
                    }
                }
            } catch (_: Exception) { unavailable() }
        }
        private fun contains(cp: Int, ranges: Array<IntArray>): Boolean {
            var lower = 0; var upper = ranges.size
            while (lower < upper) {
                val mid = lower + (upper - lower) / 2; val row = ranges[mid]
                if (cp < row[0]) upper = mid else if (cp > row[1]) lower = mid + 1 else return true
            }
            return false
        }
        override fun category(codePoint: Int): PortableVocabulary.Category = when {
            codePoint !in 0..0x10ffff -> PortableVocabulary.Category.OTHER
            contains(codePoint, letters) -> PortableVocabulary.Category.LETTER
            contains(codePoint, others) -> PortableVocabulary.Category.OTHER
            else -> PortableVocabulary.Category.NON_LETTER
        }
        private inline fun scalars(value: String, each: (Int) -> Unit) {
            var at = 0
            while (at < value.length) {
                val c = value[at++]
                val cp = if (c.isHighSurrogate()) {
                    if (at >= value.length || !value[at].isLowSurrogate()) invalid()
                    Character.toCodePoint(c, value[at++])
                } else {
                    if (c.isLowSurrogate()) invalid()
                    c.code
                }
                each(cp)
            }
        }
        override fun fullCaseFold(value: String): String = buildString {
            scalars(value) { cp ->
                val mapped = folds[cp]
                if (mapped == null) appendCodePoint(cp) else mapped.forEach { appendCodePoint(it) }
            }
        }
        override fun nfc(value: String): String {
            val ordered = ArrayList<Int>()
            fun appendOrdered(cp: Int) {
                if (ordered.size >= 8192) invalid()
                ordered.add(cp); val c = classes[cp] ?: 0
                if (c == 0) return
                var i = ordered.lastIndex
                while (i > 0 && (classes[ordered[i - 1]] ?: 0) > c) {
                    val before = ordered[i - 1]; ordered[i - 1] = ordered[i]; ordered[i] = before; i--
                }
            }
            fun decompose(cp: Int, depth: Int) {
                if (depth >= 32) unavailable()
                if (cp in 0xac00 until 0xac00 + 11172) {
                    val s = cp - 0xac00
                    appendOrdered(0x1100 + s / 588); appendOrdered(0x1161 + (s % 588) / 28)
                    if (s % 28 != 0) appendOrdered(0x11a7 + s % 28)
                } else {
                    val parts = decompositions[cp]
                    if (parts == null) appendOrdered(cp) else parts.forEach { decompose(it, depth + 1) }
                }
            }
            scalars(value) { decompose(it, 0) }
            if (ordered.isEmpty()) return ""
            val result = ArrayList<Int>(); result.add(ordered[0])
            var starter = ordered[0]; var starterIndex = 0; var lastClass = classes[starter] ?: 0
            fun composite(a: Int, b: Int): Int? {
                if (a in 0x1100 until 0x1100 + 19 && b in 0x1161 until 0x1161 + 21)
                    return 0xac00 + (a - 0x1100) * 588 + (b - 0x1161) * 28
                if (a in 0xac00 until 0xac00 + 11172 && (a - 0xac00) % 28 == 0 && b > 0x11a7 && b < 0x11a7 + 28)
                    return a + b - 0x11a7
                return compositions[pairKey(a, b)]
            }
            for (i in 1 until ordered.size) {
                val cp = ordered[i]; val c = classes[cp] ?: 0; val combined = composite(starter, cp)
                if (combined != null && (lastClass == 0 || lastClass < c)) {
                    result[starterIndex] = combined; starter = combined
                } else {
                    if (c == 0) { starterIndex = result.size; starter = cp }
                    result.add(cp); lastClass = c
                }
            }
            return buildString { result.forEach { appendCodePoint(it) } }
        }
    }
    private fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 21) or b.toLong()
    private fun invalid(): Nothing = throw VocabularyException("Invalid Unicode term")
}
