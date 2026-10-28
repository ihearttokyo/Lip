package dev.lip.vocabulary

import org.junit.Assert.*
import org.junit.Test
import java.text.Normalizer
import java.util.Locale

/** Synthetic JVM codec tests only; this fixture is NOT Android ICU/Unicode16 proof. */
class PortableVocabularyTest {
    private object FixtureUnicode : PortableVocabulary.Unicode {
        override val version = "16.0.0"
        override fun nfc(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC)
        override fun fullCaseFold(value: String) = value.lowercase(Locale.ROOT).replace("ß", "ss").replace("ς", "σ")
        override fun category(codePoint: Int) = when {
            Character.isLetter(codePoint) -> PortableVocabulary.Category.LETTER
            Character.getType(codePoint) in setOf(Character.UNASSIGNED.toInt(), Character.CONTROL.toInt(),
                Character.FORMAT.toInt(), Character.PRIVATE_USE.toInt(), Character.SURROGATE.toInt()) -> PortableVocabulary.Category.OTHER
            else -> PortableVocabulary.Category.NON_LETTER
        }
    }
    private val codec = PortableVocabulary(FixtureUnicode)
    private fun row(term: String = "Ada", weight: String = "1", extra: String = "") =
        """{"term":"$term","language":"en","weight":$weight,"source":"manual"$extra}"""
    private fun document(rows: String = row(), header: String = "") =
        """{"format":"lip.learned-words","version":1,"unicode_version":"16.0.0","entries":[$rows]$header}"""
    private fun decode(value: String) = codec.decode(value.toByteArray(Charsets.UTF_8))
    private fun refuse(block: () -> Unit): VocabularyException {
        try { block() } catch (failure: VocabularyException) { return failure }
        throw AssertionError("Expected a fixed-message vocabulary refusal")
    }

