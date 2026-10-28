package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class StickerGrantRevocationTest {
    @Test fun successRequiresActualCallbackReturnAndNeverRepeats(){
        val calls=AtomicInteger();val revocation=StickerGrantRevocation{calls.incrementAndGet()}
        assertEquals(StickerGrantRevocation.State.NOT_STARTED,revocation.state)
        assertEquals(StickerGrantRevocation.State.SUCCEEDED,revocation.request())
        assertEquals(StickerGrantRevocation.State.SUCCEEDED,revocation.state)
        repeat(10){assertEquals(StickerGrantRevocation.State.SUCCEEDED,revocation.request())}
        assertEquals(1,calls.get())
    }
    @Test fun blockedCallbackIsPendingAndConcurrentRequestDoesNotClaimCompletion(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
        val calls=AtomicInteger();val result=AtomicReference<StickerGrantRevocation.State>();val failure=AtomicReference<Throwable>()
        val revocation=StickerGrantRevocation{calls.incrementAndGet();entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val caller=Thread{try{result.set(revocation.request())}catch(t:Throwable){failure.set(t)}finally{done.countDown()}}.apply{isDaemon=true;start()}
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertEquals(StickerGrantRevocation.State.PENDING,revocation.state)
            assertEquals(StickerGrantRevocation.State.PENDING,revocation.request())
            assertEquals(1,calls.get());assertNull(result.get());assertEquals(1L,done.count)
        }finally{release.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));caller.join(1000)}
        assertFalse(caller.isAlive);assertNull(failure.get())
        assertEquals(StickerGrantRevocation.State.SUCCEEDED,result.get());assertEquals(StickerGrantRevocation.State.SUCCEEDED,revocation.state)
        assertEquals(1,calls.get())
    }
    @Test fun allThrowableFailuresAreLatchedWithoutRetryOrCauseExposure(){
        for(failure in listOf<Throwable>(IllegalStateException("private-test-payload"),AssertionError("private-test-payload"),object:Error("private-test-payload"){})){
            val calls=AtomicInteger();val revocation=StickerGrantRevocation{calls.incrementAndGet();throw failure}
            assertEquals(StickerGrantRevocation.State.UNCONFIRMED,revocation.request())
            repeat(10){assertEquals(StickerGrantRevocation.State.UNCONFIRMED,revocation.request())}
            assertEquals(StickerGrantRevocation.State.UNCONFIRMED,revocation.state);assertEquals(1,calls.get())
            assertFalse(revocation.toString().contains("private-test-payload"))
        }
    }
    @Test fun reentrantRequestReturnsPendingWithoutDuplicateExecutionOrDeadlock(){
        val calls=AtomicInteger();val nested=AtomicReference<StickerGrantRevocation.State>()
        lateinit var revocation:StickerGrantRevocation
        revocation=StickerGrantRevocation{calls.incrementAndGet();nested.set(revocation.request());assertEquals(StickerGrantRevocation.State.PENDING,revocation.state)}
        assertEquals(StickerGrantRevocation.State.SUCCEEDED,revocation.request())
        assertEquals(StickerGrantRevocation.State.PENDING,nested.get());assertEquals(1,calls.get())
    }
    @Test fun blockedFailureDoesNotBecomeSucceededAndRemainsQuarantined(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1);val calls=AtomicInteger()
        val result=AtomicReference<StickerGrantRevocation.State>()
        val revocation=StickerGrantRevocation{calls.incrementAndGet();entered.countDown();check(release.await(5,TimeUnit.SECONDS));throw IllegalStateException("private-test-payload")}
        val caller=Thread{try{result.set(revocation.request())}finally{done.countDown()}}.apply{isDaemon=true;start()}
        try{assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(StickerGrantRevocation.State.PENDING,revocation.request())}
        finally{release.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));caller.join(1000)}
        assertFalse(caller.isAlive);assertEquals(StickerGrantRevocation.State.UNCONFIRMED,result.get())
        assertEquals(StickerGrantRevocation.State.UNCONFIRMED,revocation.request());assertEquals(1,calls.get())
    }
    @Test fun manyThreadsExecuteOneCallbackAndObservePendingUntilRealCompletion(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val start=CountDownLatch(1);val done=CountDownLatch(12)
        val calls=AtomicInteger();val failure=AtomicReference<Throwable>();val pendingDone=CountDownLatch(11)
        val results=Collections.synchronizedList(ArrayList<StickerGrantRevocation.State>())
        val revocation=StickerGrantRevocation{calls.incrementAndGet();entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val threads=(0 until 12).map{Thread{
            var pending=false
            try{check(start.await(5,TimeUnit.SECONDS));val observed=revocation.request();results.add(observed);pending=observed==StickerGrantRevocation.State.PENDING}
            catch(t:Throwable){failure.compareAndSet(null,t)}finally{done.countDown();if(pending)pendingDone.countDown()}
        }.apply{isDaemon=true;start()}}
        start.countDown()
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertTrue(pendingDone.await(5,TimeUnit.SECONDS))
            assertEquals(1L,done.count);assertEquals(1,calls.get())
            assertEquals(StickerGrantRevocation.State.PENDING,revocation.state)
            assertEquals(11,results.size);assertTrue(results.all{it==StickerGrantRevocation.State.PENDING})
        }finally{release.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));threads.forEach{it.join(1000)}}
        assertTrue(threads.none{it.isAlive});assertNull(failure.get());assertEquals(1,calls.get())
        assertEquals(1,results.count{it==StickerGrantRevocation.State.SUCCEEDED})
        assertEquals(StickerGrantRevocation.State.SUCCEEDED,revocation.state)
    }
    @Test fun defaultRenderingNeverIncludesCallbackOrPrivateMaterial(){
        val marker="private-test-payload";val callback={check(marker.isNotEmpty())}
        val revocation=StickerGrantRevocation(callback)
        assertEquals("StickerGrantRevocation(redacted)",revocation.toString())
        revocation.request();assertEquals("StickerGrantRevocation(redacted)",revocation.toString())
    }
}
