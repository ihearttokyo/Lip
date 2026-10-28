package dev.lip.speech

import java.nio.charset.CharacterCodingException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WhisperEngineTest {
    @Test fun identicalFinalInputReusesCompletedDecodeButAnyInputIdentityChangeDecodesAgain() {
        val native = NativeFixture()
        WhisperEngine("test-model", native).use { engine ->
            val capture = engine.borrow()
            val audio = floatArrayOf(0.25f, 0.5f)
            capture.transcribe(audio, "en", "terms", startSample = 16)
            val before = native.events.toList()
            assertEquals("hello", capture.transcribe(audio.copyOf(), "en", "terms", startSample = 16, reuseIdentical = true).single().text)
            assertEquals("Same EOF audio must not decode or reset native abort twice", before, native.events.toList())
            audio[0] = 0.3f
            capture.transcribe(audio, "en", "terms", startSample = 16, reuseIdentical = true)
            assertTrue("Retained PCM must not alias caller mutations", native.events.size > before.size)
            for (changed in listOf(
                { capture.transcribe(audio + 0.1f, "en", "terms", 16, true) },
                { capture.transcribe(audio + 0.1f, "ja", "terms", 16, true) },
                { capture.transcribe(audio + 0.1f, "ja", "other", 16, true) },
                { capture.transcribe(audio + 0.1f, "ja", "other", 17, true) }
            )) {
                val count = native.events.size
                changed()
                assertTrue("Changed samples, language, prompt or range must decode", native.events.size > count)
            }
            val count = native.events.size
            capture.transcribe(floatArrayOf(0.4f, 0.5f, 0.1f), "ja", "other", 17, true)
            assertTrue("Same-sized changed PCM must decode", native.events.size > count)
            capture.cancel()
        }
    }

    @Test fun completedDecodeCannotBeReusedAfterLeaseRetirementOrInAnotherCapture() {
        val native = NativeFixture()
        WhisperEngine("test-model", native).use { engine ->
            val first = engine.borrow()
            first.transcribe(floatArrayOf(0.25f), "en")
            first.cancel()
            val before = native.events.toList()
            assertTrue(runCatching { first.transcribe(floatArrayOf(0.25f), "en", reuseIdentical = true) }.exceptionOrNull() is CancellationException)
            assertEquals(before, native.events.toList())
            val next = engine.borrow()
            next.transcribe(floatArrayOf(0.25f), "en", reuseIdentical = true)
            assertTrue(native.events.size > before.size)
            next.cancel()
        }
    }

    @Test fun failedDecodeCannotLeaveAReusableResultAndClosedEngineRejectsReuse() {
        var failNext = false
        val native = NativeFixture { if (failNext) { failNext = false; error("fixture failure") } }
        val engine = WhisperEngine("test-model", native)
        val capture = engine.borrow()
        capture.transcribe(floatArrayOf(0.25f), "en")
        failNext = true
        assertTrue(runCatching { capture.transcribe(floatArrayOf(0.25f), "en") }.isFailure)
        val before = native.events.size
        capture.transcribe(floatArrayOf(0.25f), "en", reuseIdentical = true)
        assertTrue("Failed work must invalidate the previous reusable result", native.events.size > before)
        engine.close()
        assertTrue(runCatching { capture.transcribe(floatArrayOf(0.25f), "en", reuseIdentical = true) }.isFailure)
    }

    @Test fun canceledDelayedCaptureCannotResetAbortOrStartNonzeroInference() {
        val native = NativeFixture()
        WhisperEngine("test-model", native).use { engine ->
            val capture = engine.borrow() // Obtained before the capture worker is scheduled.
            capture.cancel()
            val before = native.events.toList()
            assertTrue("Canceled queued PCM must be refused before native admission",
                runCatching { capture.transcribe(floatArrayOf(0.25f), "en") }.exceptionOrNull() is CancellationException)
            assertEquals("No abort reset or inference after Cancel", before, native.events)
        }
    }

    @Test fun staleCaptureCancelCannotAbortNewCaptureAndFreshNonzeroStillWorks() {
        val native = NativeFixture()
        WhisperEngine("test-model", native).use { engine ->
            val old = engine.borrow()
            old.cancel()
            val fresh = engine.borrow()
            val before = native.events.toList()
            old.cancel()
            assertEquals("Retired capture cannot cancel the next borrower", before, native.events)
            assertEquals("hello", fresh.transcribe(floatArrayOf(0.25f), "en").single().text)
            fresh.cancel()
            engine.cancel()
            assertEquals("hello", engine.transcribe(floatArrayOf(-0.25f), "en").single().text)
        }
    }

    @Test fun activeCaptureCancelSignalsAbortAndBorrowWaitsForActualQuiescence() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Throwable?>()
        val native = NativeFixture { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        WhisperEngine("test-model", native).use { engine ->
            val capture = engine.borrow()
            val worker = Thread { result.set(runCatching { capture.transcribe(floatArrayOf(0.25f), "ja") }.exceptionOrNull()) }
            worker.start()
            try {
                assertTrue("Fixture must enter nonzero inference", entered.await(2, TimeUnit.SECONDS))
                capture.cancel()
                assertEquals("abort:true", native.events.last())
                assertTrue("A new capture must not borrow a still-active context",
                    runCatching { engine.borrow() }.exceptionOrNull() is IllegalStateException)
            } finally { release.countDown(); worker.join(2_000) }
            assertFalse("Inference worker must return", worker.isAlive)
            assertTrue(result.get() is CancellationException)
            val fresh = engine.borrow()
            assertEquals("hello", fresh.transcribe(floatArrayOf(0.25f), "zh").single().text)
            fresh.cancel()
        }
    }

    @Test fun normalCompletionCanRetireCaptureAndCloseRemainsTerminal() {
        val native = NativeFixture()
        val engine = WhisperEngine("test-model", native)
        val capture = engine.borrow()
        assertTrue(capture.transcribe(floatArrayOf(0f, -0f), "en").isEmpty())
        assertEquals("hello", capture.transcribe(floatArrayOf(0.25f), "en").single().text)
        capture.cancel() // Also required when LocalCapture has already delivered onEnd.
        val fresh = engine.borrow()
        fresh.cancel()
        engine.close(); engine.close()
        assertEquals(1, native.events.count { it == "close" })
        assertTrue(runCatching { engine.transcribe(floatArrayOf(0.25f), "en") }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { engine.borrow() }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun unissuedLeaseAndDirectCallsCannotBypassExclusiveCapture() {
        val native = NativeFixture()
        WhisperEngine("test-model", native).use { engine ->
            val capture = engine.borrow()
            val unissued = WhisperEngine.CaptureLease(engine)
            val before = native.events.toList()
            assertTrue(runCatching { engine.borrow() }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { engine.transcribe(floatArrayOf(0.25f), "en") }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { unissued.transcribe(floatArrayOf(0.25f), "en") }.exceptionOrNull() is CancellationException)
            unissued.cancel()
            assertEquals(before, native.events)
            assertEquals("hello", capture.transcribe(floatArrayOf(0.25f), "en").single().text)
            capture.cancel()
        }
    }

    @Test fun staleCancelDuringFreshInferenceDoesNotInvalidateItsResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Result<List<WhisperEngine.Segment>>>()
        val native = NativeFixture { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        WhisperEngine("test-model", native).use { engine ->
            val old = engine.borrow(); old.cancel()
            val fresh = engine.borrow()
            val worker = Thread { result.set(runCatching { fresh.transcribe(floatArrayOf(0.25f), "en") }) }
            worker.start()
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val before = native.events.toList()
                old.cancel()
                assertEquals("Stale Cancel must not signal abort on active new inference", before, native.events)
            } finally { release.countDown(); worker.join(2_000) }
            assertFalse(worker.isAlive)
            assertEquals("hello", result.get().getOrThrow().single().text)
            fresh.cancel()
        }
    }

    @Test fun nativeFailureReleasesInferenceAndValidationStillPrecedesZeroBypass() {
        var failNative = true
        val native = NativeFixture { if (failNative) { failNative = false; error("fixture failure") } }
        WhisperEngine("test-model", native).use { engine ->
            val capture = engine.borrow()
            assertTrue(runCatching { capture.transcribe(floatArrayOf(0.25f), "en") }.isFailure)
            capture.cancel()
            val fresh = engine.borrow()
            for ((pcm, language, prompt) in listOf(Triple(floatArrayOf(), "en", ""),
                Triple(floatArrayOf(0f), "xx", ""), Triple(floatArrayOf(0f), "en", "\u0000"))) {
                val before = native.events.toList()
                assertTrue(runCatching { fresh.transcribe(pcm, language, prompt) }.exceptionOrNull() is IllegalArgumentException)
                assertEquals(before, native.events)
            }
            assertTrue(fresh.transcribe(floatArrayOf(0f, -0f), "zh").isEmpty())
            assertEquals("hello", fresh.transcribe(floatArrayOf(0.25f), "ja").single().text)
            fresh.cancel()
        }
    }

    private class NativeFixture(private val infer: () -> Unit = {}) : WhisperEngine.NativeCalls {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun open(modelPath: ByteArray): Long = 1L
        override fun transcribe(handle: Long, pcm: FloatArray, language: ByteArray, prompt: ByteArray): Array<WhisperEngine.Segment> {
            events += "transcribe"
            infer()
            return arrayOf(WhisperEngine.Segment("hello".toByteArray(), 0, 0, 0f))
        }
        override fun abort(handle: Long, abort: Boolean) { events += "abort:$abort" }
        override fun close(handle: Long) { events += "close" }
    }

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
