package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Pure barrier policy only: these tests do not claim Android gating, store I/O or IME cancellation. */
class StickerOriginalReadBarrierTest {
    private fun asset()=StickerAssetIdentity("8cc8db1d-6255-40df-90f4-815329caa03d",
        "fbb2e463bb624120710566fd8877c49a3c0c045500e562b680739ef3f10cf1ec","red",
        "df75ecf5f620d125e80ae77bf639cecad76878216cdafb71afef124b04bbf5dc","image/png",78)
    private fun arm(policy:StickerOriginalReadBarrierPolicy)=policy.arm(asset(),UUID.randomUUID().toString())
    private fun refused(action:()->Unit){try{action();fail("Expected static refusal.")}catch(e:IllegalStateException){assertEquals("Read barrier unavailable.",e.message)}}
    private fun await(check:()->Boolean){
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(!check()){if(System.nanoTime()>=deadline)fail("Barrier state deadline.");Thread.sleep(1)}
    }
    private class Worker(val policy:StickerOriginalReadBarrierPolicy,val handle:StickerOriginalReadBarrierPolicy.Handle,
        val live:()->Boolean={true},val exit:CountDownLatch=CountDownLatch(0)):AutoCloseable {
        val result=AtomicReference<Boolean>()
        val failure=AtomicReference<Throwable>()
        val thread=Thread{
            try{
                check(policy.claim(handle.asset)===handle)
                result.set(policy.storeLive(handle,live))
                if(exit.count>0)check(exit.await(3,TimeUnit.SECONDS))
            }catch(t:Throwable){failure.set(t)}finally{
                if(policy.snapshot(handle).claimed)policy.loaderAdmissionReleased(handle)
            }
        }.apply{isDaemon=true;start()}
        override fun close(){
            if(policy.snapshot(handle).active)policy.forceReleaseFinally(handle)
            exit.countDown();thread.join(4000)
            assertFalse("Actual test worker still alive.",thread.isAlive)
            failure.get()?.let{throw AssertionError("Worker failed.",it)}
        }
    }
    @Test fun onlyCanonicalKnownAssetAndCanonicalOwnerCanArm(){
        val p=StickerOriginalReadBarrierPolicy();val a=asset()
        refused{p.arm(a,"not-an-owner")}
        val changed=listOf(
            StickerAssetIdentity(UUID.randomUUID().toString(),a.archiveSha256,a.stickerId,a.memberSha256,a.mime,a.byteLength),
            StickerAssetIdentity(a.commitId,"a".repeat(64),a.stickerId,a.memberSha256,a.mime,a.byteLength),
            StickerAssetIdentity(a.commitId,a.archiveSha256,"other",a.memberSha256,a.mime,a.byteLength),
            StickerAssetIdentity(a.commitId,a.archiveSha256,a.stickerId,"a".repeat(64),a.mime,a.byteLength),
            StickerAssetIdentity(a.commitId,a.archiveSha256,a.stickerId,a.memberSha256,"image/webp",a.byteLength),
            StickerAssetIdentity(a.commitId,a.archiveSha256,a.stickerId,a.memberSha256,a.mime,79))
        changed.forEach{refused{p.arm(it,UUID.randomUUID().toString())}}
        val h=arm(p);assertNull(p.claim(changed.first()));assertFalse(p.snapshot(h).claimed)
        p.forceReleaseFinally(h);assertTrue(p.awaitLoaderDrained(h,0));assertTrue(p.retire(h))
    }
    @Test fun canceledPredicateDoesNotReleaseCallbackOrAdmissionCapacity(){
        val p=StickerOriginalReadBarrierPolicy();val h=arm(p);val live=AtomicBoolean(true);val exit=CountDownLatch(1)
        Worker(p,h,{live.get()},exit).use{w->
            await{p.snapshot(h).entered};assertTrue(p.snapshot(h).workerThreadAlive)
            assertNull(p.claim(asset()))
            live.set(false);await{p.snapshot(h).canceledPredicateObserved}
            p.cancel(h);assertFalse(p.snapshot(h).released);assertFalse(p.snapshot(h).callbackExited)
            assertFalse(p.awaitLoaderDrained(h,5));assertFalse(p.retire(h));refused{arm(p)}
            p.release(h);await{p.snapshot(h).callbackExited};assertEquals(false,w.result.get())
            assertFalse(p.snapshot(h).loaderAdmissionReleased);assertFalse(p.awaitLoaderDrained(h,5));refused{arm(p)}
            exit.countDown();assertTrue(p.awaitLoaderDrained(h,3000))
        }
        assertFalse(p.snapshot(h).workerThreadAlive);assertTrue(p.retire(h))
    }
    @Test fun explicitReleaseAllowsTruePredicateAndRequiresRealLoaderExit(){
        val p=StickerOriginalReadBarrierPolicy();val h=arm(p)
        Worker(p,h).use{w->await{p.snapshot(h).entered};p.release(h);assertTrue(p.awaitLoaderDrained(h,3000));assertEquals(true,w.result.get())}
        assertTrue(p.snapshot(h).callbackExited);assertEquals(1,p.snapshot(h).storeLiveInvocations);assertTrue(p.retire(h))
    }
    @Test fun cleanupCancellationIsStickyAndOldHandleCannotAffectNewArm(){
        val p=StickerOriginalReadBarrierPolicy();val old=arm(p)
        Worker(p,old).use{w->await{p.snapshot(old).entered};p.forceReleaseFinally(old);assertTrue(p.awaitLoaderDrained(old,3000));assertEquals(false,w.result.get())}
        assertTrue(p.retire(old));val next=arm(p)
        refused{p.cancel(old)};refused{p.release(old)};assertFalse(p.retire(old))
        assertFalse(p.snapshot(next).released);assertFalse(p.snapshot(next).cancelRequested)
        p.forceReleaseFinally(next);assertTrue(p.retire(next))
    }
    @Test fun failsafeTimeoutIsVisibleAndNeverClaimsSuccessfulRead(){
        val p=StickerOriginalReadBarrierPolicy(maximumWaitMs=20);val h=arm(p)
        Worker(p,h).use{w->assertTrue(p.awaitLoaderDrained(h,3000));assertEquals(false,w.result.get())}
        val s=p.snapshot(h);assertTrue(s.timedOut);assertTrue(s.cancelRequested);assertTrue(s.callbackExited);assertTrue(s.loaderAdmissionReleased)
        assertTrue(p.retire(h))
    }
    @Test fun interruptionFailsClosedWithoutOwningOrKillingExecutorThread(){
        val p=StickerOriginalReadBarrierPolicy();val h=arm(p)
        Worker(p,h).use{w->await{p.snapshot(h).entered};w.thread.interrupt();assertTrue(p.awaitLoaderDrained(h,3000));assertEquals(false,w.result.get())}
        assertTrue(p.snapshot(h).interrupted);assertTrue(p.snapshot(h).cancelRequested);assertTrue(p.retire(h))
    }
    @Test fun releasedUnenteredArmCannotLaterClaimOrSupersedeAndRenderingIsRedacted(){
        val p=StickerOriginalReadBarrierPolicy();assertNull(p.activeSnapshot());val h=arm(p)
        assertEquals(p.snapshot(h),p.activeSnapshot())
        assertEquals("StickerOriginalReadBarrierHandle(redacted)",h.toString())
        assertEquals("StickerOriginalReadBarrierPolicy(redacted)",p.toString())
        assertFalse(p.awaitLoaderDrained(h,0));p.forceReleaseFinally(h);assertNull(p.claim(asset()))
        assertTrue(p.awaitLoaderDrained(h,0));refused{arm(p)};assertTrue(p.retire(h))
        assertFalse(p.snapshot(h).active);assertFalse(p.snapshot(h).entered);assertNull(p.activeSnapshot())
    }
}
