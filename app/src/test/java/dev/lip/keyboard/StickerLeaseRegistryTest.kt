package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic arbitrary bytes test transport policy, NOT PNG/WebP codec acceptance or actual pipe draining. */
class StickerLeaseRegistryTest {
    private class Clock(var now:Long=0,var broken:Boolean=false){fun read():Long{if(broken)throw IllegalStateException("private-test-payload");return now}}
    private val receiver=StickerReceiverBinding("owned.receiver",12001)
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun bytes(size:Int=73)=ByteArray(size){(it*31+7).toByte()}
    private fun asset(bytes:ByteArray,mime:String="image/png",sha:String=hash(bytes),length:Int=bytes.size)=StickerAssetIdentity(
        "00000000-0000-0000-0000-000000000001","a".repeat(64),"synthetic-face",sha,mime,length)
    private fun registry(clock:Clock=Clock())=StickerLeaseRegistry(clock::read){it.fill(0)}
    private fun refused(reason:StickerLeaseException.Reason?=null,block:()->Unit):StickerLeaseException {
        val e=assertThrows(StickerLeaseException::class.java){block()}
        assertEquals("Private sticker lease unavailable.",e.message);assertNull(e.cause)
        if(reason!=null)assertEquals(reason,e.reason)
        return e
    }
    private fun readAll(registry:StickerLeaseRegistry,handle:StickerLeaseRegistry.ReadHandle,size:Int,chunk:Int=17):ByteArray {
        val output=ByteArray(size);val buffer=ByteArray(chunk);var at=0
        while(true){val count=registry.readNext(handle,buffer);if(count<0)break
            assertTrue(count in 1..chunk);assertTrue(at<=size-count);buffer.copyInto(output,at,0,count);at+=count}
        assertEquals(size,at);return output
    }
    @Test fun issueDescribesOnlyMimeLengthAndRedactsOpaqueObjects(){
        val r=registry();val data=bytes();val identity=asset(data);val lease=r.issue(identity,receiver,data)
        assertTrue(Regex("[a-f0-9]{80}").matches(lease.token));assertTrue(r.isActive())
        val metadata=r.describe(lease.token,receiver,true)
        assertEquals("image/png",metadata.mime);assertEquals(data.size,metadata.byteLength)
        val handle=r.open(lease.token,receiver,true)
        for(value in listOf(r.toString(),lease.toString(),handle.toString(),metadata.toString())){
            assertFalse(value.contains(lease.token));assertFalse(value.contains(identity.commitId));assertFalse(value.contains(identity.stickerId));assertFalse(value.contains(receiver.packageName))
        }
        assertTrue(r.finish(handle));assertFalse(r.isActive())
    }
    @Test fun defensiveSnapshotSurvivesCallerMutationEvenDuringRandomCallback(){
        val input=bytes();val original=input.copyOf();val identity=asset(input)
        val r=StickerLeaseRegistry({0L}){random->random.fill(1);input.fill(0)}
        val lease=r.issue(identity,receiver,input);assertTrue(input.all{it==0.toByte()})
        val handle=r.open(lease.token,receiver,true)
        assertArrayEquals(original,readAll(r,handle,original.size));assertTrue(r.isActive());assertTrue(r.finish(handle))
    }
    @Test fun privateSnapshotHashAndExactLengthMustMatchBeforeLeaseExists(){
        val data=bytes();val r=registry()
        refused(StickerLeaseException.Reason.INTEGRITY){r.issue(asset(data,sha="0".repeat(64)),receiver,data)}
        assertFalse(r.isActive())
        refused(StickerLeaseException.Reason.INVALID){r.issue(asset(data,length=data.size+1),receiver,data)}
        refused(StickerLeaseException.Reason.INVALID){r.issue(asset(byteArrayOf(1)),receiver,byteArrayOf())}
        assertFalse(r.isActive());val lease=r.issue(asset(data),receiver,data);assertTrue(r.revoke(lease))
    }
    @Test fun originalEightMiBBoundAndMaximumReadChunkAreEnforced(){
        val data=bytes(8*1024*1024);val r=registry();val lease=r.issue(asset(data,mime="image/webp"),receiver,data)
        assertEquals(data.size,r.describe(lease.token,receiver,true).byteLength)
        val handle=r.open(lease.token,receiver,true)
        refused(StickerLeaseException.Reason.INVALID){r.readNext(handle,ByteArray(0))}
        refused(StickerLeaseException.Reason.INVALID){r.readNext(handle,ByteArray(32769))}
        assertArrayEquals(data,readAll(r,handle,data.size,32768));assertTrue(r.finish(handle))
        refused(StickerLeaseException.Reason.INVALID){r.issue(asset(data),receiver,ByteArray(8*1024*1024+1))}
        assertFalse(r.isActive())
    }
    @Test fun oneActiveLeaseCannotBeSilentlySuperseded(){
        val r=registry();val data=bytes();val lease=r.issue(asset(data),receiver,data)
        refused(StickerLeaseException.Reason.BUSY){r.issue(asset(data),receiver,data)}
        assertEquals(data.size,r.describe(lease.token,receiver,true).byteLength);assertTrue(r.revoke(lease));assertFalse(r.isActive())
    }
    @Test fun malformedWrongTokenReceiverAndMissingGrantNeverConsumeRightfulLease(){
        val clock=Clock();val r=registry(clock);val data=bytes();val lease=r.issue(asset(data),receiver,data)
        val wrong=(if(lease.token[0]=='0')"1" else "0")+lease.token.drop(1)
        for(token in listOf("", "A".repeat(80),"../private-test-payload",lease.token+"?x",wrong)){
            refused(StickerLeaseException.Reason.UNAVAILABLE){r.describe(token,receiver,true)}
            refused(StickerLeaseException.Reason.UNAVAILABLE){r.open(token,receiver,true)}
        }
        for(other in listOf(StickerReceiverBinding("owned.receiver",12002),StickerReceiverBinding("other.receiver",12001))){
            refused(StickerLeaseException.Reason.UNAVAILABLE){r.describe(lease.token,other,true)}
            refused(StickerLeaseException.Reason.UNAVAILABLE){r.open(lease.token,other,true)}
        }
        refused(StickerLeaseException.Reason.UNAVAILABLE){r.describe(lease.token,receiver,false)}
        refused(StickerLeaseException.Reason.UNAVAILABLE){r.open(lease.token,receiver,false)}
        // Bad access must not even admit a rollback observation against the rightful lease.
        clock.now=-1;refused{r.open(wrong,receiver,true)};clock.now=0
        val handle=r.open(lease.token,receiver,true);assertArrayEquals(data,readAll(r,handle,data.size));assertTrue(r.finish(handle))
    }
    @Test fun openingIsOneShotAndEofDoesNotReleaseCapacity(){
        val r=registry();val data=bytes();val lease=r.issue(asset(data),receiver,data);val handle=r.open(lease.token,receiver,true)
        refused(StickerLeaseException.Reason.UNAVAILABLE){r.open(lease.token,receiver,true)}
        assertArrayEquals(data,readAll(r,handle,data.size));assertEquals(-1,r.readNext(handle,ByteArray(1)))
        assertTrue(r.isLive(handle));assertTrue(r.isActive())
        refused(StickerLeaseException.Reason.BUSY){r.issue(asset(data),receiver,data)}
        assertTrue(r.finish(handle));assertFalse(r.finish(handle));assertFalse(r.isActive())
        refused(StickerLeaseException.Reason.UNAVAILABLE){r.open(lease.token,receiver,true)}
    }
    @Test fun unopenedRevokeDropsOnlyExactLeaseAndNeverReleasesNewerOne(){
        val r=registry();val data=bytes();val old=r.issue(asset(data),receiver,data)
        assertFalse(r.revoke(StickerLeaseRegistry.Lease(old.token)));assertTrue(r.isActive())
        assertTrue(r.revoke(old));assertFalse(r.isActive());assertFalse(r.revoke(old))
        val current=r.issue(asset(data),receiver,data);assertFalse(r.revoke(old));assertTrue(r.isActive())
        refused{r.describe(old.token,receiver,true)};assertTrue(r.revoke(current))
    }
    @Test fun openedRevokeRemainsDrainingUntilExactHandleFinish(){
        val r=registry();val data=bytes();val lease=r.issue(asset(data),receiver,data);val handle=r.open(lease.token,receiver,true)
        assertTrue(r.revoke(lease));assertFalse(r.isLive(handle));assertTrue(r.isActive())
        refused(StickerLeaseException.Reason.UNAVAILABLE){r.readNext(handle,ByteArray(16))}
        refused(StickerLeaseException.Reason.BUSY){r.issue(asset(data),receiver,data)}
        assertFalse(r.finish(StickerLeaseRegistry.ReadHandle()));assertTrue(r.isActive())
        assertTrue(r.finish(handle));assertFalse(r.isActive())
    }
    @Test fun unopenedExpiryUsesExactMonotonicTtlBoundary(){
        val clock=Clock();val r=registry(clock);val data=bytes();val lease=r.issue(asset(data),receiver,data)
        assertEquals(120000L,StickerLeaseRegistry.TTL_MS)
        clock.now=119999;assertEquals(data.size,r.describe(lease.token,receiver,true).byteLength)
        clock.now=120000;assertFalse(r.isActive());refused{r.open(lease.token,receiver,true)}
        val next=r.issue(asset(data),receiver,data);assertTrue(next.token!=lease.token);assertTrue(r.revoke(next))
    }
    @Test fun openedExpiryRetainsCapacityEvenAfterEofUntilFinish(){
        val clock=Clock();val r=registry(clock);val data=bytes();val lease=r.issue(asset(data),receiver,data);val handle=r.open(lease.token,receiver,true)
        readAll(r,handle,data.size);clock.now=120000
        assertFalse(r.isLive(handle));assertTrue(r.isActive());refused{r.describe(lease.token,receiver,true)}
        refused(StickerLeaseException.Reason.BUSY){r.issue(asset(data),receiver,data)}
        assertTrue(r.finish(handle));assertFalse(r.isActive())
    }
    @Test fun rollbackPoisonsPermanentlyButOpenedCapacityStillRequiresFinish(){
        val data=bytes();val pendingClock=Clock(100);val pending=registry(pendingClock)
        pending.issue(asset(data),receiver,data);pendingClock.now=99;assertFalse(pending.isActive())
        pendingClock.now=101;refused(StickerLeaseException.Reason.CLOCK){pending.issue(asset(data),receiver,data)}
        val clock=Clock(100);val r=registry(clock);val lease=r.issue(asset(data),receiver,data);val handle=r.open(lease.token,receiver,true)
        clock.now=99;assertFalse(r.isLive(handle));assertTrue(r.isActive())
        refused(StickerLeaseException.Reason.BUSY){r.issue(asset(data),receiver,data)}
        assertTrue(r.finish(handle));assertFalse(r.isActive());clock.now=101
        refused(StickerLeaseException.Reason.CLOCK){r.issue(asset(data),receiver,data)}
    }
    @Test fun clockExceptionIsPrivateAndStillPermitsExactWriterDrain(){
        val clock=Clock();val r=registry(clock);val data=bytes();val lease=r.issue(asset(data),receiver,data);val handle=r.open(lease.token,receiver,true)
        clock.broken=true;assertFalse(r.isLive(handle));assertTrue(r.isActive());refused{r.readNext(handle,ByteArray(1))}
        assertTrue(r.finish(handle));assertFalse(r.isActive());refused(StickerLeaseException.Reason.CLOCK){r.issue(asset(data),receiver,data)}
    }
    @Test fun negativeOriginAndExpiryOverflowAreHandledWithoutWrapping(){
        val data=bytes();val clock=Clock(-100);val r=registry(clock);val lease=r.issue(asset(data),receiver,data)
        clock.now=0;assertTrue(r.isActive());assertTrue(r.revoke(lease))
        val overflow=registry(Clock(Long.MAX_VALUE-StickerLeaseRegistry.TTL_MS+1))
        refused(StickerLeaseException.Reason.LIMIT){overflow.issue(asset(data),receiver,data)};assertFalse(overflow.isActive())
        val edgeClock=Clock(Long.MAX_VALUE-StickerLeaseRegistry.TTL_MS);val edge=registry(edgeClock)
        edge.issue(asset(data),receiver,data);edgeClock.now=Long.MAX_VALUE;assertFalse(edge.isActive())
    }
    @Test fun finalIssueClockStartsTtlAndRollbackDuringIssuePublishesNothing(){
        val data=bytes();val clock=Clock()
        val r=StickerLeaseRegistry(clock::read){random->random.fill(0);clock.now=5000}
        val lease=r.issue(asset(data),receiver,data)
        clock.now=124999;assertEquals(data.size,r.describe(lease.token,receiver,true).byteLength)
        clock.now=125000;assertFalse(r.isActive())
        val rollbackClock=Clock(100)
        val rollback=StickerLeaseRegistry(rollbackClock::read){random->random.fill(0);rollbackClock.now=99}
        refused(StickerLeaseException.Reason.CLOCK){rollback.issue(asset(data),receiver,data)}
        assertFalse(rollback.isActive())
    }
    @Test fun randomFailureExposesNoCauseAndLeavesNoActiveLease(){
        val data=bytes();val r=StickerLeaseRegistry({0L}){throw IllegalStateException("private-test-payload")}
        refused(StickerLeaseException.Reason.UNAVAILABLE){r.issue(asset(data),receiver,data)}
        assertFalse(r.isActive())
    }
    @Test fun processNoncePreventsDeterministicTokenReuseAcrossRegistryInstances(){
        val data=bytes();val firstRegistry=registry();val first=firstRegistry.issue(asset(data),receiver,data);firstRegistry.revoke(first)
        val secondRegistry=registry();val second=secondRegistry.issue(asset(data),receiver,data)
        assertTrue(first.token!=second.token);assertTrue(first.token.take(64)==second.token.take(64))
        assertTrue(second.token.takeLast(16).toLong(16)>first.token.takeLast(16).toLong(16));secondRegistry.revoke(second)
    }
    @Test fun staleCrossRegistryAndFinishedHandlesCannotReleaseOrReadNewLease(){
        val data=bytes();val r=registry();val first=r.issue(asset(data),receiver,data);val old=r.open(first.token,receiver,true);r.finish(old)
        val next=r.issue(asset(data),receiver,data);val current=r.open(next.token,receiver,true)
        assertFalse(r.finish(old));assertFalse(r.isLive(old));refused{r.readNext(old,ByteArray(1))};assertTrue(r.isLive(current))
        val other=registry();val otherLease=other.issue(asset(data),receiver,data);val foreign=other.open(otherLease.token,receiver,true)
        assertFalse(r.finish(foreign));assertFalse(r.revoke(otherLease));assertTrue(r.isActive())
        assertTrue(r.finish(current));assertTrue(other.finish(foreign))
    }
    @Test fun blockedSimulatedWriterRetainsAdmissionAfterCancelUntilFinally(){
        val r=registry();val data=bytes();val lease=r.issue(asset(data),receiver,data);val handle=r.open(lease.token,receiver,true)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1);val error=AtomicReference<Throwable>()
        val writer=Thread{try{assertTrue(r.readNext(handle,ByteArray(1))==1);entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            catch(t:Throwable){error.set(t)}finally{r.finish(handle);done.countDown()}}.apply{isDaemon=true;start()}
        try{assertTrue(entered.await(5,TimeUnit.SECONDS));r.revoke(lease);assertTrue(r.isActive());refused(StickerLeaseException.Reason.BUSY){r.issue(asset(data),receiver,data)}}
        finally{release.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));writer.join(1000)}
        assertFalse(writer.isAlive);assertNull(error.get());assertFalse(r.isActive())
    }
    @Test fun concurrentIssueAndOpenHaveExactlyOneWinner(){
        val r=registry();val data=bytes();val identity=asset(data);val gate=CountDownLatch(1);val done=CountDownLatch(8)
        val leases=Collections.synchronizedList(ArrayList<StickerLeaseRegistry.Lease>());val busy=AtomicInteger();val failure=AtomicReference<Throwable>()
        val issuers=(0 until 8).map{Thread{try{check(gate.await(5,TimeUnit.SECONDS));leases.add(r.issue(identity,receiver,data))}
            catch(e:StickerLeaseException){if(e.reason==StickerLeaseException.Reason.BUSY)busy.incrementAndGet() else failure.compareAndSet(null,e)}
            catch(t:Throwable){failure.compareAndSet(null,t)}finally{done.countDown()}}.apply{isDaemon=true;start()}}
        gate.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));issuers.forEach{it.join(1000)}
        assertTrue(issuers.none{it.isAlive});assertNull(failure.get());assertEquals(1,leases.size);assertEquals(7,busy.get())
        val lease=leases.single();val openGate=CountDownLatch(1);val opened=CountDownLatch(8);val handles=Collections.synchronizedList(ArrayList<StickerLeaseRegistry.ReadHandle>())
        val refused=AtomicInteger();val openers=(0 until 8).map{Thread{try{check(openGate.await(5,TimeUnit.SECONDS));handles.add(r.open(lease.token,receiver,true))}
            catch(e:StickerLeaseException){if(e.reason==StickerLeaseException.Reason.UNAVAILABLE)refused.incrementAndGet() else failure.compareAndSet(null,e)}
            catch(t:Throwable){failure.compareAndSet(null,t)}finally{opened.countDown()}}.apply{isDaemon=true;start()}}
        openGate.countDown();assertTrue(opened.await(5,TimeUnit.SECONDS));openers.forEach{it.join(1000)}
        assertTrue(openers.none{it.isAlive});assertNull(failure.get());assertEquals(1,handles.size);assertEquals(7,refused.get());assertTrue(r.finish(handles.single()))
    }
}
