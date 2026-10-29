package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class StickerPreferencesTest {
    @Test fun exactSmallSchemaRoundTripsEveryIndependentScale(){
        for(scale in KeyboardScale.entries){
            val encoded=StickerPreferences.encode(scale)
            assertEquals(setOf("version","previewScale"),encoded.keys)
            assertEquals(1,encoded["version"]);assertEquals(scale.name,encoded["previewScale"])
            assertEquals(scale,StickerPreferences.decode(encoded))
        }
    }
    @Test fun missingMalformedUnknownAndFutureFieldsDefaultToSystem(){
        for(values in listOf<Map<String,*>>(
            emptyMap<String,Any>(),mapOf("version" to 1),mapOf("previewScale" to "LARGE"),
            mapOf("version" to 2,"previewScale" to "LARGE"),
            mapOf("version" to "1","previewScale" to "LARGE"),
            mapOf("version" to 1L,"previewScale" to "LARGE"),
            mapOf("version" to true,"previewScale" to "LARGE"),
            mapOf("version" to 1,"previewScale" to "large"),
            mapOf("version" to 1,"previewScale" to "GIANT"),
            mapOf("version" to 1,"previewScale" to 1),
            mapOf("version" to 1,"previewScale" to null),
            mapOf("version" to 1,"previewScale" to "LARGE","extra" to "value")
        ))assertEquals(KeyboardScale.SYSTEM,StickerPreferences.decode(values))
    }
    @Test fun falseCommitMayMutateBackendButNeverConfirmedConsumers(){
        var backend=StickerPreferences.encode(KeyboardScale.SMALL)
        var succeeds=false
        val repo=StickerPreferenceRepository({backend}){scale->backend=StickerPreferences.encode(scale);succeeds}
        assertEquals(KeyboardScale.SMALL,repo.load())
        assertFalse(repo.save(KeyboardScale.EXTRA_LARGE))
        assertEquals(KeyboardScale.EXTRA_LARGE,StickerPreferences.decode(backend))
        assertEquals(KeyboardScale.SMALL,repo.load())
        val consumerA={repo.load()};val consumerB={repo.load()}
        assertEquals(KeyboardScale.SMALL,consumerA());assertEquals(consumerA(),consumerB())
        succeeds=true;assertTrue(repo.save(KeyboardScale.LARGE))
        assertEquals(KeyboardScale.LARGE,consumerA());assertEquals(KeyboardScale.LARGE,consumerB())
        assertEquals(KeyboardScale.LARGE,StickerPreferences.decode(backend))
    }
    @Test fun thrownWritePreservesConfirmationEvenAfterBackendMutation(){
        var backend=StickerPreferences.encode(KeyboardScale.LARGE)
        val repo=StickerPreferenceRepository({backend}){scale->backend=StickerPreferences.encode(scale);throw IllegalStateException("Synthetic storage failure")}
        assertFalse(repo.save(KeyboardScale.SMALL))
        assertEquals(KeyboardScale.SMALL,StickerPreferences.decode(backend));assertEquals(KeyboardScale.LARGE,repo.load())
    }
    @Test fun invalidOrUnavailableInitialBackendUsesSafeUiDefault(){
        assertEquals(KeyboardScale.SYSTEM,StickerPreferenceRepository({mapOf("version" to 9)}){true}.load())
        assertEquals(KeyboardScale.SYSTEM,StickerPreferenceRepository({throw IllegalStateException("Synthetic unavailable settings")}){true}.load())
    }
    @Test fun stickerSchemaCannotSilentlyConsumeOrChangeOldKeyboardChannels(){
        val old=KeyboardPreferences(hand=KeyboardHand.RIGHT,height=KeyboardHeight.COMPACT,theme=KeyboardTheme.MATTE,haptics=true,keyLabels=KeyboardScale.EXTRA_LARGE,emojiPreview=KeyboardScale.SMALL)
        val snapshot=old.encode()
        assertEquals(KeyboardScale.SYSTEM,StickerPreferences.decode(snapshot))
        assertEquals(KeyboardPreferences(),KeyboardPreferences.decode(StickerPreferences.encode(KeyboardScale.LARGE)))
        assertEquals(old,KeyboardPreferences.decode(snapshot))
        assertEquals(setOf("version","hand","height","theme","haptics","keyLabels","emojiPreview","heightPercent"),snapshot.keys)
        assertEquals(3,snapshot["version"])
        // Retain the exact seven-key v2 contract as an explicit migration/control input.
        val legacyV2=mapOf("version" to 2,"hand" to "RIGHT","height" to "COMPACT","theme" to "MATTE","haptics" to true,
            "keyLabels" to "EXTRA_LARGE","emojiPreview" to "SMALL")
        assertEquals(setOf("version","hand","height","theme","haptics","keyLabels","emojiPreview"),legacyV2.keys)
        assertEquals(old,KeyboardPreferences.decode(legacyV2))
        assertEquals(90,KeyboardPreferences.decode(legacyV2).heightPercent)
        assertEquals(KeyboardScale.SYSTEM,StickerPreferences.decode(legacyV2))
    }
}
