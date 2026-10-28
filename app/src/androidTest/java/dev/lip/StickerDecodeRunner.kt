package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.content.*
import android.os.*
import dev.lip.stickers.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*

/** Real owned isolated Android codec, no editor/UI/receiver or source image installation. */
class StickerDecodeRunner:Instrumentation(){
    private lateinit var args:Bundle
    private val results=JSONArray()
    private fun requireValue(value:Boolean,reason:String){check(value){reason}}
    private fun fixture(owner:String,name:String):ByteArray{
        requireValue(name in setOf("red.png","red.webp","animated.webp","corrupt-pixels.png","corrupt-second-frame.webp","invalid-icc.png","expanded-icc.png","wide.png"),"Fixed fixture only")
        return File(targetContext.noBackupFilesDir,"sticker-decode-fixture-$owner/$name").readBytes()
    }
    private fun member(name:String,b:ByteArray)=StickerArchiveMember(name,b.copyOf())
    private fun red(p:SandboxedStickerPreview){
        val b=p.premultipliedRgbaSrgbBytes();requireValue(b.size==p.width*p.height*4,"RGBA bound")
        for(i in b.indices step 4)requireValue(b[i]==255.toByte()&&b[i+1]==0.toByte()&&b[i+2]==0.toByte()&&b[i+3]==255.toByte(),"Expected original red pixels")
        b.fill(0);requireValue(p.premultipliedRgbaSrgbBytes()[0]==255.toByte(),"Copy-safe pixels")
    }
    private fun record(name:String,p:SandboxedStickerPreview){
        requireValue(p.workerPid!=Process.myPid()&&p.workerUid!=Process.myUid()&&Process.isIsolatedUid(p.workerUid),"Actual separate isolated worker required")
        results.put(JSONObject().put("case",name).put("sha256",p.sourceSha256).put("width",p.width).put("height",p.height).put("frames",p.metadata.frames).put("durationMs",p.metadata.durationMs).put("workerPid",p.workerPid).put("workerUid",p.workerUid).put("observedPeakRssKb",p.observedPeakRssKb).put("rgbaBytes",p.byteCount))
    }
    private class Raw(val context:Context):AutoCloseable{
        val connected=CountDownLatch(1);val dead=CountDownLatch(1);var binder:IBinder?=null;private val bound=AtomicBoolean(false)
        private val connection=object:ServiceConnection{
            override fun onServiceConnected(n:ComponentName,b:IBinder){binder=b;b.linkToDeath({dead.countDown()},0);connected.countDown()}
            override fun onServiceDisconnected(n:ComponentName){dead.countDown();close()}
            override fun onNullBinding(n:ComponentName){connected.countDown();close()}
        }
        init{bound.set(context.bindService(Intent(context,StickerDecodeService::class.java),connection,Context.BIND_AUTO_CREATE));check(bound.get()&&connected.await(3,TimeUnit.SECONDS)&&binder!=null)}
        fun hello():IntArray{
            val d=Parcel.obtain();val r=Parcel.obtain()
            try{d.writeInterfaceToken(StickerDecodeWire.TOKEN);check(binder!!.transact(StickerDecodeWire.HELLO,d,r,0));val v=IntArray(5){r.readInt()};check(r.dataAvail()==0&&v[0]==1&&v[3]==1&&v[2]!=Process.myUid()&&Process.isIsolatedUid(v[2]));return v}finally{d.recycle();r.recycle()}
        }
        override fun close(){if(bound.compareAndSet(true,false))runCatching{context.unbindService(connection)}}
    }
    private fun waitCapacity(){
        val field=StickerDecoderSession::class.java.getDeclaredField("capacity").apply{isAccessible=true};val capacity=field.get(null) as AtomicBoolean
        val end=SystemClock.elapsedRealtime()+3000;while(capacity.get()&&SystemClock.elapsedRealtime()<end)Thread.sleep(20)
        requireValue(!capacity.get(),"Owned IPC/writer capacity did not drain")
    }
    private fun watchdog(){
        val raw=Raw(targetContext);val h=raw.hello();val pipe=ParcelFileDescriptor.createReliablePipe();val finished=CountDownLatch(1);val failed=AtomicBoolean(false)
        val start=SystemClock.elapsedRealtime()
        try{
            Thread({val d=Parcel.obtain();val r=Parcel.obtain();try{
                d.writeInterfaceToken(StickerDecodeWire.TOKEN);d.writeString(UUID.randomUUID().toString());d.writeString("0".repeat(64));pipe[0].writeToParcel(d,0)
                raw.binder!!.transact(StickerDecodeWire.DECODE,d,r,0)
            }catch(_:RemoteException){failed.set(true)}finally{d.recycle();r.recycle();finished.countDown()}},"fixture-stalled-codec").start()
            val end=start+2000;var active=false
            while(!active&&SystemClock.elapsedRealtime()<end){active=raw.hello()[4]==1;if(!active)Thread.sleep(20)}
            requireValue(active,"Stalled source must be accepted before measuring watchdog")
            requireValue(raw.dead.await(11,TimeUnit.SECONDS)&&finished.await(2,TimeUnit.SECONDS)&&failed.get(),"Watchdog must terminate only its own blocking worker")
            val elapsed=SystemClock.elapsedRealtime()-start;requireValue(elapsed in 7500..11000,"Observed watchdog outside declared scheduling tolerance")
            results.put(JSONObject().put("case","stalled-fd-watchdog").put("workerPid",h[1]).put("workerUid",h[2]).put("elapsedMs",elapsed).put("binderDied",true).put("ipcDrained",true))
        }finally{pipe.forEach{runCatching{it.close()}};raw.close()}
    }
    private fun canceledWriter(bytes:ByteArray){
        val session=StickerDecoderSession(targetContext);val entered=CountDownLatch(1);val release=CountDownLatch(1);val complete=CountDownLatch(1);val refused=AtomicBoolean(false)
        try{
            Thread({try{session.decode(member("synthetic",bytes)){
                if(Thread.currentThread().name=="lip-sticker-writer"){entered.countDown();release.await(5,TimeUnit.SECONDS)};true
            }}catch(e:StickerDecodeException){refused.set(e.reason==StickerDecodeException.Reason.CANCELED)}finally{complete.countDown()}},"fixture-codec-call").start()
            requireValue(entered.await(3,TimeUnit.SECONDS),"Controlled source writer barrier not entered")
            session.close();requireValue(complete.await(2,TimeUnit.SECONDS)&&refused.get(),"Closing active client must discard result")
            try{StickerDecoderSession(targetContext).close();error("Capacity released while source writer still blocked")}
            catch(e:StickerDecodeException){requireValue(e.reason==StickerDecodeException.Reason.BUSY,"Expected held capacity")}
            results.put(JSONObject().put("case","cancel-blocked-writer").put("discarded",true).put("slotHeldUntilDrain",true))
        }finally{release.countDown();session.close()}
        waitCapacity()
    }
    private fun probeAdmission(bytes:ByteArray){
        val session=StickerDecoderSession(targetContext);val entered=CountDownLatch(1);val release=CountDownLatch(1);val completed=CountDownLatch(1);val first=AtomicBoolean(true);val canceled=AtomicBoolean(false)
        try{
            Thread({try{session.decode(member("probe",bytes)){
                if(first.compareAndSet(true,false)){entered.countDown();release.await(5,TimeUnit.SECONDS)};true
            }}catch(e:StickerDecodeException){canceled.set(e.reason==StickerDecodeException.Reason.CANCELED)}finally{completed.countDown()}},"fixture-preprobe").start()
            requireValue(entered.await(3,TimeUnit.SECONDS),"Controlled pre-probe barrier")
            var otherChecks=0
            try{session.decode(member("concurrent",bytes)){otherChecks++;false};error("Concurrent decode accepted")}
            catch(e:StickerDecodeException){results.put(JSONObject().put("case","probe-admission").put("otherLiveCalls",otherChecks).put("concurrentRefusal",e.reason.name));requireValue(e.reason==StickerDecodeException.Reason.BUSY&&otherChecks==0,"Admission must precede copying/probing/live callback")}
            session.close()
            try{StickerDecoderSession(targetContext).close();error("Capacity released during paused probe")}
            catch(e:StickerDecodeException){requireValue(e.reason==StickerDecodeException.Reason.BUSY,"Probe lifetime must retain slot")}
        }finally{session.close();release.countDown();requireValue(completed.await(3,TimeUnit.SECONDS),"Paused probe must drain")}
        waitCapacity();requireValue(canceled.get(),"Probe cancellation must use typed cancellation")
        val early=StickerDecoderSession(targetContext)
        try{early.decode(member("early",bytes)){false};error("Canceled decode accepted")}
        catch(e:StickerDecodeException){requireValue(e.reason==StickerDecodeException.Reason.CANCELED,"Early typed cancellation")}
        waitCapacity();early.close()
        val duringProbe=StickerDecoderSession(targetContext);var checks=0
        try{duringProbe.decode(member("probe-cancel",bytes)){++checks<2};error("Probe cancellation accepted")}
        catch(e:StickerDecodeException){requireValue(e.reason==StickerDecodeException.Reason.CANCELED&&checks>=2,"Probe-time typed cancellation")}
        waitCapacity();duringProbe.close()
        results.put(JSONObject().put("case","probe-cancel-lifetime").put("closedAndDrained",true))
    }
    override fun onCreate(a:Bundle?){super.onCreate(a);args=a?:Bundle();start()}
    override fun onStart(){
        val report=JSONObject().put("runnerVersion",2).put("passed",false).put("scope","actual isolated emulator codec;no install/UI/receiver/physical/security entitlement");var outcome=Activity.RESULT_CANCELED
        try{
            requireValue(Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android.sizingfixture"&&context.packageName=="dev.lip.android.sizingfixture.test","Owned fixture only")
            val owner=args.getString("owner")!!;requireValue(UUID.fromString(owner).toString()==owner,"Explicit owner")
            val mode=args.getString("mode");requireValue(mode in listOf("smoke","suite","selected","admission"),"Explicit mode")
            report.put("owner",owner).put("mode",mode).put("appPid",Process.myPid()).put("appUid",Process.myUid())
            val png=fixture(owner,"red.png")
            requireValue(NativeStickerCodec.decode(png).code==5,"Raw parent-UID JNI must refuse before decoding")
            StickerDecoderSession(targetContext).use{val p=it.decode(member("red.png",png));red(p);record("red-png",p)};waitCapacity()
            if(mode=="admission")probeAdmission(png)
            if(mode=="suite"){
                probeAdmission(png)
                for(name in listOf("red.webp","animated.webp","wide.png")){
                    StickerDecoderSession(targetContext).use{val p=it.decode(member(name,fixture(owner,name)));red(p);record(name,p)};waitCapacity()
                }
                for(name in listOf("corrupt-pixels.png","corrupt-second-frame.webp")){
                    val bytes=fixture(owner,name);StickerImageProbe.inspect(bytes)
                    StickerDecoderSession(targetContext).use{session->
                        try{session.decode(member(name,bytes));error("Corrupt compressed pixels accepted")}
                        catch(e:StickerDecodeException){requireValue(e.reason in setOf(StickerDecodeException.Reason.CODEC,StickerDecodeException.Reason.FORMAT,StickerDecodeException.Reason.INTEGRITY),"Not a codec/integrity refusal");results.put(JSONObject().put("case",name).put("metadataAccepted",true).put("refusal",e.reason.name))}
                    };waitCapacity()
                }
                for(name in listOf("invalid-icc.png","expanded-icc.png")){
                    val bytes=fixture(owner,name);StickerImageProbe.inspect(bytes)
                    StickerDecoderSession(targetContext).use{session->
                        try{val p=session.decode(member(name,bytes));red(p);record(name,p)}
                        catch(e:StickerDecodeException){requireValue(e.reason in setOf(StickerDecodeException.Reason.CODEC,StickerDecodeException.Reason.FORMAT,StickerDecodeException.Reason.INTEGRITY),"Unqualified metadata failure");results.put(JSONObject().put("case",name).put("refusal",e.reason.name))}
                    };waitCapacity()
                }
                canceledWriter(png);watchdog()
                StickerDecoderSession(targetContext).use{val p=it.decode(member("recovery",png));red(p);record("new-session-after-watchdog",p)};waitCapacity()
            }
            if(mode=="selected"){
                val file=File(targetContext.noBackupFilesDir,"sticker-decode-fixture-$owner/wechat.zip")
                requireValue(MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}=="ce2c6b33e09477f260140536df54ad82a1d51a698b11217e79763d3793f6b15a","Pinned test source only")
                val pack=file.inputStream().use{StickerPackInspector.inspect(it)};requireValue(pack.stickers.size==75,"Exact known source")
                val pids=HashSet<Int>();var total=0;StickerDecoderSession(targetContext).use{session->
                    for(sticker in pack.stickers){val p=session.decode(sticker.member);record("selected-local-face",p);requireValue(p.width==100&&p.height==100,"Original dimensions preserved");pids.add(p.workerPid);total+=p.byteCount}
                };waitCapacity();requireValue(pids.size==1&&total==3_000_000,"Serial session/source byte bound")
                report.put("selectedFaces",75).put("previewRGBABytes",total).put("rights","UNVERIFIED").put("packInstalled",false)
            }
            report.put("results",results).put("passed",true);outcome=Activity.RESULT_OK
        }catch(t:Throwable){report.put("results",results).put("failure",android.util.Log.getStackTraceString(t))}
        finish(outcome,Bundle().apply{putString("decodeResult",report.toString())})
    }
}
