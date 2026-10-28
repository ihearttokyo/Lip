package dev.lip.keyboard

import dev.lip.vocabulary.PortableVocabulary
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PersonalSwipeLexiconTest {
    private fun base(word: String, frequency: Int = 71, display: String = word, offensive: Boolean = false) = SwipeLexeme(word, frequency, display, offensive)
    private fun entry(term: String, weight: Int = 1, language: String = "en") = PortableVocabulary.Entry(term, language, weight, "import")
    private fun key(prefix: Char, value: Int): String = buildString {
        append(prefix)
        var n = value
        repeat(5) { append('a' + n % 26); n /= 26 }
    }
    private fun build(base: List<SwipeLexeme> = listOf(base("hello")), manual: List<String> = emptyList(), portable: List<PortableVocabulary.Entry> = emptyList()) =
        PersonalSwipeLexicon.build(base, manual, portable)

    @Test fun allBaseKeysFrequenciesAndFlagsSurvivePersonalDisplayOverride() {
        val base = listOf(base("hello", 0, "hello"), base("world", 255, "World"), base("medical", 19, offensive = true))
        val union = build(base, listOf("HELLO"), listOf(entry("WORLD", 1_000_000), entry("newword", 1_000_000)))
        assertEquals(base.map { it.word }, union.entries.take(base.size).map { it.word })
        assertEquals(base.map { it.frequency }, union.entries.take(base.size).map { it.frequency })
        assertEquals(base.map { it.offensive }, union.entries.take(base.size).map { it.offensive })
        assertEquals("HELLO", union.display("hello")); assertEquals("WORLD", union.display("world"))
        assertEquals("medical", union.display("medical")); assertNull(union.display("missing"))
        assertEquals(4, union.count); assertEquals(3, union.eligibleCount); assertEquals(1, union.addedCount)
        assertEquals(0, union.unsupportedCount)
    }
    @Test fun manualFirstSpellingAndFirstPortableSpellingWinButWeightsUseMaximum() {
        val union = build(manual = listOf("Jaredium", "JAREDIUM"), portable = listOf(entry("JAREDIUM", 1000), entry("Otherium", 2), entry("OTHERIUM", 100)))
        assertEquals("Jaredium", union.display("jaredium")); assertEquals("Otherium", union.display("otherium"))
        assertEquals(200, union.entries.single { it.word == "jaredium" }.frequency) // floor(log2(1000))=9
        assertEquals(176, union.entries.single { it.word == "otherium" }.frequency)
        assertEquals(2, union.eligibleCount); assertEquals(2, union.addedCount)
    }
    @Test fun prospectiveFrequencyPolicyIsExactAtLogAndSaturationBoundaries() {
        val weights = listOf(1, 2, 3, 4, 7, 8, 32767, 32768, 65535, 65536, 1_000_000)
        val expected = listOf(128, 136, 136, 144, 144, 152, 240, 248, 248, 255, 255)
        val union = build(portable = weights.mapIndexed { i, weight -> entry(key('p', i), weight) })
        for (i in weights.indices) assertEquals(expected[i], union.entries.single { it.word == key('p', i) }.frequency)
        assertEquals(128, build(manual = listOf("Manualium")).entries.single { it.word == "manualium" }.frequency)
    }
    @Test fun deterministicOovOrderAndRepeatedReimportsAreIdempotent() {
        val entries = listOf(entry("Zed", 3), entry("Alpha", 8), entry("ALPHA", 2))
        val once = build(portable = entries)
        val twice = build(portable = entries + entries)
        assertEquals(once.entries, twice.entries)
        assertEquals(listOf("hello", "alpha", "zed"), once.entries.map { it.word })
        assertEquals(once.entries, build(portable = listOf(entries[1], entries[2], entries[0])).entries)
    }
    @Test fun unsupportedStoredRecordsAreCountedNotSplitTranslatedTrimmedOrTruncated() {
        val manual = listOf("two words", "école", "has-digit1", "'lead", "trail'", "two''quotes", "a".repeat(49), "ab".repeat(20) + "a", " leading")
        val portable = listOf(entry("東京", language = "ja"), entry("hola", language = "es"), entry("plain", language = "zh"), entry("SavedPhrase two"), entry("Supported", language = "und"))
        val preserved = portable.toList()
        val union = build(manual = manual, portable = portable)
        assertEquals(manual.size + 4, union.unsupportedCount)
        assertEquals(1, union.eligibleCount); assertEquals(1, union.addedCount)
        assertEquals(preserved, portable); assertEquals("Supported", union.display("supported"))
        assertEquals(2, union.count)
    }
    @Test fun apostropheGrammarAndCollapsed40KeyBoundaryMatchNativeGate() {
        val good = listOf("Don't", "a'a", "a".repeat(48), "ab".repeat(20))
        val union = build(manual = good)
        assertEquals(good.size, union.eligibleCount)
        SwipeInputBounds.lexicon(union.entries.map { it.word }, IntArray(union.count) { union.entries[it].frequency })
        assertEquals(1, build(manual = listOf("ab".repeat(20) + "a")).unsupportedCount)
        assertEquals("Don't", union.display("don't"))
    }
    @Test fun overSourceCapsFailInsteadOfKeepingSomeRecords() {
        assertThrows(IllegalArgumentException::class.java) { build(manual = List(201) { "Ada" }) }
        assertThrows(IllegalArgumentException::class.java) { build(portable = List(100_001) { entry("Ada") }) }
        assertThrows(IllegalArgumentException::class.java) { build(base = List(250_001) { base(key('b', it)) }) }
        for (weight in listOf(0, -1, 1_000_001)) assertThrows(IllegalArgumentException::class.java) { build(portable = listOf(entry("Unsupported phrase", weight))) }
    }
    @Test fun malformedBaseIsRefusedNeverRepairedDroppedOrReweighted() {
        for (rows in listOf(listOf(base("Duplicate")), listOf(base("same"), base("same")), listOf(base("toolong".repeat(8))), listOf(base("a", -1)), listOf(base("ab".repeat(20) + "a")))) {
            assertThrows(IllegalArgumentException::class.java) { build(base = rows, manual = listOf("good")) }
        }
    }
    @Test fun fullSourceCapacityUnionContainsEveryBaseAnd100kPortableAnd200Manual() {
        val base = List(250_000) { base(key('b', it), it % 256) }
        val portable = List(100_000) { entry(key('p', it), 1 + it) }
        val manual = List(200) { key('m', it) }
        val union = build(base, manual, portable)
        assertEquals(350_200, union.count); assertEquals(100_200, union.eligibleCount); assertEquals(100_200, union.addedCount)
        assertEquals(base, union.entries.take(base.size))
        val words = union.entries.map { it.word }.toSet()
        assertTrue(manual.all { it in words }); assertTrue(portable.all { it.term in words })
        assertEquals(0, union.unsupportedCount)
        assertThrows(UnsupportedOperationException::class.java) { (union.entries as MutableList).clear() }
    }
    @Test fun unionBoundsAccept350200AndReject350201Or351kWithoutChangingBaseSourceCap() {
        val words = List(SwipeInputBounds.MAX_LEXICON_ENTRIES) { key('u', it) }
        SwipeInputBounds.lexicon(words, IntArray(words.size) { 255 })
        assertThrows(IllegalArgumentException::class.java) { SwipeInputBounds.lexicon(words + "extra", IntArray(words.size + 1)) }
        assertThrows(IllegalArgumentException::class.java) { SwipeInputBounds.lexicon(List(351_000) { key('u', it) }, IntArray(351_000)) }
        assertEquals(250_000, SwipeAssetCatalog.MAX_ENTRIES)
    }
    @Test fun actualPinnedBaseAssetIsUnchangedAndEveryAdmittedRowSurvives() {
        val path = listOf(File("app/src/main/assets/swipe/en.tsv"), File("src/main/assets/swipe/en.tsv")).firstOrNull { it.isFile }
        assertNotNull("Pinned base asset must be present", path)
        val bytes = path!!.readBytes()
        val source = SwipeLexiconData.verified(bytes).admitted()
        val union = build(source, listOf("Jaredium"), listOf(entry("Newlyapprovedium", 99)))
        assertEquals(source.map { it.word }, union.entries.take(source.size).map { it.word })
        assertEquals(source.map { it.frequency }, union.entries.take(source.size).map { it.frequency })
        assertEquals(source.map { it.offensive }, union.entries.take(source.size).map { it.offensive })
        assertEquals(SwipeAssetCatalog.DICTIONARY_SHA256, SwipeAssetCatalog.sha256(bytes))
    }
}
