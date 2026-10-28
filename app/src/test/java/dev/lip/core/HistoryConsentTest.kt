package dev.lip.core

import dev.lip.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HistoryConsentTest {
    private fun ready(gate: HistoryConsentGate): HistoryConsentTicket {
        val id = gate.request(true); assertTrue(gate.complete(id, true)); return gate.ticket()!!
    }
    @Test fun newConsentDefaultsOffAndLegacyChoiceDoesNotAuthorizeCapture() {
        for (value in listOf(null, false, "true", 1, emptyMap<String, Any>())) {
            assertFalse(HistoryConsentPolicy.explicit(value))
            assertNull(HistoryConsentGate(HistoryConsentPolicy.explicit(value)).ticket())
        }
        assertTrue(HistoryConsentPolicy.explicit(true))
        val old = mapOf("history" to true)
        assertNull(HistoryConsentGate(HistoryConsentPolicy.explicit(old["history.consent.v1"])).ticket())
        assertEquals(true, old["history"]) // Original legacy preference/data not modified.
    }
    @Test fun readyPersistenceRequiredAndStaleCompletionCannotReviveConsent() {
        val gate = HistoryConsentGate()
        val on = gate.request(true)
        assertTrue(gate.snapshot().preparing); assertNull(gate.ticket())
        val off = gate.request(false)
        assertFalse(gate.complete(on, true)); assertNull(gate.ticket())
        assertTrue(gate.complete(off, true)); assertFalse(gate.snapshot().failed)
        val bad = gate.request(true); assertTrue(gate.complete(bad, false))
        assertTrue(gate.snapshot().failed); assertNull(gate.ticket())
    }
    @Test fun startOffOrPreparingCannotAcquireTicketRetroactively() {
        val gate = HistoryConsentGate()
        val old = gate.ticket(); val pending = gate.request(true); val preparing = gate.ticket()
        gate.complete(pending, true)
        assertNull(old); assertNull(preparing); assertNotNull(gate.ticket())
    }
    @Test fun offOnEpochAndCaptureCancellationArePermanent() {
        val gate = HistoryConsentGate(); val old = ready(gate)
        val off = gate.request(false); assertFalse(gate.eligible(old)); gate.complete(off, true)
        val fresh = ready(gate)
        assertFalse(gate.eligible(old)); assertTrue(runCatching { gate.claim(old) }.isFailure)
        fresh.revoke(); assertFalse(gate.sameRequest(fresh)); assertFalse(gate.eligible(fresh)); assertTrue(runCatching { gate.claim(fresh) }.isFailure)
        assertNotNull(gate.ticket()) // Only a new capture can use new epoch.
    }
    @Test fun duplicateClaimsAndLateStatusAreFenced() {
        val gate = HistoryConsentGate(); val ticket = ready(gate)
        gate.claim(ticket); assertFalse(gate.eligible(ticket))
        assertTrue(runCatching { gate.claim(ticket) }.isFailure)
        assertTrue(gate.sameRequest(ticket)) // Confirmation can report an already-claimed write.
        gate.request(false); assertFalse(gate.sameRequest(ticket))
        ready(gate); assertFalse(gate.sameRequest(ticket))
    }
    @Test fun revocationDuringEncryptedStageDeniesActivePublication() {
        val gate = HistoryConsentGate(); val ticket = ready(gate)
        val stage = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(); val files = linkedMapOf<String, String>()
        val records = HistoryRecords({ files[it] }, { n,v -> files[n]=v }, { files.keys.toList() }, { files.remove(it); Unit },
            writeAdmitted = { n,v,admit -> stage.countDown(); check(release.await(5,TimeUnit.SECONDS)); admit(); files[n]=v })
        val thread = Thread {
            try { records.save(entry(), true) { gate.claim(ticket) } } catch (e: Throwable) { failure.set(e) } finally { done.countDown() }
        }.apply { start() }
        assertTrue(stage.await(5, TimeUnit.SECONDS))
        val off = gate.request(false) // MUST return while fake disk/crypto stage remains blocked.
        assertNull(gate.ticket()); assertFalse(gate.eligible(ticket))
        val drained = CountDownLatch(1)
        val waiter = Thread { HistoryRecords.awaitDrain(); gate.complete(off, true); drained.countDown() }.apply { start() }
        assertFalse(drained.await(100, TimeUnit.MILLISECONDS))
        release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(drained.await(5,TimeUnit.SECONDS))
        thread.join(); waiter.join(); assertNotNull(failure.get()); assertTrue(files.isEmpty())
    }
    @Test fun admittedIoCanFinishAndOffConfirmationWaitsForDrain() {
        val gate = HistoryConsentGate(); val ticket = ready(gate)
        val admitted = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(); val files = linkedMapOf<String,String>()
        val records = HistoryRecords({ files[it] }, { n,v -> files[n]=v }, { files.keys.toList() }, { files.remove(it); Unit },
            writeAdmitted = { n,v,admit -> admit(); admitted.countDown(); check(release.await(5,TimeUnit.SECONDS)); files[n]=v })
        val writer = Thread { try { records.save(entry(), true) { gate.claim(ticket) } } catch (e: Throwable) { failure.set(e) } finally { done.countDown() } }.apply { start() }
        assertTrue(admitted.await(5,TimeUnit.SECONDS)); val off = gate.request(false)
        assertNull(gate.ticket()); assertFalse(gate.sameRequest(ticket))
        val drained = CountDownLatch(1)
        val waiter = Thread { HistoryRecords.awaitDrain(); gate.complete(off,true); drained.countDown() }.apply { start() }
        assertFalse(drained.await(100,TimeUnit.MILLISECONDS))
        release.countDown(); assertTrue(done.await(5,TimeUnit.SECONDS)); assertTrue(drained.await(5,TimeUnit.SECONDS))
        writer.join(); waiter.join(); assertNull(failure.get()); assertEquals(1, files.size)
        assertFalse(gate.snapshot().preparing); assertNull(gate.ticket())
    }
    @Test fun captureSaveDoesNotMigrateLegacyBeforeAdmission() {
        val files = linkedMapOf("history" to AppStore.encodeHistory(listOf(entry())))
        val original = files.toMap()
        val records = HistoryRecords({ files[it] }, { n,v -> files[n]=v }, { files.keys.filter { it.startsWith("history-") } }, { files.remove(it); Unit })
        assertTrue(runCatching { records.save(entry(), true) { error("Revoked fixture") } }.isFailure)
        assertEquals(original, files)
        assertEquals(1, records.page().entries.size) // Explicit read still verifies/migrates old content.
        assertFalse(files.containsKey("history"))
    }
    @Test fun preferencePresentationNeverCallsPreparingOrFailedOnOff() {
        val gate = HistoryConsentGate()
        assertTrue(HistoryConsentPresentation.describe(gate.snapshot()).contains("Off"))
        val on = gate.request(true); assertTrue(HistoryConsentPresentation.describe(gate.snapshot()).contains("On requested"))
        gate.complete(on,false); assertTrue(HistoryConsentPresentation.describe(gate.snapshot()).contains("unconfirmed"))
        ready(gate); assertTrue(HistoryConsentPresentation.describe(gate.snapshot()).contains("eligible new recordings"))
        gate.request(false); assertTrue(HistoryConsentPresentation.describe(gate.snapshot()).contains("draining"))
    }
    @Test fun reorderedSubmissionCannotPersistOldOnAfterConfirmedOff() {
        val gate = HistoryConsentGate(); var persisted = false; var writes = 0
        val oldOn = gate.request(true) // Pause A before executor submission.
        val off = gate.request(false) // B submits/finishes before A resumes.
        assertTrue(HistoryConsentPersistence.apply(gate, off, false, {}, { persisted=it; writes++; true }))
        assertFalse(persisted); assertFalse(gate.snapshot().preparing)
        assertFalse(HistoryConsentPersistence.apply(gate, oldOn, true, {}, { persisted=it; writes++; true }))
        assertFalse(persisted); assertEquals(1,writes)
        assertNull(HistoryConsentGate(persisted).ticket()) // Restart must remain Off.
    }
    @Test fun staleOffAfterDrainCannotOverwriteNewPreferenceAndFailedIoIsHonest() {
        val gate = HistoryConsentGate(); var writes = 0
        val off = gate.request(false)
        assertFalse(HistoryConsentPersistence.apply(gate, off, false, { gate.request(true) }, { writes++; true }))
        assertEquals(0,writes); assertNull(gate.ticket())
        val current = gate.snapshot().request
        assertTrue(HistoryConsentPersistence.apply(gate,current,true,{}, { throw java.io.IOException("Fixture write failed") }))
        assertTrue(gate.snapshot().failed); assertNull(gate.ticket())
    }
    private fun entry() = Transcript(id="synthetic", time=123L,raw="synthetic raw",clean="Synthetic raw.",language="en-US",usedChatGpt=false)
}
