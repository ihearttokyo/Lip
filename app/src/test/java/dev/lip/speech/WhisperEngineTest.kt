package dev.lip.speech

import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WhisperEngineTest {
    @Test fun exactZeroPcmAcceptsBothSignsOfZeroWithoutLoadingNativeLibrary() {
        for (pcm in listOf(floatArrayOf(0f), floatArrayOf(-0f), floatArrayOf(0f, -0f, 0f)))
            assertTrue(WhisperEngine.ExactZeroPcm.matches(pcm))
    }

    @Test fun exactZeroPcmRejectsEveryNonzeroAndNonfiniteSample() {
        for (sample in listOf(Float.MIN_VALUE, -Float.MIN_VALUE, 1f / 32768f, -1f / 32768f,
            1f, -1f, 1.01f, -1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (index in 0..2) {
                val pcm = floatArrayOf(0f, -0f, 0f).apply { this[index] = sample }
                assertFalse("Not exact zero: $sample at $index", WhisperEngine.ExactZeroPcm.matches(pcm))
            }
        }
    }

    @Test fun segmentPreservesCjkEmojiAndLineBreaksWithoutLoadingNativeLibrary() {
        val text = "你好\nこんにちは 🙂 café"
        assertEquals(text, WhisperEngine.Segment(text.toByteArray(Charsets.UTF_8), 0, 100, 0f).text)
    }

    @Test fun malformedUtf8FailsInsteadOfReplacingTranscriptBytes() {
        for (invalid in listOf(
            byteArrayOf(0xC3.toByte(), 0x28), // Invalid continuation.
            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x99.toByte()), // Truncated emoji.
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()), // Overlong encoding.
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), // UTF-16 surrogate.
        )) {
            try {
                WhisperEngine.Segment(invalid, 0, 100, 0f)
                fail("Malformed UTF-8 must not become a transcript")
            } catch (_: CharacterCodingException) { }
        }
    }
}
