package dev.lip.vocabulary

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real hash-pinned Unicode16 tables, not an OS adapter or fake compatibility assertion. */
class PinnedUnicode16Test {
    @Test fun actualJavaResourceIsPinnedAndDefaultCodecUsesIt() {
        assertEquals("16.0.0", PinnedUnicode16.version)
        val codec = PortableVocabulary()
        val old = listOf(PortableVocabulary.Entry("Straße"), PortableVocabulary.Entry("cafe\u0301"), PortableVocabulary.Entry("\u1c89"))
        val decoded = codec.decode(codec.encode(old))
        assertEquals(listOf("café", "Straße", "\u1c89"), decoded.map { it.term })
        val merged = codec.merge(old, listOf(PortableVocabulary.Entry("STRASSE", weight=2), PortableVocabulary.Entry("\u1c8a", weight=3)))
        assertEquals(3, merged.size)
        assertEquals(2, merged.first { it.term == "Straße" }.weight)
        assertEquals(3, merged.first { it.term == "\u1c89" }.weight)
    }
    @Test fun corruptOrUnknownResourceNeverClaimsCompatibility() {
        val bytes = javaClass.getResourceAsStream("/dev/lip/vocabulary/unicode16.json")!!.use { it.readBytes() }
        assertEquals("16.0.0", PinnedUnicode16.checked(bytes).version)
        val bad = bytes.copyOf().also { it[0] = 0 }
        for (data in listOf(bad, ByteArray(0), ByteArray(2*1024*1024+1))) {
            val result = runCatching { PinnedUnicode16.checked(data) }
            assertTrue(result.exceptionOrNull() is VocabularyException)
            assertFalse(result.exceptionOrNull()!!.message!!.contains("Straße"))
        }
    }
    @Test fun categoriesUsePinnedDataNotOlderOrFutureOsTables() {
        assertEquals(PortableVocabulary.Category.LETTER, PinnedUnicode16.category(0x1c89))
        assertEquals(PortableVocabulary.Category.LETTER, PinnedUnicode16.category(0x10570))
        // Beria Erfe is outside UCD16; a newer OS cannot silently admit it.
        assertEquals(PortableVocabulary.Category.OTHER, PinnedUnicode16.category(0x16ea0))
        for (cp in listOf(0, 0x202e, 0xe000, 0xd800, 0x10ffff, -1, 0x110000))
            assertEquals(PortableVocabulary.Category.OTHER, PinnedUnicode16.category(cp))
        assertEquals(PortableVocabulary.Category.NON_LETTER, PinnedUnicode16.category(0x301))
    }
    @Test fun allIndependentPython16DifferentialCasesMatch() {
        val relative = "docs/contracts/learned-words/normalization-differential-v1.json"
        val source = System.getProperty("lip.vocabulary.vectors")?.let(::File)
            ?: listOf(File(relative), File("../$relative")).firstOrNull { it.isFile }
            ?: error("Shared synthetic Unicode oracle unavailable")
        require(source.length() in 1..4*1024*1024)
        val oracle = JSONObject(source.readText(Charsets.UTF_8))
        assertEquals("16.0.0", oracle.getString("unicode_version"))
        val rows = oracle.getJSONArray("cases")
        assertEquals(27_531, rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index); val input = row.getString("input")
            assertEquals("Pinned NFC case $index", row.getString("nfc"), PinnedUnicode16.nfc(input))
            assertEquals("Pinned fold case $index", row.getString("fold"), PinnedUnicode16.nfc(PinnedUnicode16.fullCaseFold(input)))
        }
    }
    @Test fun strictInvalidScalarsAndExpansionBounds() {
        for (bad in listOf("\ud800", "\udc00", "\ud800A")) {
            assertTrue(runCatching { PinnedUnicode16.nfc(bad) }.exceptionOrNull() is VocabularyException)
            assertTrue(runCatching { PinnedUnicode16.fullCaseFold(bad) }.exceptionOrNull() is VocabularyException)
        }
        assertTrue(runCatching { PinnedUnicode16.nfc("A".repeat(8193)) }.exceptionOrNull() is VocabularyException)
    }
}
