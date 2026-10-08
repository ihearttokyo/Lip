package dev.lip.speech

import org.junit.Assert.*
import org.junit.Test

/** Exercises real PCM retention/admission with supplied timestamps, not speech recognition quality. */
class LocalCaptureTest {
    @Test fun onlyObservedEndsWithFiveHundredMsLookaheadBecomeStable() {
        val pcm = window(2)
        val snapshot = pcm.snapshot()!!
        val admission = LocalCapture.Admission("en-US")
        val update = admission.admit(admission.generation, snapshot,
            listOf(segment("first", 0, 1_200), segment("second", 1_200, 1_800)))!!
        assertEquals(listOf("first"), update.stable)
        assertEquals("second", update.partial)
        assertFalse(update.ended)
        assertEquals(19_200L, pcm.snapshot()!!.startSample)
        assertEquals(12_800, pcm.snapshot()!!.samples.size)
    }

    @Test fun lookaheadHypothesisStaysUncommittedUntilMoreActualAudioExists() {
        val pcm = window(1)
        val admission = LocalCapture.Admission("en-US")
        val update = admission.admit(admission.generation, pcm.snapshot()!!, listOf(segment("pending", 0, 700)))!!
        assertTrue(update.stable.isEmpty())
        assertEquals("pending", update.partial)
        assertEquals(0L, pcm.snapshot()!!.startSample)
        assertEquals(16_000, pcm.snapshot()!!.samples.size)
    }

    @Test fun audioAppendedDuringDecodeSurvivesObservedBoundaryCommit() {
        val pcm = window(2, 1)
        val snapshot = pcm.snapshot()!!
        pcm.append(packet(16_000, 3))
        val admission = LocalCapture.Admission("en-US")
        admission.admit(admission.generation, snapshot, listOf(segment("first", 0, 1_000)))
        val retained = pcm.snapshot()!!
        assertEquals(16_000L, retained.startSample)
        assertEquals(32_000, retained.samples.size)
        assertEquals(3f / 32_768f, retained.samples.last(), 0f)
    }

    @Test fun lateNonfinalResultAfterFinishKeepsFullPcmForOneFinalDecode() {
        val pcm = window(2, 1)
        val inFlight = pcm.snapshot()!!
        pcm.append(packet(16_000, 3))
        val allAudio = pcm.snapshot()!!
        val admission = LocalCapture.Admission("en-US")
        val generation = admission.generation

        val late = admission.admit(generation, inFlight,
            listOf(segment("obsolete prefix", 0, 1_000)), finishRequested = true)!!
        assertTrue("A late preview cannot finalize words after Finish", late.stable.isEmpty())
        assertFalse(late.ended)
        val finalAudio = pcm.finish()!!
        assertEquals(0L, finalAudio.startSample)
        assertArrayEquals(allAudio.samples, finalAudio.samples, 0f)

        val final = admission.admit(generation, finalAudio,
            listOf(segment("complete final transcript", 0, 3_000)), final = true, finishRequested = true)!!
        assertEquals(listOf("complete final transcript"), final.stable)
        assertEquals("", final.partial)
        assertTrue(final.ended)
        assertNull(admission.admit(generation, finalAudio, emptyList(), final = true, finishRequested = true))
        assertNull(pcm.finish())
    }

    @Test fun lateEmptyResultAfterFinishCannotConsumeRetainedSilence() {
        val pcm = window(2, 0)
        val inFlight = pcm.snapshot()!!
        pcm.append(packet(16_000, 3))
        val allAudio = pcm.snapshot()!!
        val admission = LocalCapture.Admission("en-US")
        val late = admission.admit(admission.generation, inFlight, emptyList(), finishRequested = true)!!
        assertTrue(late.stable.isEmpty())
        assertFalse(late.ended)
        val finalAudio = pcm.finish()!!
        assertEquals("Finish retains even an exact-zero preview prefix", 0L, finalAudio.startSample)
        assertArrayEquals(allAudio.samples, finalAudio.samples, 0f)
    }

    @Test fun finishDrainsAcceptedRemainderExactlyOnceWithoutRepeatingCommittedWords() {
        val pcm = window(2)
        val admission = LocalCapture.Admission("en-US")
        val generation = admission.generation
        val first = admission.admit(generation, pcm.snapshot()!!,
            listOf(segment("first", 0, 1_000), segment("second", 1_000, 1_800)))!!
        val remainder = pcm.finish()!!
        assertEquals(16_000L, remainder.startSample)
        assertEquals(16_000, remainder.samples.size)
        val final = admission.admit(generation, remainder, listOf(segment("second", 0, 800)), final = true)!!
        assertEquals(listOf("first", "second"), first.stable + final.stable)
        assertEquals("", final.partial)
        assertTrue(final.ended)
        assertNull(admission.admit(generation, remainder, listOf(segment("second", 0, 800)), final = true))
        assertNull(pcm.finish())
    }

