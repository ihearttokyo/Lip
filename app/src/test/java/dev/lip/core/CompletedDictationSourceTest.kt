package dev.lip.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CompletedDictationSourceTest {
    private fun CompletedDictationSource.begin(allowed: Boolean = true) = begin(allowed, "en-US", "clean")

    @Test fun emptyAndUnfinishedNeverBecomeCompleted() {
        val source = CompletedDictationSource()
        assertNull(source.current())
        source.begin()
        assertNull(source.current())
    }

    @Test fun successPreservesExactFormattedUnicodeAndCapturedProvenance() {
        val source = CompletedDictationSource()
        val ticket = source.begin(true, "en-US", "pro")
        val text = "  Agenda:\n1. Meet Zoë on Friday at 3 p.m.\n2. Bring 🧑🏽‍💻 notes.\n"
        val result = source.complete(ticket, text)!!
        assertEquals(text, result.text)
        assertEquals("en-US", result.language)
        assertEquals("pro", result.modelId)
        assertSame(result, source.current())
        assertTrue(source.isCurrent(result))
    }

    @Test fun refusedNewStartRevokesPreviousEvenBeforeEarlyPermissionFailure() {
        val source = CompletedDictationSource()
        val old = source.complete(source.begin(), "Previous query")!!
        val refused = source.begin(false)
        assertNull(source.current())
        assertFalse(source.isCurrent(old))
        assertNull(source.complete(refused, "Permission-denied recovery"))
    }

    @Test fun privateCaptureCannotPublishOrBeUpgradedAfterCompletion() {
        val source = CompletedDictationSource()
        val private = source.begin(false)
        assertNull(source.complete(private, "Private text"))
        assertNull(source.revise(private, "Same text after privacy changes"))
        assertNull(source.current())
    }

    @Test fun interruptedRecoveryIsNotAPublishableCompletion() {
        val source = CompletedDictationSource()
        val attempt = source.begin()
        source.revoke() // failCapture/interrupt clears independently of phase READY.
        assertNull(source.complete(attempt, "Recovered unfinished text"))
        assertNull(source.revise(attempt, "Recovered raw text"))
        assertNull(source.current())
    }

    @Test fun lockDuringCleaningRejectsLateProducerAndOldChooser() {
        val source = CompletedDictationSource()
        val old = source.complete(source.begin(), "Old completed query")!!
        val cleaning = source.begin()
        source.revoke() // independent of whether UI phase is CLEANING, READY or IDLE.
        assertNull(source.complete(cleaning, "Late cleanup"))
        assertFalse(source.isCurrent(old))
    }

    @Test fun onlyTheCurrentProducerCanCompleteOnce() {
        val source = CompletedDictationSource()
        val stale = source.begin()
        val current = source.begin()
        assertNull(source.complete(stale, "Late old result"))
        val result = source.complete(current, "Current result")!!
        assertNull(source.complete(current, "Duplicate callback"))
        assertSame(result, source.current())
    }

    @Test fun foreignProcessOwnerCannotPublishOrRenewLease() {
        val first = CompletedDictationSource()
        val second = CompletedDictationSource()
        val ticket = first.begin()
        val otherTicket = second.begin()
        assertNull(first.complete(otherTicket, "Foreign producer"))
        val record = first.complete(ticket, "Shared-looking text")!!
        val otherRecord = second.complete(otherTicket, record.text)!!
        assertFalse(first.isCurrent(otherRecord))
        assertFalse(second.isCurrent(record))
    }

    @Test fun blankCompletionCannotLaterBeReclassifiedAsSuccess() {
        val source = CompletedDictationSource()
        val attempt = source.begin()
        assertNull(source.complete(attempt, " \n\t"))
        assertNull(source.complete(attempt, "Late retry"))
        assertNull(source.revise(attempt, "Manual text"))
    }

    @Test fun rawCleanedSelectionGetsANewIdentityWithoutChangingPayload() {
        val source = CompletedDictationSource()
        val attempt = source.begin()
        val cleaned = source.complete(attempt, "Meet on Friday.")!!
        val raw = source.revise(attempt, "meet on friday")!!
        assertEquals("Meet on Friday.", cleaned.text)
        assertEquals("meet on friday", raw.text)
        assertFalse(source.isCurrent(cleaned))
        assertTrue(source.isCurrent(raw))
        val cleanedAgain = source.revise(attempt, cleaned.text)!!
        assertNotSame(cleaned, cleanedAgain)
        assertFalse(source.isCurrent(cleaned)) // text-equal ABA never renews a lease.
        assertTrue(source.isCurrent(cleanedAgain))
    }

    @Test fun selectingBeforeSuccessOrAfterRevocationCannotCreateARecord() {
        val source = CompletedDictationSource()
        val attempt = source.begin()
        assertNull(source.revise(attempt, "Text in the visible field"))
        val original = source.complete(attempt, "Completed")!!
        assertNull(source.revise(attempt, " "))
        assertSame(original, source.current())
        source.revoke()
        assertNull(source.revise(attempt, "Selected old raw"))
        assertNull(source.current())
    }

    @Test fun newAttemptWithSameTextDoesNotRestorePriorIdentity() {
        val source = CompletedDictationSource()
        val old = source.complete(source.begin(), "Same")!!
        val fresh = source.complete(source.begin(), "Same")!!
        assertNotSame(old, fresh)
        assertFalse(source.isCurrent(old))
        assertTrue(source.isCurrent(fresh))
    }

    @Test fun diagnosticsNeverRenderTranscriptOrCapturedMetadata() {
        val source = CompletedDictationSource()
        val attempt = source.begin(true, "private-language-label", "private-model-label")
        val result = source.complete(attempt, "private query never logged")!!
        for (diagnostic in listOf(source.toString(), attempt.toString(), result.toString())) {
            assertFalse(diagnostic.contains("private"))
        }
    }

    @Test fun missingMetadataDoesNotAdmitAResult() {
        for ((language, model) in listOf("" to "clean", "en-US" to " ")) {
            val source = CompletedDictationSource()
            assertNull(source.complete(source.begin(true, language, model), "Transcript"))
        }
    }

    @Test fun freshInstanceCannotRehydratePriorProcessText() {
        val prior = CompletedDictationSource()
        val record = prior.complete(prior.begin(), "Prior process query")!!
        val restarted = CompletedDictationSource()
        assertNull(restarted.current())
        assertFalse(restarted.isCurrent(record))
    }

    @Test fun completedCallbackCannotRaceBackIntoExistenceAfterRevokeReturns() {
        val source = CompletedDictationSource()
        val attempt = source.begin()
        val ready = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicReference<CompletedDictationSource.Record?>()
        val worker = Thread {
            ready.countDown()
            if (proceed.await(3, TimeUnit.SECONDS)) result.set(source.complete(attempt, "Late callback"))
            finished.countDown()
        }
        worker.start()
        try {
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            source.revoke()
            proceed.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertNull(result.get())
            assertNull(source.current())
        } finally {
            proceed.countDown()
            worker.join(3_000)
        }
    }
}
