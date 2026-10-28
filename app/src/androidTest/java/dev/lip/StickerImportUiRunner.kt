package dev.lip

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.*
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.lip.stickers.StickerPacksActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual guest touch/AX consumer path; main-process instrumentation, NOT physical-phone proof. */
class StickerImportUiRunner:Instrumentation() {
    private lateinit var args:Bundle
    private lateinit var ax:UiAutomation
    private val shots=JSONArray();private val taps=JSONArray()
    private val app="dev.lip.android.sizingfixture"
    private val docs=setOf("com.android.documentsui","com.google.android.documentsui")
    private data class Node(val label:String,val pkg:String,val rect:Rect,val enabled:Boolean,val clickable:Boolean,val path:String,val targetPath:String?,val resourceId:String?,val targetId:String?,val ancestors:List<String>,val targetRect:Rect)
    private fun requireValue(value:Boolean,message:String){check(value){message}}
    private fun nodes():List<Node> {
        requireValue(ax.clearCache(),"Fresh AX cache")
        val root=ax.rootInActiveWindow?:return emptyList()
        val out=ArrayList<Node>()
        data class Target(val pkg:String,val rect:Rect,val enabled:Boolean,val path:String,val id:String?)
        fun walk(n:AccessibilityNodeInfo?,depth:Int,path:String,ancestor:Target?,ids:List<String>) {
            if(n==null||depth>32||out.size>=4096)return
            val pkg=n.packageName?.toString().orEmpty();val bounds=Rect();n.getBoundsInScreen(bounds)
            val target=if(n.isClickable&&n.isVisibleToUser)Target(pkg,Rect(bounds),n.isEnabled,path,n.viewIdResourceName) else ancestor?.takeIf{it.pkg==pkg}
            if(n.isVisibleToUser) {
                val text=n.text?.toString().orEmpty();val desc=n.contentDescription?.toString().orEmpty()
                for(label in listOf(text,desc).filter{it.isNotEmpty()}.distinct())out+=Node(label,pkg,Rect(bounds),target?.enabled?:n.isEnabled,target!=null,path,target?.path,n.viewIdResourceName,target?.id,ids,Rect(target?.rect?:bounds))
            }
            for(i in 0 until n.childCount)walk(n.getChild(i),depth+1,"$path/$i",target,ids+listOfNotNull(n.viewIdResourceName))
        }
        walk(root,0,"0",null,emptyList());return out
    }
    private fun findNow(label:String,packages:Set<String>):Node? {
        val labels=nodes().filter{it.label==label&&it.pkg in packages}.filter{
            // Pinned installed DocumentsUI APK fragment_roots.xml: select the
            // actual sidebar RootItemView, NEVER the same-named apps-row shortcut.
            label!="Lip sticker test files"||it.pkg !in docs||("${it.pkg}:id/roots_list" in it.ancestors)
        }
        // DocumentsUI may expose one row both as its accessible parent description
        // and child text. Collapse ONLY a shared nearest clickable ancestor path;
        // two distinct rows/targets remain ambiguous even with identical text.
        val targets=labels.groupBy{it.pkg+":"+(it.targetPath?:it.path)}
        requireValue(targets.size<=1,"Ambiguous logical control: $label; targets="+targets.size)
        val group=targets.values.singleOrNull()?:return null
        requireValue(group.all{it.targetRect==group[0].targetRect&&it.enabled==group[0].enabled},"Inconsistent semantic control geometry")
        // The nameplate text is the intended file-open hit zone. The center of
        // DocumentsUI's whole item_root card can be a thumbnail/selection hotspot.
        // Parent description and title are still ONE target; never merge two rows.
        return group.filter{it.resourceId=="android:id/title"}.minByOrNull{it.rect.height()}
            ?:group.minByOrNull{it.rect.width().toLong()*it.rect.height()}
    }
    private fun waitNode(label:String,packages:Set<String> =setOf(app)):Node {
        val end=SystemClock.uptimeMillis()+10000
        while(SystemClock.uptimeMillis()<end){findNow(label,packages)?.let{return it};SystemClock.sleep(80)}
        error("Visible fixed control missing: $label; visibleNodes="+nodes().size)
    }
    private fun event(action:Int,x:Float,y:Float,down:Long) {
        val pointer=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_FINGER}
        val position=MotionEvent.PointerCoords().apply{this.x=x;this.y=y;pressure=1f;size=1f}
        val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,arrayOf(pointer),arrayOf(position),0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
        requireValue(e.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER&&e.source==InputDevice.SOURCE_TOUCHSCREEN,"Explicit emulated finger protocol")
        try{requireValue(ax.injectInputEvent(e,true),"Guest touch refused")}finally{e.recycle()}
    }
    private fun tap(label:String,packages:Set<String> =setOf(app)) {
        ax.waitForIdle(150,3000)
        var first=waitNode(label,packages)
        var since=SystemClock.uptimeMillis();val limit=since+8000
        while(SystemClock.uptimeMillis()-since<350) {
            requireValue(SystemClock.uptimeMillis()<limit,"Native control geometry did not settle")
            SystemClock.sleep(70)
            val next=findNow(label,packages)
            if(next==null){since=SystemClock.uptimeMillis();continue}
            if(first.rect!=next.rect||first.targetPath!=next.targetPath)since=SystemClock.uptimeMillis()
            first=next
        }
        val second=findNow(label,packages)?:error("Control disappeared")
        requireValue(first.rect==second.rect&&first.targetPath==second.targetPath&&second.enabled&&!second.rect.isEmpty,"Fresh enabled control geometry");requireValue(second.clickable,"No current clickable target for physical tap")
        val root=ax.rootInActiveWindow?:error("No active owned window")
        requireValue(root.packageName?.toString() in packages,"Unexpected active window")
        val r=Rect(second.rect);val bounds=Rect();root.getBoundsInScreen(bounds)
        requireValue(r.intersect(second.targetRect)&&r.intersect(bounds)&&r.width()>0&&r.height()>0,"Visible tap intersection")
        taps.put(JSONObject().put("label",label).put("package",second.pkg).put("rect",r.flattenToString()).put("targetRect",second.targetRect.flattenToString()).put("targetPath",second.targetPath))
        val down=SystemClock.uptimeMillis();event(MotionEvent.ACTION_DOWN,r.exactCenterX(),r.exactCenterY(),down);event(MotionEvent.ACTION_UP,r.exactCenterX(),r.exactCenterY(),down)
        ax.waitForIdle(100,3000)
    }
    private fun scrollTo(label:String,up:Boolean=false) {
        repeat(20) {
            if(findNow(label,setOf(app))!=null)return
            val root=ax.rootInActiveWindow?:error("No active scroll window")
            requireValue(root.packageName?.toString()==app,"Scroll only owned screen")
            val r=Rect();root.getBoundsInScreen(r);requireValue(r.width()>0&&r.height()>200,"Owned scroll bounds")
            val down=SystemClock.uptimeMillis();val x=r.exactCenterX();val from=r.top+r.height()*(if(up)0.30f else 0.82f);val to=r.top+r.height()*(if(up)0.82f else 0.30f)
            event(MotionEvent.ACTION_DOWN,x,from,down)
            for(step in 1..8){SystemClock.sleep(20);event(MotionEvent.ACTION_MOVE,x,from+(to-from)*step/8,down)}
            event(MotionEvent.ACTION_UP,x,to,down);ax.waitForIdle(100,3000)
        }
        error("Control unreachable by bounded physical scrolling: $label")
    }
    private fun screenshot(owner:String,name:String) {
        // AX-ready can precede the starting-surface/window compositor transition.
        // Three consecutive identical frames, plus the explicit black padding sample,
        // are visual observation requirements; the retained first white frame is NOT
        // material acceptance. No animation-scale mutation or image recoloring.
        ax.waitForIdle(500,5000)
        val deadline=SystemClock.uptimeMillis()+10000
        var previous:ByteArray?=null;var stable=0;var captured:ByteArray?=null
        while(SystemClock.uptimeMillis()<deadline) {
            requireValue(ax.rootInActiveWindow?.packageName?.toString()==app,"Capture only owned active app scene")
            val bitmap=ax.takeScreenshot()?:error("Screenshot unavailable")
            val bytes:ByteArray;val padding:Int
            try {
                padding=bitmap.getPixel(maxOf(1,bitmap.width/100),bitmap.height/2)
                bytes=java.io.ByteArrayOutputStream().also{requireValue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it),"PNG write")}.toByteArray()
            } finally {bitmap.recycle()}
            stable=if(previous?.contentEquals(bytes)==true)stable+1 else 1
            if(stable>=3) {
                requireValue(padding==android.graphics.Color.BLACK,"Settled app padding is not opaque black")
                captured=bytes;break
            }
            previous=bytes;SystemClock.sleep(200)
        }
        val bytes=captured?:error("Owned compositor frames did not settle")
        val folder=File(targetContext.filesDir,"sticker-ui-evidence-$owner").apply{if(!exists())requireValue(mkdir(),"Own evidence directory")}
        val file=File(folder,"$name.png");requireValue(!file.exists(),"No screenshot overwrite")
        java.io.FileOutputStream(file).use{it.write(bytes);it.fd.sync()};shots.put(file.name)
    }
    private fun safeState():JSONObject {
        val allowed=setOf("Open","OPEN","Select","SELECT","Done","1 selected","Review your pack","Validate your pack","Sticker packs","Choose ZIP…","Choose a sticker pack ZIP…","Synthetic sticker pack.zip","Broken sticker pack.zip","The selected file could not be validated or preparation was canceled. Nothing committed; source unchanged.","File selection canceled. Nothing imported.","Local packs unavailable")
        val observed=nodes();val pkg=ax.rootInActiveWindow?.packageName?.toString()
        return JSONObject().put("activePackage",if(pkg==app||pkg in docs)pkg else "OTHER").put("knownControls",JSONArray(observed.filter{it.label in allowed&&(it.pkg==app||it.pkg in docs)}.map{JSONObject().put("label",it.label).put("nodeResourceId",it.resourceId).put("targetResourceId",it.targetId).put("ancestors",JSONArray(it.ancestors)).put("rect",it.rect.flattenToString()).put("enabled",it.enabled)})).put("redactedLabelNodeCount",observed.count{it.label !in allowed})
    }
    private fun commitIds():Set<String> {
        val root=File(targetContext.noBackupFilesDir,"sticker-packs-v1")
        return root.listFiles()?.filter{it.name.startsWith("commit-")&&it.isDirectory}?.map{it.name}?.toSet()?:emptySet()
    }
    override fun onCreate(a:Bundle?){super.onCreate(a);args=a?:Bundle();start()}
    override fun onStart() {
        val report=JSONObject().put("runnerVersion",1).put("passed",false).put("scope","actual emulator guest touch/AX;main-process driver,not phone/9.2")
        val actors=java.util.concurrent.CopyOnWriteArrayList<Activity>();val activity=AtomicReference<Activity?>();val created=CountDownLatch(1);val destroyed=CountDownLatch(1);val resumed=AtomicBoolean(false);var passed=false;var pickerOutstanding=false;var cleanupConfirmed=true;var launchRequested=false
        val application=targetContext.applicationContext as Application
        val incarnation="lip-sticker-ui-"+(args.getString("owner")?:"invalid")
        val callbacks=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a:Activity,b:Bundle?){if(a is StickerPacksActivity&&a.intent.identifier==incarnation){actors.add(a);activity.set(a);created.countDown()}}
            override fun onActivityStarted(a:Activity){};override fun onActivityResumed(a:Activity){if(a===activity.get())resumed.set(true)};override fun onActivityPaused(a:Activity){if(a===activity.get())resumed.set(false)}
            override fun onActivityStopped(a:Activity){};override fun onActivitySaveInstanceState(a:Activity,b:Bundle){};override fun onActivityDestroyed(a:Activity){if(a===activity.get())destroyed.countDown()}
        }
        var registered=false
        try {
            requireValue(targetContext.packageName==app&&context.packageName==app+".test"&&Build.HARDWARE=="ranchu","Owned fixture only")
            val owner=args.getString("owner")?:error("Owner required");requireValue(UUID.fromString(owner).toString()==owner,"Exact owner UUID")
            val mode=args.getString("mode")?:error("Mode required");requireValue(mode in listOf("observe","cancel","confirm","broken","inspect-picker","inspect-selection","orphan","inspect-saved","wechat-confirm","wechat-inspect"),"Explicit mode")
            report.put("owner",owner).put("mode",mode)
            val probe=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,1f,1f,0).apply{source=InputDevice.SOURCE_TOUCHSCREEN}
            try{report.put("legacyToolTypeProbe",probe.getToolType(0))}finally{probe.recycle()}
            report.put("injectedToolType",MotionEvent.TOOL_TYPE_FINGER).put("injectedSource",InputDevice.SOURCE_TOUCHSCREEN)
            ax=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val info=ax.serviceInfo;info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ax.serviceInfo=info
            val before=commitIds();report.put("beforeCommits",JSONArray(before.sorted()))
            application.registerActivityLifecycleCallbacks(callbacks);registered=true
            launchRequested=true
            runOnMainSync{targetContext.startActivity(Intent(targetContext,StickerPacksActivity::class.java).setIdentifier(incarnation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))}
            requireValue(created.await(8,TimeUnit.SECONDS),"Fresh owned Activity creation deadline")
            waitNode("Sticker packs");waitNode("Choose a sticker pack ZIP…");screenshot(owner,"library")
            val confirming=mode in setOf("confirm","wechat-confirm")
            if(mode=="wechat-inspect") {
                val expected=args.getString("expectedArchiveSHA256")?:error("Expected selected local source required")
                val store=dev.lip.stickers.StickerPackTransactionStore(File(targetContext.noBackupFilesDir,"sticker-packs-v1").toPath())
                val entry=store.scan().filter{it.archiveSha256==expected}.single()
                scrollTo("Inspect WeChat selected local source");tap("Inspect WeChat selected local source");waitNode("Saved pack")
                scrollTo("Static previews 1–8 of 75");waitNode("Static previews 1–8 of 75");screenshot(owner,"wechat-first")
                scrollTo("Next eight previews");tap("Next eight previews")
                scrollTo("Static previews 9–16 of 75",up=true);waitNode("Static previews 9–16 of 75");screenshot(owner,"wechat-next")
                scrollTo("Previous eight previews");tap("Previous eight previews")
                scrollTo("Static previews 1–8 of 75",up=true);waitNode("Static previews 1–8 of 75")
                val pack=store.read(entry.commitId)
                requireValue(pack.inspected.stickers.size==75&&pack.previews.size==75&&pack.previews.all{it.width==100&&it.height==100}&&pack.archiveSha256==expected&&commitIds()==before,"Exact original100px75memberpack/preserved commits")
                report.put("selectedSourceCommitId",entry.commitId).put("archiveSHA256",pack.archiveSha256).put("faces",75).put("sourceResolution","100x100,NOT highres").put("rights","UNVERIFIED").put("pageForwardBack",true)
            } else if(mode=="inspect-saved") {
                requireValue(before.size==1,"One exact controlled saved pack required")
                scrollTo("Inspect Synthetic test");tap("Inspect Synthetic test");waitNode("Saved pack")
                scrollTo("Static preview: Red")
                val preview=waitNode("Static preview: Red")
                screenshot(owner,"saved-preview")
                val frame=ax.takeScreenshot()?:error("Owned preview frame unavailable")
                try {
                    val x=preview.rect.centerX();val y=preview.rect.centerY()
                    requireValue(x in 0 until frame.width&&y in 0 until frame.height,"Preview center must actually be visible")
                    val pixel=frame.getPixel(x,y)
                    requireValue(pixel==android.graphics.Color.RED,"Actual raw first-frame preview pixel is not red")
                    report.put("previewCenterArgb",Integer.toHexString(pixel))
                } finally {frame.recycle()}
                val id=before.single().removePrefix("commit-")
                val stored=dev.lip.stickers.StickerPackTransactionStore(File(targetContext.noBackupFilesDir,"sticker-packs-v1").toPath()).read(id)
                requireValue(stored.archiveSha256==args.getString("expectedArchiveSHA256")&&commitIds()==before,"Inspection preserves exact archive and commit set")
                report.put("inspectedCommitId",id).put("archiveSHA256",stored.archiveSha256)
            } else if(mode!="observe") {
                tap("Choose a sticker pack ZIP…");waitNode("Choose ZIP…");pickerOutstanding=true;tap("Choose ZIP…")
                val filename=if(mode=="broken")"Broken sticker pack.zip" else if(mode=="wechat-confirm")"Selected local reactions.zip" else "Synthetic sticker pack.zip"
                val end=SystemClock.uptimeMillis()+10000
                while(ax.rootInActiveWindow?.packageName?.toString() !in docs&&SystemClock.uptimeMillis()<end)SystemClock.sleep(80)
                requireValue(ax.rootInActiveWindow?.packageName?.toString() in docs,"System DocumentsUI active");ax.waitForIdle(500,5000);SystemClock.sleep(500)
                tap("Show roots",docs)
                if(mode=="inspect-picker") {
                    val found=nodes().filter{it.label=="Lip sticker test files"&&it.pkg in docs}
                    report.put("fixtureRootCandidates",JSONArray(found.map{JSONObject().put("nodePath",it.path).put("targetPath",it.targetPath).put("nodeResourceId",it.resourceId).put("targetResourceId",it.targetId).put("ancestors",JSONArray(it.ancestors)).put("rect",it.rect.flattenToString()).put("enabled",it.enabled)}))
                    requireValue(found.isNotEmpty(),"Known fixture root candidates required")
                } else {
                tap("Lip sticker test files",docs)
                if(mode=="orphan") {
                    waitNode(filename,docs)
                    val old=activity.get()?:error("Owned caller required")
                    runOnMainSync{old.recreate()}
                    val deadline=SystemClock.uptimeMillis()+10000
                    while((activity.get()===old||!old.isDestroyed)&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(80)
                    requireValue(activity.get()!==old&&old.isDestroyed&&actors.size==2,"Actual caller recreation while picker remains")
                    report.put("callerIncarnations",actors.size)
                }
                tap(filename,docs)
                if(mode=="inspect-selection") {
                    ax.waitForIdle(500,5000);SystemClock.sleep(500);report.put("selectionState",safeState())
                } else {
                if(mode=="orphan") {
                    waitNode("Sticker packs");requireValue(waitNode("Choose a sticker pack ZIP…").enabled,"Orphan result returns to available library")
                    val current=activity.get()?:error("New owned caller required")
                    val field=StickerPacksActivity::class.java.getDeclaredField("worker").apply{isAccessible=true}
                    val executor=field.get(current) as java.util.concurrent.ThreadPoolExecutor
                    var quietSince=0L;val deadline=SystemClock.uptimeMillis()+10000
                    while(SystemClock.uptimeMillis()<deadline) {
                        runOnMainSync{}
                        requireValue(findNow("Review your pack",setOf(app))==null,"Stale picker result recreated a review")
                        if(executor.activeCount==0&&executor.queue.isEmpty()) {if(quietSince==0L)quietSince=SystemClock.uptimeMillis();if(SystemClock.uptimeMillis()-quietSince>=1000)break} else quietSince=0L
                        SystemClock.sleep(80)
                    }
                    requireValue(quietSince>0&&SystemClock.uptimeMillis()-quietSince>=1000,"Orphan result work did not settle")
                    pickerOutstanding=false;report.put("orphanResultIgnored",true)
                } else if(mode=="broken") {
                    waitNode("The selected file could not be validated or preparation was canceled. Nothing committed; source unchanged.");pickerOutstanding=false
                } else {
                    waitNode("Review your pack");pickerOutstanding=false;screenshot(owner,"review")
                    val button=if(confirming)"Confirm local import" else "Cancel import"
                    scrollTo(button);tap(button)
                    waitNode("Sticker packs");waitNode("Choose a sticker pack ZIP…")
                    if(confirming) {
                        val timeout=SystemClock.uptimeMillis()+10000
                        while(commitIds().size!=before.size+1&&SystemClock.uptimeMillis()<timeout)SystemClock.sleep(80)
                    }
                }
                val after=commitIds();requireValue(if(confirming)after.size==before.size+1&&after.containsAll(before) else after==before,"Actual published commit delta")
                if(confirming) {
                    val id=(after-before).single().removePrefix("commit-")
                    val expected=args.getString("expectedArchiveSHA256")?:error("Expected synthetic archive hash required")
                    requireValue(Regex("[a-f0-9]{64}").matches(expected),"Expected hash syntax")
                    val stored=dev.lip.stickers.StickerPackTransactionStore(File(targetContext.noBackupFilesDir,"sticker-packs-v1").toPath()).read(id)
                    requireValue(stored.archiveSha256==expected,"Exact integrity-checked persisted source")
                    requireValue(dev.lip.stickers.StickerImportJournal(File(targetContext.noBackupFilesDir,"sticker-import-journal-v1").toPath()).unresolved().isEmpty(),"Confirm must settle recovery journal")
                    val status="Saved locally. Commit $id. Nothing activated or sent."
                    scrollTo(status);waitNode(status)
                    scrollTo("Choose a sticker pack ZIP…");requireValue(waitNode("Choose a sticker pack ZIP…").enabled,"Settled import allows another explicit attempt")
                    report.put("settledCommitId",id).put("archiveSHA256",stored.archiveSha256)
                }
                report.put("afterCommits",JSONArray(after.sorted()));screenshot(owner,"result")
                }
                }
            }
            report.put("visibleOwnedNodeCount",nodes().count{it.pkg==app})
            passed=true
        } catch(e:Throwable){report.put("failure",e.javaClass.simpleName+": "+(e.message?:""));if(::ax.isInitialized)runCatching{report.put("safeState",safeState())}}
        finally {
            try {
                if(pickerOutstanding) {
                    var returned=false
                    for(attempt in 0 until 4) {
                        if(::ax.isInitialized&&ax.rootInActiveWindow?.packageName?.toString()==app&&resumed.get()){returned=true;break}
                        val pkg=if(::ax.isInitialized)ax.rootInActiveWindow?.packageName?.toString() else null
                        if(pkg !in docs&&pkg!=app)break
                        if(pkg in docs)requireValue(ax.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK),"Owned picker Back refused")
                        val end=SystemClock.uptimeMillis()+3000
                        while(SystemClock.uptimeMillis()<end){if(ax.rootInActiveWindow?.packageName?.toString()==app&&resumed.get()){returned=true;break};SystemClock.sleep(80)}
                        if(returned)break
                    }
                    requireValue(returned,"Owned picker cancellation/return UNCONFIRMED")
                    pickerOutstanding=false
                }
                if(actors.isNotEmpty()) {
                    runOnMainSync{actors.forEach{if(!it.isDestroyed)it.finishAndRemoveTask()}}
                    val end=SystemClock.uptimeMillis()+8000
                    while(actors.any{!it.isDestroyed}&&SystemClock.uptimeMillis()<end)SystemClock.sleep(80)
                    requireValue(actors.all{it.isDestroyed},"Every owned Activity destruction UNCONFIRMED")
                    // Read-only diagnostics of exact owned instances, not UI actions.
                    val field=StickerPacksActivity::class.java.getDeclaredField("worker").apply{isAccessible=true}
                    for(owned in actors) {
                        val executor=field.get(owned) as java.util.concurrent.ThreadPoolExecutor
                        requireValue(executor.isShutdown&&executor.awaitTermination(10,TimeUnit.SECONDS),"Owned open/read/preparation executor drain UNCONFIRMED")
                    }
                } else requireValue(!launchRequested,"Owned launch cleanup UNCONFIRMED")
                val drainEnd=SystemClock.uptimeMillis()+10000
                while(dev.lip.stickers.StickerImportAdmission.isActive()&&SystemClock.uptimeMillis()<drainEnd)SystemClock.sleep(80)
                requireValue(!dev.lip.stickers.StickerImportAdmission.isActive(),"Owned commit/reconcile drain UNCONFIRMED")
                val capacityField=dev.lip.stickers.StickerDecoderSession::class.java.getDeclaredField("capacity").apply{isAccessible=true}
                val capacity=capacityField.get(null) as AtomicBoolean
                val codecEnd=SystemClock.uptimeMillis()+10000
                while(capacity.get()&&SystemClock.uptimeMillis()<codecEnd)SystemClock.sleep(80)
                requireValue(!capacity.get(),"Owned decoder IPC/writer lifetime UNCONFIRMED")
            } catch(_:Throwable){cleanupConfirmed=false;passed=false;report.put("cleanupFailure","Owned picker/task/drain UNCONFIRMED;no next baseline")}
            if(registered)application.unregisterActivityLifecycleCallbacks(callbacks)
        }
        report.put("cleanupConfirmed",cleanupConfirmed).put("pickerOutstanding",pickerOutstanding)

        report.put("passed",passed).put("taps",taps).put("screenshots",shots)
        finish(if(passed)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString("stickerUiResult",report.toString())})
    }
}