    @Test fun displayNormalizationAndFullFoldCollisionAreSeparate() {
        assertEquals("café", decode(document(row("  cafe\\u0301  "))).single().term)
        refuse { decode(document(row("Straße") + "," + row("STRASSE"))) }
        val merged = codec.merge(listOf(PortableVocabulary.Entry("Straße", source = "manual")),
            listOf(PortableVocabulary.Entry("STRASSE", weight = 7, source = "gmail")))
        assertEquals(listOf(PortableVocabulary.Entry("Straße", weight = 7, source = "manual")), merged)
        assertEquals(merged, codec.merge(merged, merged))
    }
    @Test fun languageIsPartOfIdentityAndOrderingIsDeterministic() {
        val words = listOf(PortableVocabulary.Entry("Zed", "ja"), PortableVocabulary.Entry("beta"),
            PortableVocabulary.Entry("Alpha"), PortableVocabulary.Entry("Alpha", "es"))
        val result = codec.merge(emptyList(), words)
        assertEquals(listOf("en:Alpha", "en:beta", "es:Alpha", "ja:Zed"), result.map { "${it.language}:${it.term}" })
        assertArrayEquals(codec.encode(words), codec.encode(words.reversed()))
    }
    @Test fun sortingUsesCodePointsNotUtf16CodeUnits() {
        val supplementaryLetter = String(Character.toChars(0x10400))
        val bmpLetter = "\uff21"
        assertEquals(listOf(bmpLetter, supplementaryLetter), codec.merge(emptyList(),
            listOf(PortableVocabulary.Entry(supplementaryLetter), PortableVocabulary.Entry(bmpLetter))).map { it.term })
    }
    @Test fun pythonStripIncludesC0SeparatorsAndNbspButNotZeroWidth() {
        assertEquals("Ada", decode(document(row("\\u001c\\u001f\\u0085\\u00a0Ada\\u3000"))).single().term)
        refuse { decode(document(row("Ada\\u001cRivers"))) }
        refuse { decode(document(row("\\u200bAda"))) }
    }
    @Test fun strictDuplicateKeysIncludeEscapedEquivalentKeys() {
        refuse { decode(document(header = ",\"version\":1")) }
        refuse { decode(document(row(extra = ",\"term\":\"Other\""))) }
        refuse { decode(document(row(extra = ",\"te\\u0072m\":\"Other\""))) }
    }
    @Test fun exactRequiredFieldsAndDocumentVersion() {
        for (value in listOf(document(header = ",\"extra\":null"), document().replace("\"version\":1,", ""),
            document().replace("\"version\":1", "\"version\":2"),
            document().replace("16.0.0", "15.0.0"), document().replace("lip.learned-words", "other"),
            document(row(extra = ",\"other\":1")), document().replace(",\"source\":\"manual\"", ""))) {
            refuse { decode(value) }
        }
    }
    @Test fun jsonIntegersAreStrictNeverBooleansDecimalsOrExponents() {
        for (weight in listOf("true", "false", "null", "1.0", "1e0", "NaN", "Infinity", "01", "+1", "0", "-1", "1000001", "999999999999999999999999")) {
            refuse { decode(document(row(weight = weight))) }
        }
        refuse { decode(document().replace("\"version\":1", "\"version\":true")) }
        assertEquals(1_000_000, decode(document(row(weight = "1000000"))).single().weight)
    }
    @Test fun malformedJsonAndNestedShapesFailWithoutRecursiveAllocation() {
        for (value in listOf("[]", "null", document() + "true", document().dropLast(1),
            document().replace("[${row()}]", "[[[[]]]]"), document(row() + ","),
            document().replace("\"source\":\"manual\"", "\"source\":{}"), "[".repeat(10000))) {
            refuse { decode(value) }
        }
    }
    @Test fun malformedUtf8BomAndUnpairedSurrogatesFail() {
        refuse { codec.decode(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }
        refuse { decode("\ufeff" + document()) }
        for (term in listOf("\\ud800", "\\udc00", "\\ud800Ada", "\\udc00\\ud800")) refuse { decode(document(row(term))) }
        refuse { codec.encode(listOf(PortableVocabulary.Entry("\ud800"))) }
        val supplementary = String(Character.toChars(0x10400))
        assertEquals(supplementary, decode(document(row("\\ud801\\udc00"))).single().term)
    }
    @Test fun termsRejectCategoryCOddControlsNoLetterAndAddresses() {
        for (term in listOf("", "123456", "abc\\u202e", "a@example.com", "https://example.com", "WWW.example.com",
            "Ada\\u0000", "Ada\\tRiver", "Ada\\nRiver", "Ada\\ue000", "Ada\\u0378")) {
            val failure = refuse { decode(document(row(term))) }
            if (term.isNotEmpty()) assertFalse(failure.message.orEmpty().contains(term))
        }
        assertEquals("Ada Rivers", decode(document(row("Ada Rivers"))).single().term)
    }
    @Test fun codepointNotUtf16TermLimitsAndPretrimLimit() {
        val letter = String(Character.toChars(0x10400))
        assertEquals(128, decode(document(row(letter.repeat(128)))).single().term.codePointCount(0, 256))
        refuse { decode(document(row(letter.repeat(129)))) }
        refuse { decode(document(row(" ".repeat(256) + "A"))) }
        assertEquals("A", decode(document(row(" ".repeat(255) + "A"))).single().term)
    }
    @Test fun languageSourceAndConstructedEntriesAreAlwaysValidated() {
        for (entry in listOf(PortableVocabulary.Entry("Ada", language = "fr"), PortableVocabulary.Entry("Ada", weight = 0),
            PortableVocabulary.Entry("Ada", source = "mailbox-id"), PortableVocabulary.Entry("123"))) {
            refuse { codec.merge(emptyList(), listOf(entry)) }
            refuse { codec.encode(listOf(entry)) }
        }
        refuse { decode(document().replace("\"language\":\"en\"", "\"language\":null")) }
    }
    @Test fun encodingMatchesPythonPrettyJsonAndRoundTripsEscapes() {
        val expected = """
            {
              "format": "lip.learned-words",
              "version": 1,
              "unicode_version": "16.0.0",
              "entries": [
                {
                  "term": "Ada",
                  "language": "en",
                  "weight": 1,
                  "source": "manual"
                }
              ]
            }
        """.trimIndent() + "\n"
        assertEquals(expected, codec.encode(listOf(PortableVocabulary.Entry("Ada"))).toString(Charsets.UTF_8))
        val quoted = PortableVocabulary.Entry("Ada \"River\" \\ path")
        assertEquals(listOf(quoted), codec.decode(codec.encode(listOf(quoted))))
        assertEquals("{\n  \"format\": \"lip.learned-words\",\n  \"version\": 1,\n  \"unicode_version\": \"16.0.0\",\n  \"entries\": []\n}\n", codec.encode(emptyList()).toString(Charsets.UTF_8))
    }
    @Test fun runtimeVersionMismatchRefusesBeforeAnyNormalization() {
        val incompatible = PortableVocabulary(object : PortableVocabulary.Unicode {
            override val version = "15.0.0"
            override fun nfc(value: String): String = error("must not normalize")
            override fun fullCaseFold(value: String): String = error("must not fold")
            override fun category(codePoint: Int): PortableVocabulary.Category = error("must not classify")
        })
        refuse { incompatible.decode(document().toByteArray()) }
        refuse { incompatible.merge(emptyList(), emptyList()) }
        refuse { incompatible.encode(emptyList()) }
    }
    @Test fun fileAndEntryWorkLimitsAreEnforced() {
        refuse { codec.decode(ByteArray(PortableVocabulary.MAX_BYTES + 1)) }
        val repeated = Iterable { generateSequence { PortableVocabulary.Entry("Ada") }.iterator() }
        refuse { codec.merge(emptyList(), repeated) }
        val tooMany = document(List(PortableVocabulary.MAX_ENTRIES + 1) { row("Term$it") }.joinToString(","))
        refuse { decode(tooMany) }
    }
    @Test fun encodedByteBudgetRefusesRatherThanReturningOversizeOutput() {
        val rows = (0 until PortableVocabulary.MAX_ENTRIES).map { PortableVocabulary.Entry("A".repeat(110) + it) }
        refuse { codec.encode(rows) }
    }
}
