package dev.lip

import dev.lip.vocabulary.PortableVocabulary
import org.junit.Assert.*
import org.junit.Test

/** Pure availability/merge policy, not storage/ICU/Keystore evidence. */
class PortableVocabularyTypingSnapshotTest {
    @Test fun unavailablePortableKeepsManualButExposesAutomaticCorrectionFence() {
        val snapshot = typingDictionarySnapshotOf(listOf("getUser", "Pianoforte"), null)
        assertEquals(listOf("getUser", "Pianoforte"), snapshot.words)
        assertFalse(snapshot.portableAvailable)
        assertFalse(snapshot.toString().contains("Pianoforte"))
    }
    @Test fun emptyPortableIsAvailableAndManualSpellingWinsAcrossUnion() {
        assertTrue(typingDictionarySnapshotOf(emptyList(), emptyList()).portableAvailable)
        val snapshot = typingDictionarySnapshotOf(listOf("getUser"), listOf(
            PortableVocabulary.Entry("GETUSER"), PortableVocabulary.Entry("Sampleton"),
            PortableVocabulary.Entry("Ada Rivers"), PortableVocabulary.Entry("東京", "ja")))
        assertEquals(listOf("getUser", "Sampleton"), snapshot.words)
        assertTrue(snapshot.portableAvailable)
    }
}
