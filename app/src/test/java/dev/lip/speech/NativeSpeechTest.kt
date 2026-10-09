package dev.lip.speech

import org.junit.Assert.*
import org.junit.Test

/** Injected native-failure fixtures; no native model load or real heap exhaustion. */
class NativeSpeechTest {
    @Test fun successfulOperationPreservesItsResult() {
        val result = Any()
        assertSame(result, nativeSpeech { result })
    }

    @Test fun nativeMemoryFailureBecomesRecoverableWithoutExposingItsDetails() {
        val failure = assertThrows(IllegalStateException::class.java) {
            nativeSpeech { throw OutOfMemoryError("fixture memory details") }
        }
        assertEquals("Local speech is unavailable or out of memory.", failure.message)
        assertNull(failure.cause)
    }

    @Test fun nativeLinkageFailuresBecomeRecoverableWithoutExposingTheirDetails() {
        for (error in listOf(LinkageError("fixture linkage details"), UnsatisfiedLinkError("fixture missing symbol"))) {
            val failure = assertThrows(IllegalStateException::class.java) { nativeSpeech { throw error } }
            assertEquals("Local speech is unavailable or out of memory.", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test fun unrelatedFatalErrorsPropagateUnchanged() {
        for (fatal in listOf(AssertionError("fixture assertion"), StackOverflowError("fixture stack"), ThreadDeath())) {
            assertSame(fatal, assertThrows(Error::class.java) { nativeSpeech { throw fatal } })
        }
    }
}
