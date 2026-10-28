package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** CPU-only concurrency controls; no Android editor, store, model or native work. */
class SwipeVocabularyEpochTest {
    @Test fun overlappingWritersRejectNewAndOldSnapshotsUntilEveryWriterDrains() {
        val gate=SwipeVocabularyEpoch();val old=gate.snapshot()!!
        val first=gate.beginWrite();val second=gate.beginWrite()
        assertNull(gate.snapshot());assertFalse(gate.isCurrent(old))
        first.finish();first.finish();assertNull(gate.snapshot())
        second.finish();val fresh=gate.snapshot()!!
        assertFalse(gate.isCurrent(old));assertTrue(gate.isCurrent(fresh));assertNotEquals(old,fresh)
    }
    @Test fun failedAndEqualValueWritesStillInvalidateEarlierEpochWithoutReset() {
        val gate=SwipeVocabularyEpoch();val old=gate.snapshot()!!
        val write=gate.beginWrite()
        try { throw IllegalStateException("synthetic store refusal") } catch(_:IllegalStateException) {} finally { write.finish() }
        assertFalse(gate.claim(old){fail("stale claim ran");true})
        val equalRetry=gate.snapshot()!!;gate.beginWrite().finish()
        assertFalse(gate.isCurrent(equalRetry));assertNotEquals(old,gate.snapshot())
    }
    @Test fun cpuClaimAndMutationLinearizeButAlreadyClaimedDispatchCanFinishOnce() {
        val gate=SwipeVocabularyEpoch();val expected=gate.snapshot()!!
        val session=ImeSession();session.beginEditor();val owner=SwipeRequestOwner(session)
        val cursor=ImeCursor("owned.synthetic",1,0,0,"","",false);val connection=Any()
        val request=owner.begin(cursor,connection)!!
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val writeAttempt=CountDownLatch(1);val writeDone=CountDownLatch(1)
        var claimed=false
        val claimant=thread { claimed=gate.claim(expected){entered.countDown();check(release.await(2,TimeUnit.SECONDS));owner.claim(request,cursor,connection)} }
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        val writer=thread { writeAttempt.countDown();gate.beginWrite().finish();writeDone.countDown() }
        assertTrue(writeAttempt.await(2,TimeUnit.SECONDS));assertFalse(writeDone.await(40,TimeUnit.MILLISECONDS))
        release.countDown();claimant.join(2000);writer.join(2000)
        assertFalse(claimant.isAlive);assertFalse(writer.isAlive);assertTrue(claimed);assertEquals(0L,writeDone.count)
        assertFalse(gate.isCurrent(expected));assertFalse(owner.claim(request,cursor,connection))
        // The one CPU claim is authority for its already-admitted dispatch only;
        // no gate lock is held during that later (simulated) dispatch.
        var dispatched=0;if(claimed)dispatched++;assertEquals(1,dispatched)
    }
    @Test fun cursorSnapshotIpcMustNotHoldGateAndMutationDuringItRefusesFinalClaim() {
        val gate=SwipeVocabularyEpoch();val expected=gate.snapshot()!!
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1);val claims=AtomicInteger()
        var accepted=true
        val callback=thread {
            entered.countDown();check(release.await(2,TimeUnit.SECONDS)) // Synthetic cursor IPC, OUTSIDE gate.
            accepted=gate.claim(expected){claims.incrementAndGet();true};done.countDown()
        }
        assertTrue(entered.await(2,TimeUnit.SECONDS));gate.beginWrite().finish()
        release.countDown();assertTrue(done.await(2,TimeUnit.SECONDS));callback.join(2000)
        assertFalse(accepted);assertEquals(0,claims.get())
    }
    @Test fun observerCallbacksRunOutsideGateAndCannotStrandWriters() {
        val gate=SwipeVocabularyEpoch();val calls=AtomicInteger()
        val observer:()->Unit={
            val read=CountDownLatch(1);val another=thread { gate.snapshot();read.countDown() }
            assertTrue(read.await(2,TimeUnit.SECONDS));another.join(2000);calls.incrementAndGet()
        }
        val throwing:()->Unit={throw IllegalStateException("synthetic observer refusal")}
        gate.observe(observer);gate.observe(throwing)
        gate.beginWrite().finish();assertEquals(2,calls.get());assertNotNull(gate.snapshot())
        gate.unobserve(observer);gate.beginWrite().finish();assertEquals(2,calls.get())
    }
    @Test fun contextRevocationIsStickyAndForeignEpochCannotAuthorize() {
        val access=SwipeVocabularyAccess();assertTrue(access.isCurrent());access.revoke();access.revoke();assertFalse(access.isCurrent())
        val first=SwipeVocabularyEpoch();val second=SwipeVocabularyEpoch()
        assertFalse(second.claim(first.snapshot()!!){fail("foreign claim ran");true})
    }
    @Test fun oldSuggestionOriginMustNotBeRelabeledAfterCursorIpc() {
        val gate=SwipeVocabularyEpoch();val originatingEngine=gate.snapshot()!!
        assertTrue(gate.isCurrent(originatingEngine)) // First async-callback guard passed.
        // The subsequent cursor/owned-span check performs editor IPC outside the
        // gate. A real dictionary write can complete while that IPC is pending.
        gate.beginWrite().finish()
        assertFalse(gate.isCurrent(originatingEngine)) // Stage with captured origin refuses.
        assertTrue(gate.isCurrent(gate.snapshot()!!)) // Recapturing here would incorrectly admit old choices.
    }

}
