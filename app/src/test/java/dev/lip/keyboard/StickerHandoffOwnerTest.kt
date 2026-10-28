package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StickerHandoffOwnerTest {
    private fun asset(commit:String="00000000-0000-0000-0000-000000000001",archive:String="a".repeat(64),
        id:String="synthetic-face",member:String="b".repeat(64),mime:String="image/png",bytes:Int=64)=
        StickerAssetIdentity(commit,archive,id,member,mime,bytes)
    private class Fixture {
        val session=ImeSession().apply{beginEditor()}
        val owner=StickerHandoffOwner(session)
        val connection=Any();val window=Any()
        val receiver=StickerReceiverBinding("owned.fixture",12001)
        val cursor=ImeCursor(receiver.packageName,7,2,2,"ab","cd",false)
    }
    private fun begin(f:Fixture,asset:StickerAssetIdentity=asset(),mime:Array<String> =arrayOf("image/png"))=
        f.owner.begin(asset,f.cursor,f.connection,f.window,f.receiver,mime,false)!!
    private fun claim(f:Fixture,r:StickerHandoffRequest,asset:StickerAssetIdentity=r.asset,mime:Array<String> =arrayOf("image/png"))=
        f.owner.claim(r,asset,f.cursor,f.connection,f.window,f.receiver,mime,false)

    @Test fun immutableIdentityHasExactEqualityBoundsAndRedactedDefaults(){
        val a=asset();val b=asset(mime="IMAGE/PNG")
        assertEquals(a,b);assertEquals(a.hashCode(),b.hashCode());assertEquals("image/png",b.mime)
        asset(bytes=1);asset(bytes=8*1024*1024);asset(id="a");asset(id="a".repeat(64));asset(mime="image/webp")
        val variants=listOf(asset(commit="00000000-0000-0000-0000-000000000002"),asset(archive="c".repeat(64)),asset(id="different"),asset(member="d".repeat(64)),asset(mime="image/webp"),asset(bytes=65))
        variants.forEach{assertNotEquals(a,it)}
        val f=Fixture();val request=begin(f)
        for(value in listOf(a.toString(),f.receiver.toString(),request.toString(),f.owner.toString())){
            assertTrue(value.contains("redacted"));assertFalse(value.contains("owned.fixture"));assertFalse(value.contains("synthetic-face"));assertFalse(value.contains(a.commitId));assertFalse(value.contains(a.archiveSha256))
        }
    }
    @Test fun malformedIdentityAndReceiverAreRefusedWithoutInputInMessages(){
        for(work in listOf<()->Any>(
            {asset(commit="private-test-payload")},{asset(commit="00000000-0000-0000-0000-00000000000A")},
            {asset(archive="A".repeat(64))},{asset(member="b".repeat(63))},
            {asset(id="private/test-payload")},{asset(id="../private-test-payload")},{asset(id="A")},{asset(id="-a")},{asset(id="a-")},{asset(id="a".repeat(65))},
            {asset(mime="image/gif")},{asset(mime=" image/png")},{asset(mime="image/png; charset=utf8")},
            {asset(bytes=0)},{asset(bytes=8*1024*1024+1)},
            {StickerReceiverBinding("private-test-payload",12001)},{StickerReceiverBinding("owned.fixture ",12001)},
            {StickerReceiverBinding("owned.fixture",0)},{StickerReceiverBinding("owned.fixture",-1)}
        )){
            val e=assertThrows(IllegalArgumentException::class.java){work()};assertFalse(e.message.orEmpty().contains("private-test-payload"))
        }
        assertEquals(StickerReceiverBinding("owned.fixture",12001),StickerReceiverBinding("owned.fixture",12001))
        assertNotEquals(StickerReceiverBinding("owned.fixture",12001),StickerReceiverBinding("owned.fixture",12002))
    }
    @Test fun onlyExactOriginalAndExplicitImageWildcardsMatchCaseInsensitively(){
        for(original in listOf("image/png","image/webp","IMAGE/PNG"))for(accepted in listOf(original,"IMAGE/*","*/*"))
            assertTrue(StickerHandoffMime.accepts(original,arrayOf(accepted)))
        assertTrue(StickerHandoffMime.accepts("image/png",arrayOf("text/plain","image/png")))
        for(types in listOf<Array<String>?>(null,emptyArray(),arrayOf("image/gif"),arrayOf("text/*"),arrayOf("*/png"),arrayOf(" image/png"),arrayOf("image/png "),arrayOf("image/png;charset=utf8"),arrayOf("image/%70ng"),arrayOf("image%2Fpng"),arrayOf("image/png\n"),arrayOf("image/png","bad value")))
            assertFalse(StickerHandoffMime.accepts("image/png",types))
        assertFalse(StickerHandoffMime.accepts("image/png",arrayOf("image/webp")))
        assertFalse(StickerHandoffMime.accepts("image/webp",arrayOf("image/png")))
        assertFalse(StickerHandoffMime.accepts(" image/png",arrayOf("*/*")))
    }
    @Test fun onePendingNoSupersessionAndSuccessfulClaimIsOneShot(){
        val f=Fixture();val r=begin(f)
        assertNull(f.owner.begin(asset(),f.cursor,f.connection,f.window,f.receiver,arrayOf("image/png"),false))
        assertSame(r,f.owner.current);assertTrue(claim(f,r));assertNull(f.owner.current);assertFalse(claim(f,r))
    }
    @Test fun exactFailedClaimConsumesEvenIfLaterRetriedWithCorrectValues(){
        val f=Fixture();val r=begin(f)
        assertFalse(claim(f,r,asset(bytes=65)));assertNull(f.owner.current)
        assertFalse(claim(f,r));assertNotNull(begin(f))
    }
    @Test fun changedAssetIdentityFieldsAlwaysRefuse(){
        val variants=listOf(asset(commit="00000000-0000-0000-0000-000000000002"),asset(archive="c".repeat(64)),asset(id="other"),asset(member="d".repeat(64)),asset(mime="image/webp"),asset(bytes=65))
        for(changed in variants){val f=Fixture();val r=begin(f);assertFalse(claim(f,r,changed));assertNull(f.owner.current)}
    }
    @Test fun connectionWindowAndActualReceiverMustMatchExactly(){
        for(change in 0..5){val f=Fixture();val r=begin(f)
            val connection=if(change==0)Any() else if(change==1)null else f.connection
            val window=if(change==2)Any() else if(change==3)null else f.window
            val receiver=if(change==4)StickerReceiverBinding("owned.fixture",12002) else if(change==5)null else f.receiver
            assertFalse(f.owner.claim(r,r.asset,f.cursor,connection,window,receiver,arrayOf("image/png"),false));assertNull(f.owner.current)
        }
        val f=Fixture();val r=begin(f)
        assertFalse(f.owner.claim(r,r.asset,f.cursor,f.connection,f.window,StickerReceiverBinding("other.fixture",12001),arrayOf("image/png"),false))
    }
    @Test fun receiverPackageAlsoMustBeTheCapturedEditorPackage(){
        val f=Fixture()
        assertNull(f.owner.begin(asset(),f.cursor,f.connection,f.window,StickerReceiverBinding("other.fixture",12001),arrayOf("image/png"),false))
        val r=begin(f)
        assertFalse(f.owner.claim(r,r.asset,f.cursor.copy(packageName="other.fixture"),f.connection,f.window,f.receiver,arrayOf("image/png"),false))
    }
    @Test fun changedCursorFieldSelectionContextOrProtectionConsumesRequest(){
        val f=Fixture()
        val changed=listOf(f.cursor.copy(fieldId=8),f.cursor.copy(start=3,end=3),f.cursor.copy(end=3),f.cursor.copy(before="zz"),f.cursor.copy(after="zz"),f.cursor.copy(protectedField=true),f.cursor.copy(start=-1,end=-1))
        for(cursor in changed){val r=begin(f);assertFalse(f.owner.claim(r,r.asset,cursor,f.connection,f.window,f.receiver,arrayOf("image/png"),false));assertNull(f.owner.current)}
    }
    @Test fun composingProtectedMissingOrInactiveInputsNeverBegin(){
        val f=Fixture()
        assertNull(f.owner.begin(asset(),f.cursor,f.connection,f.window,f.receiver,arrayOf("image/png"),true))
        for(cursor in listOf(null,f.cursor.copy(protectedField=true),f.cursor.copy(end=3),f.cursor.copy(before="x".repeat(65))))
            assertNull(f.owner.begin(asset(),cursor,f.connection,f.window,f.receiver,arrayOf("image/png"),false))
        assertNull(f.owner.begin(asset(),f.cursor,null,f.window,f.receiver,arrayOf("image/png"),false))
        assertNull(f.owner.begin(asset(),f.cursor,f.connection,null,f.receiver,arrayOf("image/png"),false))
        assertNull(f.owner.begin(asset(),f.cursor,f.connection,f.window,null,arrayOf("image/png"),false))
        assertNull(f.owner.begin(asset(),f.cursor,f.connection,f.window,f.receiver,null,false))
        f.session.finishEditor();assertNull(f.owner.begin(asset(),f.cursor,f.connection,f.window,f.receiver,arrayOf("image/png"),false))
        val active=Fixture();val r=begin(active)
        assertFalse(active.owner.claim(r,r.asset,active.cursor,active.connection,active.window,active.receiver,arrayOf("image/png"),true));assertNull(active.owner.current)
    }
    @Test fun editorIncarnationAndRevisionAbaRefuseIdenticalCursor(){
        for(change in 0..2){val f=Fixture();val r=begin(f)
            when(change){0->f.session.editorChanged();1->f.session.beginEditor();else->{f.session.finishEditor();f.session.beginEditor()}}
            assertFalse(claim(f,r));assertNull(f.owner.current)
        }
    }
    @Test fun cancellationAndOldCompletionNeverConsumeNewPendingRequest(){
        val f=Fixture();val old=begin(f);assertTrue(f.owner.cancel(old));assertFalse(f.owner.cancel(old))
        val current=begin(f);assertFalse(claim(f,old));assertFalse(f.owner.cancel(old));assertSame(current,f.owner.current)
        assertTrue(claim(f,current));assertFalse(f.owner.cancel())
        val next=begin(f);assertTrue(f.owner.cancel());assertFalse(claim(f,next));assertNull(f.owner.current)
    }
    @Test fun advertisedArrayIsCopiedAndCapabilityChangesRequireNewGesture(){
        val f=Fixture();val input=arrayOf("image/png","text/plain");val r=begin(f,mime=input)
        assertThrows(UnsupportedOperationException::class.java){(r.advertised as MutableSet<String>).clear()}
        input[0]="image/webp"
        assertTrue(claim(f,r,mime=arrayOf("TEXT/PLAIN","IMAGE/PNG","image/png")))
        for(changed in listOf(arrayOf("image/*"),arrayOf("*/*"),arrayOf("image/png","text/plain"),arrayOf("image/webp"),arrayOf(" image/png"))){
            val next=begin(f);assertFalse(claim(f,next,mime=changed));assertNull(f.owner.current)
        }
        val wildcard=begin(f,mime=arrayOf("image/*"));assertTrue(claim(f,wildcard,mime=arrayOf("IMAGE/*")))
    }
    @Test fun synchronizedBeginAndClaimAllowOnlyOneWinner(){
        val f=Fixture();val gate=CountDownLatch(1);val done=CountDownLatch(8);val requests=java.util.Collections.synchronizedList(ArrayList<StickerHandoffRequest>())
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val threads=(0 until 8).map{Thread{try{check(gate.await(5,TimeUnit.SECONDS));f.owner.begin(asset(),f.cursor,f.connection,f.window,f.receiver,arrayOf("image/png"),false)?.let{requests.add(it)}}catch(t:Throwable){failure.compareAndSet(null,t)}finally{done.countDown()}}.apply{isDaemon=true;start()}}
        gate.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));threads.forEach{it.join(1000)};assertTrue(threads.none{it.isAlive});assertNull(failure.get());assertEquals(1,requests.size)
        val r=requests.single();val wins=AtomicInteger();val claimGate=CountDownLatch(1);val claimsDone=CountDownLatch(8)
        val claimers=(0 until 8).map{Thread{try{check(claimGate.await(5,TimeUnit.SECONDS));if(claim(f,r))wins.incrementAndGet()}catch(t:Throwable){failure.compareAndSet(null,t)}finally{claimsDone.countDown()}}.apply{isDaemon=true;start()}}
        claimGate.countDown();assertTrue(claimsDone.await(5,TimeUnit.SECONDS));claimers.forEach{it.join(1000)}
        assertTrue(claimers.none{it.isAlive});assertNull(failure.get());assertEquals(1,wins.get());assertNull(f.owner.current)
    }
}
