package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic CPU policy/fault controls, not Android grant/UID/provider qualification. */
class ProductionStickerContractsTest {
    private val signer="a".repeat(64)
    private val safety=ProductionStickerSafety(1,0,true,true,true,false,false)
    private fun admit(pkg:String?="dev.synthetic.editor",binding:Int=10101,own:Int=10102,app:Int=10101,
        packages:List<String>?=listOf("dev.synthetic.editor"),shared:String?=null,signers:Set<String>?=setOf(signer),
        installed:Long=10,updated:Long=11,version:Long=1,enabled:Boolean=true,suspended:Boolean=false)=
        ProductionStickerReceiverPolicy.admit(pkg,binding,own,app,packages,shared,signers,installed,updated,version,enabled,suspended)
    @Test fun receiverRequiresActualUidSingletonCurrentSignerAndIncarnation() {
        val good=admit()!!
        assertEquals(10101,good.uid);assertEquals(setOf(signer),good.signers)
        val refused=listOf(admit(pkg=null),admit(pkg="bad"),admit(binding=0),admit(binding=10102),admit(app=10103),
            admit(packages=null),admit(packages=emptyList()),admit(packages=listOf("dev.foreign.editor")),
            admit(packages=listOf("dev.synthetic.editor","dev.other.app")),admit(packages=listOf("dev.synthetic.editor","dev.synthetic.editor")),
            admit(shared="shared"),admit(signers=null),admit(signers=emptySet()),admit(signers=setOf("A".repeat(64))),
            admit(signers=setOf("a".repeat(63))),admit(signers=(0..16).map{it.toString(16).padStart(64,'0')}.toSet()),
            admit(installed=0),admit(updated=9),admit(version=-1),admit(enabled=false),admit(suspended=true))
        assertTrue(refused.all{it==null})
        assertNotEquals(good,admit(updated=12));assertNotEquals(good,admit(installed=9));assertNotEquals(good,admit(version=2))
        assertNotEquals(good,admit(signers=setOf("b".repeat(64))))
        assertEquals(good,admit());assertEquals(good.hashCode(),admit().hashCode())
    }
    @Test fun signerSnapshotIsImmutableAndRedacted() {
        val source=mutableSetOf(signer)
        val facts=admit(signers=source)!!;source.clear()
        assertEquals(setOf(signer),facts.signers)
        try{(facts.signers as MutableSet<String>).clear();fail("Expected immutable signer snapshot.")}catch(_:UnsupportedOperationException){}
        assertEquals("ProductionStickerReceiverFacts(redacted)",facts.toString())
    }
    @Test fun safetyRefusesPrivateIncognitoCompositionBusyHiddenLockAndUnknown() {
        assertTrue(safety.eligible)
        val refused=listOf(safety.copy(inputType=0),safety.copy(inputType=129),safety.copy(inputType=225),
            safety.copy(inputType=18),safety.copy(imeOptions=0x1000000),safety.copy(visible=false),
            safety.copy(interactive=false),safety.copy(unlocked=false),safety.copy(composing=true),safety.copy(busy=true))
        assertTrue(refused.none{it.eligible})
    }
    @Test fun cancelledOperationRetainsGlobalCapacityUntilActualFinish() {
        val owner=ProductionStickerOperationAdmission();val first=owner.begin()!!
        first.cancel();assertFalse(first.isLive());assertTrue(owner.busy());assertNull(owner.begin())
        assertFalse(owner.finish(ProductionStickerOperationAdmission.Ticket()));assertTrue(owner.busy())
        assertTrue(owner.finish(first));assertFalse(owner.finish(first));assertFalse(owner.busy())
        val next=owner.begin()!!;assertTrue(next.isLive());assertFalse(first.isLive())
        assertFalse(owner.finish(first));assertTrue(owner.busy());assertTrue(owner.finish(next))
    }
    @Test fun cancelDuringRealBlockedWorkDoesNotReopenAdmission() {
        val owner=ProductionStickerOperationAdmission();val first=owner.begin()!!
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1);val failure=AtomicReference<Throwable>()
        val thread=Thread{try{entered.countDown();check(release.await(5,TimeUnit.SECONDS))}catch(t:Throwable){failure.set(t)}finally{owner.finish(first);done.countDown()}}.apply{isDaemon=true;start()}
        try{assertTrue(entered.await(5,TimeUnit.SECONDS));first.cancel();assertTrue(owner.busy());assertNull(owner.begin());assertEquals(1L,done.count)}
        finally{release.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));thread.join(1000)}
        assertFalse(thread.isAlive);assertNull(failure.get());assertFalse(owner.busy())
    }
    @Test fun blockedGrantRetiresOnlyAfterActualLateGrantReturn() {
        val publication=ProductionStickerGrantPublication();val permission=AtomicBoolean(false);val revokes=AtomicInteger()
        val revocation=StickerGrantRevocation{revokes.incrementAndGet();permission.set(false)}
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1);val failure=AtomicReference<Throwable>()
        val thread=Thread{try{
            entered.countDown();check(release.await(5,TimeUnit.SECONDS));permission.set(true)
        }catch(t:Throwable){failure.set(t)}finally{if(publication.finishGrant())revocation.request();done.countDown()}}.apply{isDaemon=true;start()}
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertFalse(publication.requestRetirement());assertTrue(publication.retirementRequested())
            assertEquals(0,revokes.get());assertFalse(permission.get());assertEquals(StickerGrantRevocation.State.NOT_STARTED,revocation.state)
        }finally{release.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));thread.join(1000)}
        assertFalse(thread.isAlive);assertNull(failure.get());assertFalse(permission.get());assertEquals(1,revokes.get())
        if(publication.requestRetirement())revocation.request()
        assertEquals(1,revokes.get());assertEquals(StickerGrantRevocation.State.SUCCEEDED,revocation.state)
    }
    @Test fun grantCompletionThenRetirementAndPriorRetirementAreBothOneWay() {
        val late=ProductionStickerGrantPublication();assertFalse(late.finishGrant());assertFalse(late.retirementRequested());assertTrue(late.requestRetirement())
        val early=ProductionStickerGrantPublication();assertFalse(early.requestRetirement());assertFalse(early.requestRetirement())
        assertTrue(early.finishGrant());assertTrue(early.requestRetirement());assertTrue(early.retirementRequested())
    }
    @Test fun contactRequiresExactEditorWindowFactsMimeSafetyAndLibraryNotTextAba() {
        val connection=Any();val window=Any();val epoch=Any();val cursor=ImeCursor("dev.synthetic.editor",1,0,0,"","",false)
        fun stamp(conn:Any=connection,pos:ImeCursor=cursor,win:Any=window,facts:ProductionStickerReceiverFacts=admit()!!,
            types:Array<String>?=arrayOf("image/png"),safe:ProductionStickerSafety=safety,library:Any=epoch)=
            ProductionStickerContextStamp(conn,pos,win,facts,types,safe,library)
        val original=stamp();assertTrue(original.matches(stamp()));assertTrue(original.matches(stamp(types=arrayOf("IMAGE/PNG"))))
        val refused=listOf(stamp(conn=Any()),stamp(pos=cursor.copy(fieldId=2)),stamp(win=Any()),stamp(facts=admit(version=2)!!),
            stamp(types=arrayOf("image/*")),stamp(types=null),stamp(types=arrayOf("image/png;foo=bar")),
            stamp(safe=safety.copy(imeOptions=1)),stamp(safe=safety.copy(visible=false)),stamp(library=Any()))
        assertTrue(refused.none{original.matches(it)})
        assertFalse(stamp(types=null).matches(stamp(types=null)))
        assertEquals("ProductionStickerContextStamp(redacted)",original.toString())
    }
    @Test fun preLoopSetupFailureAttemptsErrorCloseAndDoesNotPretendThreadDrained() {
        val enteredClose=CountDownLatch(1);val releaseClose=CountDownLatch(1);val done=CountDownLatch(1)
        val closes=AtomicInteger();val quarantines=AtomicInteger();val success=AtomicReference<Boolean>()
        val slot=ProductionStickerOperationAdmission();val ticket=slot.begin()!!
        val task=ProductionStickerWriterTask({throw OutOfMemoryError("synthetic setup failure")},{accepted->
            closes.incrementAndGet();success.set(accepted);enteredClose.countDown();check(releaseClose.await(5,TimeUnit.SECONDS))
        },{quarantines.incrementAndGet()})
        val thread=Thread{try{task.run()}finally{done.countDown()}}.apply{isDaemon=true;start()}
        try {
            assertTrue(enteredClose.await(5,TimeUnit.SECONDS));assertFalse(success.get());assertTrue(thread.isAlive)
            assertEquals(1L,done.count);assertTrue(slot.busy());assertNull(slot.begin())
        }finally{releaseClose.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));thread.join(1000)}
        assertFalse(thread.isAlive);assertEquals(1,closes.get());assertEquals(0,quarantines.get())
        assertTrue(slot.busy()) // Actual monitor, not task/cancel, owns capacity retirement.
        assertTrue(slot.finish(ticket));task.run();assertEquals(1,closes.get())
    }
    @Test fun closeFailureIsQuarantinedNotReportedAsSuccessfulClose() {
        val closes=AtomicInteger();val quarantines=AtomicInteger()
        val task=ProductionStickerWriterTask({throw IllegalStateException("synthetic clock failure")},{success->
            assertFalse(success);closes.incrementAndGet();throw IllegalStateException("synthetic close failure")
        },{quarantines.incrementAndGet()})
        val thread=Thread(task).apply{isDaemon=true;start()};thread.join(1000)
        assertFalse(thread.isAlive);assertEquals(1,closes.get());assertEquals(1,quarantines.get())
    }

}
