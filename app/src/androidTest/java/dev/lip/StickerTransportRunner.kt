package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import dev.lip.keyboard.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Private transport ONLY. No Activity, IME, clipboard, permission settings, external app or send. */
class StickerTransportRunner:Instrumentation(){
    private val descriptor="dev.lip.sticker.receiver.fixture.v1"
    private var args=Bundle()
    private var owner=""
    private var stage="guard"
    private var deadline=0L
    private val cases=JSONArray()
    private val connections=ArrayList<Link>()
    private val leases=ArrayList<StickerPrivateTransport.Issued>()
    private val grantAdmissions=LinkedHashSet<Uri>()
    private val readActors=ArrayList<Thread>()
    private val readReleases=ArrayList<CountDownLatch>()
    private var grantCleanupObserved=0
    private var mode="full"
    private class Link(val component:ComponentName){
        val connected=CountDownLatch(1);val died=CountDownLatch(1)
        @Volatile var binder:IBinder?=null
        @Volatile var attempted=false
        @Volatile var returned=false
        @Volatile var held=false
        @Volatile var admitted=false
        @Volatile var observed=false
        @Volatile var accepting=true
        @Volatile var unbound=false
        @Volatile var error=false
        lateinit var connection:ServiceConnection
    }
    private data class Read(val success:Boolean,val bytes:Int,val sha:String,val mime:String?,val error:String)
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);args=arguments?:Bundle();start()}
    private fun must(value:Boolean,token:String){if(!value)throw AssertionError(token)}
    private fun budget(){must(SystemClock.elapsedRealtime()<deadline,"TRANSPORT_DRIVER_DEADLINE")}
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun <T> ui(action:()->T):T {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return action()
        val result=AtomicReference<T>();val failure=AtomicReference<Throwable>()
        runOnMainSync{try{result.set(action())}catch(t:Throwable){failure.set(t)}}
        failure.get()?.let{throw it};return result.get()
    }
    private fun close(link:Link){
        ui{
            link.accepting=false
            if(link.admitted&&!link.unbound){
                try{targetContext.unbindService(link.connection);link.unbound=true}catch(_:Exception){link.error=true}
            }
        }
    }
    private fun bind(isolated:Boolean=false):Link {
        val name="dev.lip.testing.StickerReceiverService"+if(isolated)"\$Isolated" else ""
        val link=Link(ComponentName(StickerPrivateTransport.RECEIVER,name));connections.add(link)
        link.connection=object:ServiceConnection{
            override fun onServiceConnected(name:ComponentName,binder:IBinder){
                if(name!=link.component){link.error=true;return}
                link.observed=true
                if(!link.accepting){close(link);return}
                link.binder=binder
                try{binder.linkToDeath({link.died.countDown()},0)}catch(_:Exception){link.error=true}
                link.connected.countDown()
            }
            override fun onServiceDisconnected(name:ComponentName){
                if(name==link.component){link.died.countDown();close(link)}else link.error=true
            }
            override fun onNullBinding(name:ComponentName){link.error=true;link.observed=true;link.connected.countDown();close(link)}
            override fun onBindingDied(name:ComponentName){link.error=true;link.observed=true;link.died.countDown();close(link)}
        }
        ui{
            link.attempted=true
            link.admitted=targetContext.bindService(Intent().setComponent(link.component),link.connection,Context.BIND_AUTO_CREATE)
            link.returned=true
        }
        must(link.admitted&&link.connected.await(8,TimeUnit.SECONDS)&&!link.error&&link.binder!=null,"EXACT_RECEIVER_BIND_DEADLINE")
        return link
    }
    private fun <T> call(link:Link,code:Int,write:(Parcel)->Unit={},read:(Parcel)->T):T {
        budget();val data=Parcel.obtain();val reply=Parcel.obtain()
        try{
            data.writeInterfaceToken(descriptor);write(data)
            must(link.binder?.transact(code,data,reply,0)==true,"RECEIVER_TRANSACTION_FAILED")
            reply.readException();val result=read(reply);reply.enforceNoDataAvail();return result
        }finally{data.recycle();reply.recycle()}
    }
    private fun hello(link:Link)=call(link,1,read={p->p.readInt() to p.readInt()})
    private fun read(link:Link,uri:Uri,mode:String="r")=call(link,2,{it.writeString(uri.toString());it.writeString(mode)}){p->
        Read(p.readInt()==1,p.readInt(),p.readString().orEmpty(),p.readString(),p.readString().orEmpty())
    }
    private fun simple(link:Link,code:Int,uri:Uri?=null):Pair<Boolean,String> {
        if(code==3)link.held=true // Admission precedes IPC; uncertain outcome must still close the exact receiver FD.
        val result=call(link,code,{if(uri!=null)it.writeString(uri.toString())}){p->
            val ok=p.readInt()==1;val error=p.readString().orEmpty();ok to error
        }
        if(code==4&&result.first)link.held=false
        return result
    }
    private fun granted(link:Link,uri:Uri):Boolean {
        val result=simple(link,6,uri);must(result.second.isEmpty(),"PERMISSION_OBSERVATION_FAILED");return result.first
    }
    private fun grant(uri:Uri){
        grantAdmissions.add(uri) // Admission precedes IPC, including all malformed-route probes.
        targetContext.grantUriPermission(StickerPrivateTransport.RECEIVER,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private fun permission(uri:Uri):Boolean=targetContext.checkUriPermission(uri,-1,
        StickerPrivateTransport.receiver(targetContext).uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun idle(timeout:Long=3_000):Long {
        val start=SystemClock.elapsedRealtime()
        do{
            budget();val s=StickerPrivateTransport.snapshot()
            if(!s.active&&!s.writerAlive&&s.queuedTasks==0){must(s.lastWriterExited&&s.revocationCallSucceeded,"ACTUAL_TRANSPORT_CLEANUP_UNCONFIRMED");return SystemClock.elapsedRealtime()-start}
            if(s.writerAlive)must(s.active,"LIVE_WRITER_CAPACITY_RELEASED_EARLY")
            SystemClock.sleep(50)
        }while(SystemClock.elapsedRealtime()-start<timeout)
        throw AssertionError("ACTUAL_WRITER_DRAIN_DEADLINE")
    }
    private data class Original(val asset:StickerAssetIdentity,val bytes:ByteArray)
    private fun original(commit:String,id:String,expectedArchive:String,expectedSha:String?=null):Original {
        val length=when(id){"red"->78;"animated"->140;"wechat-face-000"->5809;else->throw AssertionError("KNOWN_FIXTURE_MEMBER_REQUIRED")}
        val expected=StickerAssetIdentity(commit,expectedArchive,id,checkNotNull(expectedSha),if(id=="animated")"image/webp" else "image/png",length)
        val loaded=StickerOriginalLoader.read(targetContext,expected)
        val bytes=loaded.copyBytes()
        must(loaded.asset==expected&&hash(bytes)==expected.memberSha256,"KNOWN_ORIGINAL_MEMBER_BINDING")
        return Original(loaded.asset,bytes)
    }
    private fun originalRefusal(reason:StickerOriginalException.Reason,work:()->Any?){
        val error=runCatching{work()}.exceptionOrNull()
        must(error is StickerOriginalException&&error.reason==reason,"ORIGINAL_REFUSAL_MISMATCH")
    }
    private fun loaderChecks(original:Original){
        val a=original.asset
        val changed=listOf(
            StickerAssetIdentity(a.commitId,"e".repeat(64),a.stickerId,a.memberSha256,a.mime,a.byteLength),
            StickerAssetIdentity(a.commitId,a.archiveSha256,"missing-member",a.memberSha256,a.mime,a.byteLength),
            StickerAssetIdentity(a.commitId,a.archiveSha256,a.stickerId,"e".repeat(64),a.mime,a.byteLength),
            StickerAssetIdentity(a.commitId,a.archiveSha256,a.stickerId,a.memberSha256,"image/webp",a.byteLength),
            StickerAssetIdentity(a.commitId,a.archiveSha256,a.stickerId,a.memberSha256,a.mime,a.byteLength+1))
        for(asset in changed)originalRefusal(StickerOriginalException.Reason.INTEGRITY){StickerOriginalLoader.read(targetContext,asset)}
        originalRefusal(StickerOriginalException.Reason.CANCELED){StickerOriginalLoader.read(targetContext,a){false}}
        originalRefusal(StickerOriginalException.Reason.CANCELED){StickerOriginalLoader.read(targetContext,a){throw IllegalStateException("Synthetic cancellation")}}
        var calls=0
        originalRefusal(StickerOriginalException.Reason.CANCELED){StickerOriginalLoader.read(targetContext,a){++calls!=2}}
        must(calls==2,"CANCELED_LOADER_PREDICATE_REVIVED")
        ui{originalRefusal(StickerOriginalException.Reason.STATE){StickerOriginalLoader.read(targetContext,a)}}
        pass("loader-identity-cancel-main-thread-refusals",JSONObject().put("identityVariants",changed.size).put("stickyCalls",calls))
        val entered=CountDownLatch(1);val release=CountDownLatch(1);readReleases.add(release)
        val canceled=java.util.concurrent.atomic.AtomicBoolean(false);val blocked=java.util.concurrent.atomic.AtomicBoolean(false)
        val failure=AtomicReference<Throwable?>()
        val thread=Thread({
            try{StickerOriginalLoader.read(targetContext,a){
                val storeFrame=Thread.currentThread().stackTrace.any{it.className.startsWith("dev.lip.stickers.StickerPackTransactionStore")}
                if(storeFrame&&blocked.compareAndSet(false,true)){entered.countDown();release.await(5,TimeUnit.SECONDS)}
                !canceled.get()
            };failure.set(AssertionError("CANCELED_ORIGINAL_READ_SUCCEEDED"))}catch(t:Throwable){failure.set(t)}
        },"lip-original-loader-admission-fixture")
        readActors.add(thread);thread.start()
        try{
            must(entered.await(4,TimeUnit.SECONDS)&&thread.isAlive,"ACTUAL_STORE_CALLBACK_NOT_BLOCKED")
            canceled.set(true)
            originalRefusal(StickerOriginalException.Reason.BUSY){StickerOriginalLoader.read(targetContext,a)}
        }finally{canceled.set(true);release.countDown();thread.join(5_000)}
        must(!thread.isAlive&&failure.get() is StickerOriginalException&&(failure.get() as StickerOriginalException).reason==StickerOriginalException.Reason.CANCELED,"CANCELED_ORIGINAL_READ_NOT_DRAINED")
        val fresh=StickerOriginalLoader.read(targetContext,a);must(hash(fresh.copyBytes())==a.memberSha256,"FRESH_ORIGINAL_AFTER_DRAIN_MISMATCH")
        pass("loader-cancel-retains-admission-until-actual-store-stack-drain",JSONObject().put("scope","live callback blocked inside real Store.read, NOT simulated OS disk stall").put("ownedThreadExited",true))
    }
    private fun issue(original:Original):StickerPrivateTransport.Issued {
        val lease=StickerPrivateTransport.issue(targetContext,original.asset,StickerPrivateTransport.receiver(targetContext),original.bytes)
        leases.add(lease);return lease
    }
    private fun correct(read:Read,original:Original){
        must(read.success&&read.error.isEmpty()&&read.bytes==original.bytes.size&&read.sha==original.asset.memberSha256&&read.mime==original.asset.mime,"INDEPENDENT_RECEIVER_ORIGINAL_BYTES_MISMATCH")
    }
    private fun denied(read:Read){must(!read.success&&read.bytes==0&&read.sha.isEmpty(),"UNEXPECTED_RECEIVER_ACCESS")}
    private fun pass(name:String,extra:JSONObject=JSONObject()){cases.put(extra.put("case",name).put("passed",true))}
    private fun awaitBlocked(){
        val until=SystemClock.elapsedRealtime()+3_000
        do{budget();val s=StickerPrivateTransport.snapshot()
            if(s.active&&s.writerAlive&&s.bytesWritten in 1 until 8L*1024*1024)return
            SystemClock.sleep(50)
        }while(SystemClock.elapsedRealtime()<until)
        throw AssertionError("ACTUAL_STALLED_PIPE_NOT_OBSERVED")
    }
    private fun cleanup():Boolean {
        deadline=SystemClock.elapsedRealtime()+15_000
        var ok=true
        readReleases.forEach{it.countDown()}
        readActors.forEach{thread->if(thread.isAlive)thread.interrupt();runCatching{thread.join(5_000)}.onFailure{ok=false};if(thread.isAlive)ok=false}
        leases.forEach{runCatching{StickerPrivateTransport.revoke(it)}.onFailure{ok=false}}
        for(link in connections){
            if(link.held){
                if(link.binder?.isBinderAlive==true)runCatching{must(simple(link,4).first,"HELD_DESCRIPTOR_CLEANUP_FAILED")}.onFailure{ok=false}
                else if(link.died.count==0L)link.held=false else ok=false
            }
            runCatching{close(link)}.onFailure{ok=false}
            if(link.attempted&&!link.returned)ok=false
            if(link.admitted&&(!link.observed||!link.unbound||link.error||link.held))ok=false
        }
        runCatching{idle(10_000)}.onFailure{ok=false}
        for(uri in grantAdmissions){
            runCatching{
                // Establish the current outcome before any protective cleanup; never blindly retry a revoke.
                if(permission(uri))targetContext.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                must(!permission(uri),"TRACKED_URI_GRANT_CLEANUP_UNCONFIRMED");grantCleanupObserved++
            }.onFailure{ok=false}
        }
        return ok
    }
    override fun onStart(){
        val report=JSONObject().put("runnerVersion",1).put("passed",false)
            .put("scope","actual private provider/pipe/temporarygrant to independent owned service;NOTIME/commitContent/realapp/send/visual/physicalproof")
        var outcome=Activity.RESULT_CANCELED
        try{
            must(targetContext.packageName==StickerPrivateTransport.APP&&context.packageName==StickerPrivateTransport.RECEIVER&&android.os.Build.HARDWARE=="ranchu"&&StickerPrivateTransport.enabled(targetContext),"OWNED_TRANSPORT_GUARD")
            owner=args.getString("owner")?:throw AssertionError("OWNER_REQUIRED");must(UUID.fromString(owner).toString()==owner,"CANONICAL_OWNER_REQUIRED")
            mode=args.getString("mode","full");must(mode in listOf("full","core","loader-core"),"EXPLICIT_TRANSPORT_MODE_REQUIRED")
            report.put("owner",owner).put("mode",mode);deadline=SystemClock.elapsedRealtime()+210_000
            stage="bind";val receiver=bind();val hello=hello(receiver);val target=StickerPrivateTransport.receiver(targetContext)
            must(hello.first==target.uid&&hello.second!=android.os.Process.myPid(),"INDEPENDENT_RECEIVER_IDENTITY")
            report.put("senderPid",android.os.Process.myPid()).put("receiverPid",hello.second).put("receiverUid",hello.first)
            val png=original("8cc8db1d-6255-40df-90f4-815329caa03d","red","fbb2e463bb624120710566fd8877c49a3c0c045500e562b680739ef3f10cf1ec","df75ecf5f620d125e80ae77bf639cecad76878216cdafb71afef124b04bbf5dc")
            val webp=original("8cc8db1d-6255-40df-90f4-815329caa03d","animated","fbb2e463bb624120710566fd8877c49a3c0c045500e562b680739ef3f10cf1ec","980b75994d7149d6135819f1dc0cd3c892d7791536c605e520581b077b4e0b91")
            if(mode=="loader-core"){
                stage="receiver-partial-counter"
                val partial=call(receiver,7,read={p->Read(p.readInt()==1,p.readInt(),p.readString().orEmpty(),p.readString(),p.readString().orEmpty())})
                must(!partial.success&&partial.bytes==3&&partial.sha.isEmpty()&&partial.error=="IOException","RECEIVER_PARTIAL_COUNT_WAS_MASKED")
                pass("independent-receiver-preserves-partial-byte-count",JSONObject().put("actualPrefixBytes",partial.bytes).put("scope","shared consumer helper synthetic failing stream,NOTproviderfault"))
                stage="original-loader-refusals";loaderChecks(png)
            }
            for((name,source) in listOf("original-png" to png,"original-webp" to webp)){
                stage=name;val issued=issue(source);must(!granted(receiver,issued.uri),"UNEXPECTED_INITIAL_URI_GRANT");denied(read(receiver,issued.uri))
                grant(issued.uri);must(granted(receiver,issued.uri),"TEMPORARY_READ_GRANT_MISSING");correct(read(receiver,issued.uri),source);idle()
                must(!granted(receiver,issued.uri),"URI_GRANT_NOT_REVOKED");denied(read(receiver,issued.uri));pass(name,JSONObject().put("bytes",source.bytes.size).put("mime",source.asset.mime).put("sha256",source.asset.memberSha256))
            }
            stage="approved-local-face"
            val face=original("d9f81101-a53d-4edb-83a9-591e66e11cb6","wechat-face-000","ce2c6b33e09477f260140536df54ad82a1d51a698b11217e79763d3793f6b15a",checkNotNull(args.getString("expectedFaceSHA")))
            val selected=issue(face);grant(selected.uri);correct(read(receiver,selected.uri),face);idle();pass("approved-local-original-face",JSONObject().put("bytes",face.bytes.size).put("sha256",face.asset.memberSha256))
            stage="write-and-uri-negative"
            val guarded=issue(png);grant(guarded.uri)
            for(mode in listOf("w","rw"))denied(read(receiver,guarded.uri,mode))
            val bad=listOf(guarded.uri.buildUpon().appendQueryParameter("x","1").build(),guarded.uri.buildUpon().fragment("x").build(),
                guarded.uri.buildUpon().encodedPath("/v1/%2f"+guarded.uri.lastPathSegment).build(),guarded.uri.buildUpon().encodedPath("/v1/../"+guarded.uri.lastPathSegment).build(),
                guarded.uri.buildUpon().encodedPath("/v1/"+"0".repeat(80)).build(),guarded.uri.buildUpon().encodedPath("/v1").build())
            for(uri in bad){
                grant(uri)
                try{denied(read(receiver,uri))}finally{targetContext.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            }
            grant(guarded.uri);correct(read(receiver,guarded.uri),png);idle();pass("write-query-fragment-encoded-path-traversal-token-enumeration-denied")
            stage="platform-canonical-alias"
            val alias=issue(png);val uri=alias.uri.buildUpon().encodedPath("/v1//"+alias.uri.lastPathSegment).build()
            denied(read(receiver,uri));grant(alias.uri);val observed=read(receiver,uri)
            if(observed.success){correct(observed,png);idle();denied(read(receiver,alias.uri))}else{denied(observed);correct(read(receiver,alias.uri),png);idle()}
            pass("platform-repeated-slash-alias-capability-bound",JSONObject().put("normalizedReadAccepted",observed.success).put("noGrantDenied",true).put("sameSelectedBytesAndOneShot",true))
            stage="isolated-wrong-uid"
            val isolated=bind(true);val wrong=hello(isolated);must(wrong.first!=target.uid&&wrong.first!=android.os.Process.myUid(),"ACTUAL_WRONG_UID_REQUIRED")
            val private=issue(png);grant(private.uri)
            val wrongPermission=simple(isolated,6,private.uri)
            val wrongRead=read(isolated,private.uri)
            report.put("isolatedPermissionObservation",JSONObject().put("granted",wrongPermission.first).put("errorClass",wrongPermission.second).put("permissionState",if(wrongPermission.second.isEmpty())"OBSERVED_NOT_GRANTED" else "UNOBSERVABLE_API_REFUSED").put("readErrorClass",wrongRead.error))
            must(!wrongPermission.first,"ISOLATED_UID_INHERITED_GRANT")
            must(wrongRead.error in setOf("SecurityException","FileNotFoundException"),"ISOLATED_FAILURE_NOT_ACCESS_DENIAL")
            denied(wrongRead);correct(read(receiver,private.uri),png);idle();close(isolated)
            pass("isolated-wrong-uid-denied",JSONObject().put("isolatedUid",wrong.first).put("isolatedPid",wrong.second)
                .put("permissionQueryErrorClass",wrongPermission.second).put("actualReadErrorClass",wrongRead.error)
                .put("scope","different actual isolated UID denied;permission API may itself refuse;not proof isolated reached custom UID check"))
            stage="revoke-unopened-stale"
            val old=issue(png);grant(old.uri);must(StickerPrivateTransport.revoke(old),"CURRENT_REVOKE_FAILED");idle();must(!granted(receiver,old.uri),"REVOKED_GRANT_SURVIVED");denied(read(receiver,old.uri))
            val fresh=issue(png);grant(fresh.uri);must(!StickerPrivateTransport.revoke(old)&&granted(receiver,fresh.uri),"STALE_REVOKE_CANCELED_NEW_LEASE");correct(read(receiver,fresh.uri),png);idle();pass("unopened-and-stale-revocation")
            // Opaque synthetic transport bytes, deliberately NOT a valid decoded-image claim.
            val bytes=ByteArray(8*1024*1024){(it*31).toByte()}
            val large=Original(StickerAssetIdentity("00000000-0000-0000-0000-000000000044","a".repeat(64),"transport-stall",hash(bytes),"image/png",bytes.size),bytes)
            stage="stalled-reader-cancel"
            val stalled=issue(large);grant(stalled.uri);must(simple(receiver,3,stalled.uri).first,"OWNED_HOLD_FAILED");awaitBlocked()
            must(runCatching{issue(png)}.isFailure,"BUSY_WRITER_ALLOWED_NEW_ISSUE")
            val cancelStart=SystemClock.elapsedRealtime();StickerPrivateTransport.revoke(stalled);val drain=idle();must(simple(receiver,4).first,"HELD_FD_CLOSE_FAILED")
            pass("stalled-reader-cancel-actual-writer-drain",JSONObject().put("cancelToIdleMs",SystemClock.elapsedRealtime()-cancelStart).put("idleWaitMs",drain).put("opaqueSyntheticBytes",bytes.size))
            stage="stalled-reader-deadline"
            val timed=issue(large);grant(timed.uri);val timerStart=SystemClock.elapsedRealtime();must(simple(receiver,3,timed.uri).first,"OWNED_HOLD_FAILED");awaitBlocked();idle(11_000)
            val elapsed=SystemClock.elapsedRealtime()-timerStart;must(elapsed in 7_000..11_000,"ACTUAL_WRITER_DEADLINE_NOT_OBSERVED");must(simple(receiver,4).first,"HELD_FD_CLOSE_FAILED")
            pass("stalled-reader-eight-second-deadline",JSONObject().put("elapsedMs",elapsed))
            stage="receiver-self-death"
            val death=issue(large);grant(death.uri);must(simple(receiver,3,death.uri).first,"OWNED_HOLD_FAILED");awaitBlocked();must(simple(receiver,5).first,"SELF_EXIT_ACK_FAILED")
            must(receiver.died.await(8,TimeUnit.SECONDS),"EXACT_RECEIVER_DEATH_NOT_OBSERVED");idle();close(receiver)
            pass("actual-owned-receiver-self-death-and-drain")
            if(mode=="full"){
            stage="actual-expiry"
            val finalReceiver=bind();val newHello=hello(finalReceiver);must(newHello.first==target.uid&&newHello.second!=hello.second,"FRESH_RECEIVER_PROCESS_REQUIRED")
            val expiring=issue(png);grant(expiring.uri);must(granted(finalReceiver,expiring.uri),"TEMPORARY_READ_GRANT_MISSING")
            val expiryStart=SystemClock.elapsedRealtime();idle(125_000);val expiryElapsed=SystemClock.elapsedRealtime()-expiryStart
            must(expiryElapsed in 119_000..125_000&&!granted(finalReceiver,expiring.uri),"ACTUAL_LEASE_EXPIRY_NOT_OBSERVED");denied(read(finalReceiver,expiring.uri))
            pass("actual-unopened-two-minute-expiry",JSONObject().put("elapsedMs",expiryElapsed))
            }else report.put("omittedInCore",JSONArray(listOf("actual120-second-expiry; reuse retained full-v2 only with explicit source-delta review")))
            report.put("passed",true);outcome=Activity.RESULT_OK
        }catch(t:Throwable){report.put("failureStage",stage).put("failureType",t.javaClass.simpleName);if(t is AssertionError)report.put("failureToken",t.message)}
        finally{
            val cleaned=cleanup();report.put("owner",owner).put("cleanupConfirmed",cleaned).put("cases",cases)
            report.put("uriGrantAdmissions",grantAdmissions.size).put("uriGrantsObservedAbsentAfterCleanup",grantCleanupObserved)
            report.put("bindAdmissions",connections.count{it.admitted}).put("bindObservations",connections.count{it.observed}).put("exactUnbinds",connections.count{it.unbound})
            if(!cleaned){report.put("passed",false);outcome=Activity.RESULT_CANCELED}
            finish(outcome,Bundle().apply{putString("transportResult",report.toString())})
        }
    }
}
