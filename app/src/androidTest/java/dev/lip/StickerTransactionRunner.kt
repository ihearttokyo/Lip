package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.*
import dev.lip.stickers.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Owned emulator-only pack preparation/storage evidence; no UI/receiver or real data. */
class StickerTransactionRunner:Instrumentation() {
    private lateinit var args:Bundle
    private val results=JSONArray()
    private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun requireValue(value:Boolean,message:String){check(value){message}}
    private fun crash(root:File,prepared:PreparedStickerPack,mode:String):Nothing {
        val managed=File(root,"managed");requireValue(managed.mkdir(),"Fresh crash fixture root")
        val control=StickerCommitControl()
        val point=if(mode=="crash-before")StickerStorePoint.BEFORE_MOVE else StickerStorePoint.AFTER_MOVE
        val store=StickerPackTransactionStore(managed.toPath()) { observed ->
            if(observed==point) {
                requireValue(Process.myUid()==targetContext.applicationInfo.uid&&targetContext.packageName=="dev.lip.android.sizingfixture","Only own fixture process may self-terminate")
                val intent=JSONObject().put("owner",root.name.removePrefix("sticker-transaction-fixture-")).put("point",point.name).put("commitId",control.commitId).put("pid",Process.myPid()).put("archiveSHA256",prepared.archiveSha256).toString().toByteArray()
                java.io.FileOutputStream(File(root,"crash-fired.json")).use{it.write(intent);it.fd.sync()}
                java.nio.channels.FileChannel.open(root.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
                Process.killProcess(Process.myPid())
                error("Controlled own-process termination did not occur")
            }
        }
        store.commit(prepared,control)
        error("Controlled crash hook was not reached")
    }
    private fun recover(owner:String,mode:String) {
        requireValue(UUID.fromString(owner).toString()==owner,"Exact prior controlled owner")
        val root=File(targetContext.noBackupFilesDir,"sticker-transaction-fixture-$owner")
        val marker=File(root,"crash-fired.json");requireValue(marker.length() in 1..4096,"Bounded crash record")
        val markerBytes=marker.readBytes();requireValue(sha(markerBytes)==args.getString("recoveryMarkerSHA256"),"Frozen controlled crash record")
        val intent=JSONObject(String(markerBytes,Charsets.UTF_8));val id=intent.getString("commitId")
        requireValue(intent.getString("owner")==owner&&id==args.getString("recoveryCommitId"),"Exact prior owner/commit binding")
        requireValue(UUID.fromString(id).toString()==id&&intent.getInt("pid")!=Process.myPid(),"Fresh process observes exact prior transaction")
        val store=StickerPackTransactionStore(File(root,"managed").toPath())
        val after=mode=="recover-after"
        requireValue(intent.getString("point")==if(after)"AFTER_MOVE" else "BEFORE_MOVE","Fixed crash point")
        if(after) {
            val pack=store.read(id)
            requireValue(pack.archiveSha256==intent.getString("archiveSHA256")&&sha(pack.archiveBytes())==pack.archiveSha256&&pack.previews.size==3,"Exact postdeath archive/previews")
            for(p in pack.previews){val b=p.premultipliedRgbaSrgbBytes();requireValue(b.size==16,"Bounded recovered pixels");for(i in b.indices step 4)requireValue(b[i]==255.toByte()&&b[i+1]==0.toByte()&&b[i+2]==0.toByte()&&b[i+3]==255.toByte(),"Recovered original red pixels")}
            requireValue(store.scan().size==1,"One commit after own-process death")
        } else {
            requireValue(store.scan().isEmpty()&&File(root,"managed/pending-$id").isDirectory&&!File(root,"managed/commit-$id").exists(),"Never promote retained staging after death")
            try{store.read(id);error("Unpublished staged pack read as commit")}catch(e:StickerStoreException){requireValue(e.reason==StickerStoreException.Reason.UNAVAILABLE,"Missing commit is unavailable")}
        }
        results.put(JSONObject().put("case",mode).put("commitId",id).put("priorPid",intent.getInt("pid")).put("currentPid",Process.myPid()).put("published",after).put("stagingPromoted",false).put("powerLossDurability","NOT_TESTED"))
    }
    private fun separateProcessLock(root:File,prepared:PreparedStickerPack) {
        val store=StickerPackTransactionStore(root.toPath())
        val before=store.scan().map{it.commitId}.toSet()
        val child=ProcessBuilder("/system/bin/app_process","/system/bin",StickerStoreAndroidLockChild::class.java.name,File(root,"writer.lock").path).apply{
            environment()["CLASSPATH"]=context.applicationInfo.sourceDir+File.pathSeparator+targetContext.applicationInfo.sourceDir
            redirectErrorStream(true)
        }.start()
        val ready=AtomicReference<String?>();val readDone=CountDownLatch(1)
        val reader=Thread({try{ready.set(child.inputStream.bufferedReader().readLine())}finally{readDone.countDown()}},"fixture-lock-ready")
        reader.start()
        var childPid=0
        try {
            requireValue(readDone.await(10,TimeUnit.SECONDS),"Own lock helper readiness")
            val line=ready.get()?:error("Own lock helper exited before lock")
            val parts=line.split(' ')
            requireValue(parts.size==3&&parts[0]=="LOCKED","Own lock helper exact reply: "+line.take(200))
            childPid=parts[1].toInt()
            requireValue(childPid>0&&childPid!=Process.myPid()&&parts[2].toInt()==Process.myUid(),"Different owned process/same app UID")
            try{store.commit(prepared);error("Independent process writer lock ignored")}
            catch(e:StickerStoreException){requireValue(e.reason==StickerStoreException.Reason.BUSY&&e.publication==StickerStoreException.Publication.NOT_PUBLISHED,"Independent OS lock exclusion")}
            requireValue(store.scan().map{it.commitId}.toSet()==before,"No publication while helper owns lock")
        } finally {
            runCatching{child.outputStream.use{it.write(1);it.flush()}}
            if(!child.waitFor(3,TimeUnit.SECONDS)){child.destroyForcibly();requireValue(child.waitFor(3,TimeUnit.SECONDS),"Owned helper cleanup")}
            reader.join(3000)
        }
        requireValue(child.exitValue()==0&&!reader.isAlive,"Own helper drained normally")
        val next=store.commit(prepared)
        requireValue(next.commitId !in before&&store.scan().size==before.size+1,"New explicit commit after OS lock release")
        results.put(JSONObject().put("case","android-independent-process-lock").put("helperPid",childPid).put("parentPid",Process.myPid()).put("uid",Process.myUid()).put("busyWhileLocked",true).put("newCommitAfterRelease",next.commitId))
    }
    override fun onCreate(a:Bundle?){super.onCreate(a);args=a?:Bundle();start()}
    override fun onStart(){
        val report=JSONObject().put("runnerVersion",1).put("passed",false).put("scope","owned isolated emulator pack preparation/storage only;not UI/rights/full9.2")
        var passed=false
        try {
            requireValue(targetContext.packageName=="dev.lip.android.sizingfixture"&&context.packageName=="dev.lip.android.sizingfixture.test"&&Build.HARDWARE=="ranchu","Owned emulator fixture only")
            val owner=args.getString("owner")?:error("Owner required")
            requireValue(UUID.fromString(owner).toString()==owner,"Exact owner UUID")
            val mode=args.getString("mode")?:error("Mode required");requireValue(mode in listOf("prepare","store","contracts","crash-before","crash-after","recover-before","recover-after"),"Explicit mode")
            report.put("owner",owner).put("mode",mode)
            val root=File(targetContext.noBackupFilesDir,"sticker-transaction-fixture-$owner")
            if(mode.startsWith("recover-")) {
                recover(args.getString("recoveryOwner")?:error("Controlled recovery owner required"),mode)
                passed=true
            } else {
            val archive=File(root,"pack.zip").readBytes();val expectedSha=sha(archive)
            val preparer=StickerPackPreparer(targetContext)
            val mainFailure=AtomicReference<Throwable?>()
            runOnMainSync { try{preparer.prepare(archive);error("Main thread preparation accepted")}catch(e:Throwable){mainFailure.set(e)} }
            requireValue(mainFailure.get() is IllegalStateException && mainFailure.get()?.message=="Sticker preparation cannot block the main thread","Main guard before input")
            try { preparer.prepare(archive){false};error("Early canceled preparation accepted") }
            catch(e:StickerPreparationException){requireValue(e.reason==StickerPreparationException.Reason.CANCELED,"Typed early cancellation")}
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1);val firstFailure=AtomicReference<Throwable?>();val otherLive=AtomicInteger()
            val thread=Thread({try{preparer.prepare(archive){entered.countDown();requireValue(release.await(3,TimeUnit.SECONDS),"Controlled probe release");false}}catch(e:Throwable){firstFailure.set(e)}finally{done.countDown()}},"fixture-pack-admission")
            thread.start()
            try {
                requireValue(entered.await(2,TimeUnit.SECONDS),"Preparation admission pause")
                try{preparer.prepare(archive){otherLive.incrementAndGet();true};error("Concurrent preparation accepted")}
                catch(e:StickerPreparationException){requireValue(e.reason==StickerPreparationException.Reason.BUSY,"Busy before live/copy")}
                requireValue(otherLive.get()==0,"No pre-admission callback")
            } finally {release.countDown()}
            requireValue(done.await(3,TimeUnit.SECONDS),"Preparation drained")
            requireValue((firstFailure.get() as? StickerPreparationException)?.reason==StickerPreparationException.Reason.CANCELED,"Paused preparation cancellation")
            results.put(JSONObject().put("case","preparation-admission").put("otherLiveCalls",otherLive.get()).put("mainRefused",true).put("canceledAndDrained",true))
            val bad=File(root,"broken.zip").readBytes()
            try{preparer.prepare(bad);error("Broken compressed second sticker accepted")}
            catch(e:StickerDecodeException){requireValue(e.reason==StickerDecodeException.Reason.CODEC,"Actual codec refusal")}
            results.put(JSONObject().put("case","preparation-broken-member").put("refusal","CODEC").put("published",false))
            val capacityField=StickerDecoderSession::class.java.getDeclaredField("capacity").apply{isAccessible=true}
            val capacity=capacityField.get(null) as java.util.concurrent.atomic.AtomicBoolean
            val drainDeadline=SystemClock.elapsedRealtime()+3000
            while(capacity.get()&&SystemClock.elapsedRealtime()<drainDeadline)Thread.sleep(20)
            requireValue(!capacity.get(),"Failed preparation IPC/writer actually drained")
            // New explicit invocation after cancellation/failure, never an internal retry.
            val prepared=preparer.prepare(archive)
            requireValue(prepared.archiveSha256==expectedSha&&prepared.inspected.stickers.size==2&&prepared.inspected.trayImage!=null,"All synthetic members bound")
            archive.fill(0)
            requireValue(prepared.archiveSha256==expectedSha&&prepared.inspected.rights==InspectedStickerPack.Rights.UNVERIFIED,"Copy/rights preserved")
            results.put(JSONObject().put("case","prepared-pack").put("archiveSHA256",expectedSha).put("stickers",2).put("tray",true).put("rights","UNVERIFIED").put("published",false))
            if(mode.startsWith("crash-"))crash(root,prepared,mode)
            if(mode in listOf("store","contracts")) {
                val managed=File(root,"managed")
                requireValue(managed.mkdir(),"Fresh controlled managed root")
                fun verifyStored(pack:StoredStickerPack) {
                    requireValue(pack.archiveSha256==expectedSha&&sha(pack.archiveBytes())==expectedSha&&pack.inspected.rights==InspectedStickerPack.Rights.UNVERIFIED,"Stored archive/rights exact")
                    requireValue(pack.previews.size==3,"Tray plus all stickers")
                    for(p in pack.previews) {
                        val rgba=p.premultipliedRgbaSrgbBytes()
                        requireValue(p.width==2&&p.height==2&&rgba.size==16,"Stored bounded raw pixels")
                        for(i in rgba.indices step 4)requireValue(rgba[i]==255.toByte()&&rgba[i+1]==0.toByte()&&rgba[i+2]==0.toByte()&&rgba[i+3]==255.toByte(),"Original red first frames")
                        rgba.fill(0);requireValue(p.premultipliedRgbaSrgbBytes()[0]==255.toByte(),"Stored copy safety")
                    }
                }
                val store=StickerPackTransactionStore(managed.toPath())
                val first=store.commit(prepared);verifyStored(store.read(first.commitId))
                results.put(JSONObject().put("case","android-atomic-commit-reopen").put("commitId",first.commitId).put("archiveSHA256",first.archiveSha256).put("storedBytes",first.storedBytes))
                val canceled=StickerCommitControl()
                val cancelStore=StickerPackTransactionStore(managed.toPath()) { point ->
                    if(point==StickerStorePoint.BEFORE_PUBLICATION_CLAIM)requireValue(canceled.cancel(),"Cancel must win before claim")
                }
                try{cancelStore.commit(prepared,canceled);error("Canceled transaction published")}
                catch(e:StickerStoreException){requireValue(e.publication==StickerStoreException.Publication.NOT_PUBLISHED&&e.commitId==canceled.commitId,"Explicit not-published cancellation")}
                requireValue(store.scan().size==1,"Staging never listed as committed")
                results.put(JSONObject().put("case","cancel-before-claim").put("commitId",canceled.commitId).put("publication","NOT_PUBLISHED"))
                val late=StickerCommitControl();var cancelWon=true
                val lateStore=StickerPackTransactionStore(managed.toPath()) { point ->
                    if(point==StickerStorePoint.AFTER_PUBLICATION_CLAIM)cancelWon=late.cancel()
                }
                val second=lateStore.commit(prepared,late)
                requireValue(!cancelWon&&second.commitId!=first.commitId,"Claim prevents false rollback/replacement")
                verifyStored(store.read(second.commitId));verifyStored(store.read(first.commitId))
                results.put(JSONObject().put("case","cancel-after-claim").put("cancelWon",false).put("commitId",second.commitId))
                val uncertain=StickerCommitControl()
                val uncertainStore=StickerPackTransactionStore(managed.toPath()) { point ->
                    if(point==StickerStorePoint.AFTER_MOVE)throw java.io.IOException("Controlled post-move failure")
                }
                try{uncertainStore.commit(prepared,uncertain);error("Injected post-rename error reported complete")}
                catch(e:StickerStoreException){requireValue(e.publication==StickerStoreException.Publication.UNCONFIRMED&&e.commitId==uncertain.commitId,"Immutable ID/uncertainty retained")}
                verifyStored(store.read(uncertain.commitId))
                requireValue(store.scan().size==3,"Reconcile exact uncertain commit without retry")
                results.put(JSONObject().put("case","post-move-uncertainty").put("commitId",uncertain.commitId).put("publication","UNCONFIRMED").put("reconciled",true))
            }
            if(mode=="contracts") {
                separateProcessLock(File(root,"managed"),prepared)
                val casesRoot=File(root,"contract-tests")
                requireValue(casesRoot.mkdir(),"Fresh controlled contract root")
                dev.lip.core.StickerPackTransactionStoreAndroidTest.base=casesRoot.toPath().toRealPath()
                val contractClass=dev.lip.core.StickerPackTransactionStoreAndroidTest::class.java
                val names=contractClass.methods.filter{it.isAnnotationPresent(org.junit.Test::class.java)}.map{it.name}.sorted()
                val checked=org.junit.runner.JUnitCore.runClasses(contractClass)
                val failures=JSONArray()
                checked.failures.forEach{failures.put(JSONObject().put("method",it.description.methodName).put("exception",it.exception.javaClass.simpleName).put("message",it.message))}
                results.put(JSONObject().put("case","android-store-contract-matrix").put("scope","synthetic raw-store checks;not independent codec proof").put("methods",JSONArray(names)).put("run",checked.runCount).put("ignored",checked.ignoreCount).put("failures",failures))
                requireValue(names.size==12&&checked.runCount==12&&checked.ignoreCount==0&&checked.wasSuccessful(),"Actual Android contract matrix failed")
            }
            passed=true
            }
        } catch(e:Throwable){report.put("failure",e.javaClass.simpleName+": "+(e.message?:""))}
        report.put("passed",passed).put("results",results)
        finish(if(passed)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString("transactionResult",report.toString())})
    }
}
