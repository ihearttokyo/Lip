package dev.lip.core

import org.junit.Assert.*
import org.junit.Test

class CaptureSessionTest {
    @Test fun pausesDoNotFinishAndHypothesesReplaceOnlyCurrentSegment() {
        val capture = CaptureSession("en-US")
        capture.partial("Meet at four")
        capture.partial("Meet at four actually")
        assertEquals("Meet at four actually", capture.transcript)
        capture.segment("Meet at four actually three.")
        assertFalse(capture.ended)
        capture.partial("Bring the")
        capture.partial("Bring the notes")
        assertEquals("Meet at four actually three. Bring the notes", capture.transcript)
        capture.segment("Bring the notes.")
        capture.stop()
        assertTrue(capture.finishing)
        assertEquals("Meet at four actually three. Bring the notes.", capture.complete())
        capture.segment("LATE")
        assertEquals("Meet at four actually three. Bring the notes.", capture.transcript)
    }
    @Test fun segmentBoundariesPreserveIntentionalLineBreaks() {
        val capture = CaptureSession("en-US")
        capture.segment("first\n"); capture.segment("second")
        capture.segment("\n\n"); capture.partial("third")
        assertEquals("first\nsecond\n\nthird", capture.complete())
    }
    @Test fun cjkSessionsDoNotMergeSeparateAsciiIdentifiers() {
        for (language in listOf("ja-JP", "zh-CN")) {
            val capture = CaptureSession(language)
            capture.segment("getUser"); capture.segment("account_id")
            assertEquals("getUser account_id", capture.complete())
        }
    }
    @Test fun multilingualSegmentsAndIntentionalRepetitionAreRetained() {
        for ((language, expected) in listOf("en-US" to "yes yes", "ja-JP" to "はいはい", "zh-CN" to "是是")) {
            val capture = CaptureSession(language)
            val word = when(language) { "ja-JP" -> "はい"; "zh-CN" -> "是"; else -> "yes" }
            capture.segment(word); capture.segment(word)
            assertEquals(expected, capture.complete())
        }
    }
    @Test fun stopStillAcceptsFinalButCancelRejectsLateCallbacks() {
        val capture = CaptureSession("en-US")
        capture.segment("first"); capture.stop(); capture.segment("last")
        assertEquals("first last", capture.complete())
        val canceled = CaptureSession("en-US")
        canceled.partial("private"); canceled.cancel(); canceled.segment("late"); canceled.partial("late")
        assertEquals("", canceled.transcript)
        assertTrue(canceled.ended)
    }
}
