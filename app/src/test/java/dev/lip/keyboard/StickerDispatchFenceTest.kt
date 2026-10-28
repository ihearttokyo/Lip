package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class StickerDispatchFenceTest {
    @Test fun cancellationWinsAndCannotLaterDispatch(){
        val fence=StickerDispatchFence();assertEquals(StickerDispatchFence.State.PENDING,fence.state)
        assertTrue(fence.cancel());assertEquals(StickerDispatchFence.State.CANCELED,fence.state)
        repeat(10){assertTrue(fence.cancel());assertFalse(fence.beginDispatch())}
        assertEquals(StickerDispatchFence.State.CANCELED,fence.state)
    }
    @Test fun dispatchAdmissionWinsWithoutClaimingCompletionOrAllowingDuplicates(){
        val fence=StickerDispatchFence();assertTrue(fence.beginDispatch())
        assertEquals(StickerDispatchFence.State.DISPATCHING,fence.state)
        repeat(10){assertFalse(fence.cancel());assertFalse(fence.beginDispatch())}
        assertEquals(StickerDispatchFence.State.DISPATCHING,fence.state)
    }
    @Test fun racingCancelAndDispatchHaveOneCoherentTerminalWinner(){
        repeat(32){
            val fence=StickerDispatchFence();val start=CountDownLatch(1);val done=CountDownLatch(2)
            val dispatched=AtomicReference<Boolean>();val canceled=AtomicReference<Boolean>();val failure=AtomicReference<Throwable>()
            val dispatch=Thread{try{check(start.await(5,TimeUnit.SECONDS));dispatched.set(fence.beginDispatch())}catch(t:Throwable){failure.compareAndSet(null,t)}finally{done.countDown()}}.apply{isDaemon=true;start()}
            val cancel=Thread{try{check(start.await(5,TimeUnit.SECONDS));canceled.set(fence.cancel())}catch(t:Throwable){failure.compareAndSet(null,t)}finally{done.countDown()}}.apply{isDaemon=true;start()}
            start.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));dispatch.join(1000);cancel.join(1000)
            assertFalse(dispatch.isAlive);assertFalse(cancel.isAlive);assertNull(failure.get())
            if(dispatched.get()==true){assertEquals(false,canceled.get());assertEquals(StickerDispatchFence.State.DISPATCHING,fence.state)}
            else{assertEquals(false,dispatched.get());assertEquals(true,canceled.get());assertEquals(StickerDispatchFence.State.CANCELED,fence.state)}
            assertFalse(fence.beginDispatch())
        }
    }
    @Test fun concurrentDispatchAttemptsAdmitExactlyOneActualCaller(){
        val fence=StickerDispatchFence();val gate=CountDownLatch(1);val done=CountDownLatch(12)
        val winners=AtomicInteger();val failure=AtomicReference<Throwable>()
        val threads=(0 until 12).map{Thread{try{check(gate.await(5,TimeUnit.SECONDS));if(fence.beginDispatch())winners.incrementAndGet()}
            catch(t:Throwable){failure.compareAndSet(null,t)}finally{done.countDown()}}.apply{isDaemon=true;start()}}
        gate.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));threads.forEach{it.join(1000)}
        assertTrue(threads.none{it.isAlive});assertNull(failure.get());assertEquals(1,winners.get())
        assertEquals(StickerDispatchFence.State.DISPATCHING,fence.state);assertFalse(fence.cancel())
    }
    @Test fun renderingContainsOnlyStaticRedactedDescription(){
        val pending=StickerDispatchFence();assertEquals("StickerDispatchFence(redacted)",pending.toString())
        pending.cancel();assertEquals("StickerDispatchFence(redacted)",pending.toString())
        val dispatching=StickerDispatchFence();dispatching.beginDispatch();assertEquals("StickerDispatchFence(redacted)",dispatching.toString())
    }
}