    @Test fun finishBeforeAnyPcmStillHasOnlyOneTerminalCallback() {
        val admission = LocalCapture.Admission("en-US")
        val generation = admission.generation
        val update = admission.admit(generation, null, emptyList(), final = true)!!
        assertTrue(update.ended)
        assertEquals("", update.partial)
        assertNull(admission.admit(generation, null, emptyList(), final = true))
    }

    @Test fun cancelFencesLateStablePartialAndFinalResultsWithoutAdvancingPcm() {
        val pcm = window(2)
        val admission = LocalCapture.Admission("en-US")
        val generation = admission.generation
        admission.cancel()
        assertFalse(admission.isCurrent(generation))
        assertNull(admission.admit(generation, pcm.snapshot()!!, listOf(segment("late", 0, 1_000))))
        assertNull(admission.admit(generation, pcm.snapshot()!!, listOf(segment("late", 0, 1_000)), final = true))
        assertEquals(0L, pcm.snapshot()!!.startSample)
    }

    @Test fun invalidOrOverlappingTimestampsCannotConsumeAnyPcm() {
        listOf(listOf(segment("bad", -1, 500)), listOf(segment("bad", 0, 2_001)),
            listOf(segment("first", 0, 1_200), segment("overlap", 1_100, 1_400)))
            .forEach { segments ->
                val pcm = window(2)
                val admission = LocalCapture.Admission("en-US")
                try {
                    admission.admit(admission.generation, pcm.snapshot()!!, segments)
                    fail("Invalid observed timestamps were accepted")
                } catch (_: IllegalArgumentException) { }
                assertEquals(0L, pcm.snapshot()!!.startSample)
                assertEquals(32_000, pcm.snapshot()!!.samples.size)
            }
    }

    @Test fun onlyExactZeroPcmAndEmptyActualAsrMayAdvanceAcrossSilence() {
        val silence = window(2, 0)
        val admission = LocalCapture.Admission("en-US")
        val update = admission.admit(admission.generation, silence.snapshot()!!, emptyList())!!
        assertTrue(update.stable.isEmpty())
        assertEquals("", update.partial)
        assertEquals(24_000L, silence.snapshot()!!.startSample)
        assertEquals(8_000, silence.snapshot()!!.samples.size)
        val noise = window(2, 1)
        admission.admit(admission.generation, noise.snapshot()!!, emptyList())
        assertEquals(0L, noise.snapshot()!!.startSample)
        assertEquals(32_000, noise.snapshot()!!.samples.size)
    }

    @Test fun intentionalRepeatedEnglishJapaneseAndMandarinSegmentsAreNotDeduplicated() {
        for ((language, word) in listOf("en-US" to "yes", "ja-JP" to "はい", "zh-CN" to "是")) {
            val pcm = window(2)
            val admission = LocalCapture.Admission(language)
            val update = admission.admit(admission.generation, pcm.snapshot()!!,
                listOf(segment(word, 0, 500), segment(word, 500, 1_000)))!!
            assertEquals(listOf(word, word), update.stable)
            assertEquals("", update.partial)
        }
    }

    @Test fun synchronousFirstStableCancellationStopsRemainingBatchCallbacks() {
        val pcm = window(2)
        val admission = LocalCapture.Admission("en-US")
        val generation = admission.generation
        val update = admission.admit(generation, pcm.finish(),
            listOf(segment("first", 0, 500), segment("second", 500, 1_000)), final = true)!!
        val delivered = mutableListOf<String>()
        admission.deliver(generation, update,
            { word -> delivered += "stable:$word"; admission.cancel() },
            { delivered += "partial:$it" },
            { delivered += "end" })
        assertEquals(listOf("stable:first"), delivered)
    }

    private fun window(seconds: Int, sample: Int = 1) = PcmWindow().apply { append(packet(seconds * 16_000, sample)) }
    private fun packet(samples: Int, sample: Int) = ByteArray(samples * 2).apply {
        for (index in indices step 2) { this[index] = sample.toByte(); this[index + 1] = (sample shr 8).toByte() }
    }
    private fun segment(text: String, start: Long, end: Long) = WhisperEngine.Segment(text.toByteArray(Charsets.UTF_8), start, end, 0f)
}
