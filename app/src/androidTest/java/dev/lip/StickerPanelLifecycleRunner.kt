package dev.lip

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import dev.lip.keyboard.KeyboardScale
import dev.lip.keyboard.KeyboardSettingsActivity
import dev.lip.keyboard.StickerPanel
import dev.lip.keyboard.StickerPanelPack
import dev.lip.keyboard.StickerPanelPreview
import dev.lip.keyboard.StickerPanelState
import dev.lip.keyboard.StickerAssetIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Native component callbacks only. NOT actual IME/editor, consumer navigation or physical-phone evidence. */
class StickerPanelLifecycleRunner:Instrumentation() {
    private class Counts {
        val values=linkedMapOf("back" to 0,"manage" to 0,"library" to 0,"select" to 0,"previous" to 0,"next" to 0,"attach" to 0)
        fun hit(key:String){values[key]=values.getValue(key)+1}
        fun snapshot()=values.toMap()
    }
    private class Launch(val id:String) {
        val resumed=CountDownLatch(1)
        // Creation and resume are independent: a created-but-not-resumed instance is still owned.
        val activity=AtomicReference<KeyboardSettingsActivity>()
        var duplicateCreation=false // main-thread only, refuse ambiguous launch qualification
    }
    private data class Binding(val panel:StickerPanel,val counts:Counts,val controls:Map<String,View>)
    private lateinit var args:Bundle
    private lateinit var owner:String
    private lateinit var ax:UiAutomation
    private lateinit var application:Application
    private var pending:Launch?=null // main-thread only
    private val actors=ArrayList<KeyboardSettingsActivity>()
    private val launches=ArrayList<Launch>() // admitted before startActivity; main-thread only
    private val panels=ArrayList<StickerPanel>()
    private var cleanupRequested=false // remains active for late creation after a timeout
    private var registered=false
    private var deadline=0L
    private var stage="guard"
    private val cases=JSONArray()
    private val taps=JSONArray()
    private val callbacks=object:Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a:Activity,b:Bundle?){
            if(a !is KeyboardSettingsActivity)return
            val record=launches.singleOrNull{it.id==a.intent.identifier}?:return
            actors.add(a)
            if(!record.activity.compareAndSet(null,a))record.duplicateCreation=true
            if(cleanupRequested)runCatching{a.finish()} // exact instance only; unresolved destruction cannot qualify cleanup
        }
        override fun onActivityResumed(a:Activity){
            val launch=pending
            if(a is KeyboardSettingsActivity&&launch!=null&&launch.activity.get()===a&&a.intent.identifier==launch.id)launch.resumed.countDown()
        }
        override fun onActivityStarted(a:Activity){}
        override fun onActivityPaused(a:Activity){}
        override fun onActivityStopped(a:Activity){}
        override fun onActivitySaveInstanceState(a:Activity,b:Bundle){}
        override fun onActivityDestroyed(a:Activity){retireCleanupObserverIfResolved()}
    }
    private fun cleanupResolved():Boolean = launches.all{it.activity.get()?.isDestroyed==true&&!it.duplicateCreation}&&actors.all{it.isDestroyed}
    private fun retireCleanupObserverIfResolved(){
        if(cleanupRequested&&registered&&cleanupResolved()){
            application.unregisterActivityLifecycleCallbacks(callbacks);registered=false
        }
    }
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);args=arguments?:Bundle();start()}
    private fun must(value:Boolean,token:String){if(!value)throw AssertionError(token)}
    private fun budget(){must(SystemClock.uptimeMillis()<deadline,"COMPONENT_DEADLINE")}
    private fun <T> ui(action:()->T):T {
        val value=AtomicReference<T>();val failure=AtomicReference<Throwable>()
        runOnMainSync{try{value.set(action())}catch(t:Throwable){failure.set(t)}}
        failure.get()?.let{throw it};return value.get()
    }
    private fun all(root:View):List<View> = buildList {
        fun visit(view:View){add(view);if(view is ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i))}
        visit(root)
    }
    private fun launch(suffix:String):KeyboardSettingsActivity {
        val record=Launch("$owner-$suffix")
        ui{
            pending=record
            // A throwing/blocked start can still have admitted creation. Never infer absence.
            launches.add(record)
            targetContext.startActivity(Intent(targetContext,KeyboardSettingsActivity::class.java).setIdentifier(record.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        }
        must(record.resumed.await(8,TimeUnit.SECONDS),"OWNED_ACTIVITY_RESUME_DEADLINE")
        val host=record.activity.get();must(host!=null,"FRESH_OWNED_ACTIVITY_REQUIRED")
        ui{pending=null};return host
    }
    private fun state():StickerPanelState.Pack {
        val previews=(0 until 8).map{index->
            val rgba=ByteArray(16){at->when(at%4){0->(24+index).toByte();1->48;2->72;else->255.toByte()}}
            StickerPanelPreview("Synthetic pixel $index",2,2,rgba,StickerAssetIdentity(
                "00000000-0000-0000-0000-000000000001","a".repeat(64),"synthetic-$index","b".repeat(64),"image/png",78))
        }
        return StickerPanelState.Pack("Original synthetic component","Self-authored raw pixels. Rights UNVERIFIED.",previews,8,24)
    }
    private fun libraryState()=StickerPanelState.Library(listOf(StickerPanelPack(
        "00000000-0000-0000-0000-000000000001","Original synthetic component",true,"Self-authored local fixture")),0,1)
    private fun install(host:KeyboardSettingsActivity,library:Boolean=false):Binding = ui {
        val counts=Counts()
        val panel=StickerPanel(host,600,if(library)libraryState() else state(),KeyboardScale.SYSTEM,
            {counts.hit("back")},{counts.hit("manage")},{counts.hit("library")},{counts.hit("select")},{counts.hit("previous")},{counts.hit("next")},
            {asset->must(asset.stickerId=="synthetic-0","EXACT_SELECTED_ASSET_REQUIRED");counts.hit("attach")})
        panels.add(panel);host.setContentView(panel)
        val descriptions=if(library)mapOf("select" to "Inspect saved sticker pack Original synthetic component") else linkedMapOf(
            "back" to "Return from stickers to letters","library" to "Show local sticker packs","manage" to "Manage local sticker packs",
            "previous" to "Previous sticker browser page","next" to "Next sticker browser page",
            "attach" to "Request original sticker Synthetic pixel 0")
        val controls=descriptions.mapValues{(_,description)->all(panel).single{it.contentDescription?.toString()==description}}
        Binding(panel,counts,controls)
    }
    private fun waitFor(token:String,condition:()->Boolean){
        val end=SystemClock.uptimeMillis()+8000
        do{budget();if(condition())return;SystemClock.sleep(70)}while(SystemClock.uptimeMillis()<end)
        throw AssertionError(token)
    }
    private fun visible(view:View):Rect?=ui {
        val rect=Rect()
        if(view.isAttachedToWindow&&view.isShown&&view.isEnabled&&view.windowVisibility==View.VISIBLE&&view.getGlobalVisibleRect(rect)&&!rect.isEmpty)rect else null
    }
    private fun reveal(view:View){
        // Direct component preparation, NOT a claimed consumer scroll gesture.
        waitFor("COMPONENT_LAYOUT_DEADLINE"){ui{view.isAttachedToWindow&&view.width>0&&view.height>0}}
        ui{view.requestRectangleOnScreen(Rect(0,0,view.width,view.height),true)}
        waitFor("COMPONENT_CONTROL_VISIBILITY"){visible(view)!=null}
    }
    private fun node(description:String):AccessibilityNodeInfo? {
        budget();must(ax.clearCache(),"FRESH_AX_CACHE_REQUIRED")
        val found=ArrayList<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo?,depth:Int){
            if(n==null||depth>32||found.size>1)return
            if(n.isVisibleToUser&&n.packageName?.toString()==targetContext.packageName&&n.contentDescription?.toString()==description)found.add(n)
            for(i in 0 until n.childCount)visit(n.getChild(i),depth+1)
        }
        ax.windows.forEach{visit(it.root,0)};must(found.size<=1,"AMBIGUOUS_OWNED_AX_CONTROL");return found.singleOrNull()
    }
    private fun event(action:Int,x:Float,y:Float,down:Long){
        val pointer=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_FINGER}
        val position=MotionEvent.PointerCoords().apply{this.x=x;this.y=y;pressure=1f;size=1f}
        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,arrayOf(pointer),arrayOf(position),0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
        must(event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER&&event.source==InputDevice.SOURCE_TOUCHSCREEN,"EXPLICIT_FINGER_PROTOCOL")
        try{must(ax.injectInputEvent(event,true),"OWNED_COMPONENT_FINGER_REFUSED")}finally{event.recycle()}
    }
    private fun physical(binding:Binding,key:String){
        val view=binding.controls.getValue(key);reveal(view)
        val description=ui{view.contentDescription.toString()};var previous:Rect?=null;var point:Rect?=null;var since=0L
        val end=SystemClock.uptimeMillis()+8000
        do{
            budget();val observed=node(description);val native=visible(view)
            if(observed!=null&&native!=null&&observed.refresh()&&observed.isEnabled){
                val rect=Rect();observed.getBoundsInScreen(rect)
                if(rect.intersect(native)&&!rect.isEmpty){
                    if(previous!=rect){previous=Rect(rect);since=SystemClock.uptimeMillis()}
                    if(SystemClock.uptimeMillis()-since>=300){point=Rect(rect);break}
                }else previous=null
            }else previous=null
            SystemClock.sleep(70)
        }while(SystemClock.uptimeMillis()<end)
        val hit=point?:throw AssertionError("CURRENT_NATIVE_AX_COMPONENT_INTERSECTION")
        val fresh=node(description)?:throw AssertionError("CURRENT_COMPONENT_OWNERSHIP")
        val native=visible(view)?:throw AssertionError("CURRENT_COMPONENT_OWNERSHIP")
        must(fresh.refresh()&&fresh.isEnabled,"CURRENT_COMPONENT_OWNERSHIP")
        val bounds=Rect();fresh.getBoundsInScreen(bounds)
        must(hit.intersect(bounds)&&hit.intersect(native)&&!hit.isEmpty,"FRESH_COMPONENT_POINT_REQUIRED")
        val before=ui{binding.counts.snapshot()};val down=SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN,hit.exactCenterX(),hit.exactCenterY(),down)
        event(MotionEvent.ACTION_UP,hit.exactCenterX(),hit.exactCenterY(),down)
        waitFor("CURRENT_COMPONENT_CALLBACK_NOT_DISPATCHED"){ui{binding.counts.values.getValue(key)==before.getValue(key)+1}}
        val after=ui{binding.counts.snapshot()}
        must(after.all{(name,count)->count==before.getValue(name)+(if(name==key)1 else 0)},"WRONG_COMPONENT_CALLBACK")
        taps.put(JSONObject().put("callback",key).put("rect",hit.flattenToString()).put("nativeAxOverlap",true))
    }
    private fun retainedNoDispatch(binding:Binding,token:String){
        val before=ui{binding.counts.snapshot()}
        ui{binding.controls.values.forEach{it.performClick()}}
        must(ui{binding.counts.snapshot()}==before,token)
    }
    private fun closePixels(binding:Binding){
        ui{
            val views=all(binding.panel).filterIsInstance<ImageView>().filter{it.tag=="lip:sticker:preview"}
            must(views.size==8,"EXACT_COMPONENT_BITMAP_COUNT")
            val bitmaps=views.map{(it.drawable as? BitmapDrawable)?.bitmap?:throw AssertionError("ACTUAL_BITMAP_DRAWABLE_REQUIRED")}
            must(bitmaps.all{!it.isRecycled},"CURRENT_BITMAP_ALREADY_RECYCLED")
            binding.panel.close()
            must(views.all{it.drawable==null}&&bitmaps.all{it.isRecycled},"CLOSED_BITMAPS_NOT_DETACHED_AND_RECYCLED")
        }
    }
    private fun cleanup():Boolean {
        deadline=SystemClock.uptimeMillis()+15_000
        return runCatching{
            ui{
                cleanupRequested=true;pending=null
                panels.forEach{it.close()};actors.asReversed().forEach{if(!it.isFinishing)it.finish()}
            }
            waitFor("ADMITTED_LAUNCH_CLEANUP_UNCONFIRMED"){ui{cleanupResolved()}}
            ui{retireCleanupObserverIfResolved()}
            true
        }.getOrDefault(false)
    }
    override fun onStart(){
        val report=JSONObject().put("runnerVersion",1).put("passed",false)
            .put("scope","native component-only retained listener and raw bitmap lifecycle; NOT IME consumer/receiver/physical-phone evidence")
            .put("coverage",JSONArray(listOf("back","library","manage","select","previous","next")))
            .put("componentRevealMethod","direct requestRectangleOnScreen; callback positives are actual emulated finger taps")
            .put("workersStarted",0)
        var outcome=Activity.RESULT_CANCELED
        try{
            must(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android.sizingfixture"&&context.packageName=="dev.lip.android.sizingfixture.test","OWNED_MAIN_TARGET_EMULATOR_REQUIRED")
            owner=args.getString("owner")?:throw AssertionError("OWNER_REQUIRED")
            must(UUID.fromString(owner).toString()==owner,"CANONICAL_OWNER_REQUIRED");report.put("owner",owner)
            deadline=SystemClock.uptimeMillis()+120_000
            application=targetContext.applicationContext as Application
            ui{application.registerActivityLifecycleCallbacks(callbacks);registered=true}
            ax=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ax.serviceInfo=ax.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            stage="current-pack-positives";val a=launch("a");val first=install(a)
            listOf("back","library","manage","previous","next","attach").forEach{physical(first,it)}
            cases.put(JSONObject().put("case","current-pack-finger-positives").put("callbacks",JSONObject(ui{first.counts.snapshot()})))
            stage="pending-original"
            val beforePending=ui{first.counts.snapshot()}
            ui{first.panel.setAttachmentPending(true);must(!first.controls.getValue("attach").isEnabled,"PENDING_ATTACHMENT_STILL_ENABLED");first.controls.getValue("attach").performClick()}
            must(ui{first.counts.snapshot()}==beforePending,"PENDING_ATTACHMENT_CALLBACK_ESCAPED")
            ui{first.panel.setAttachmentPending(false)};physical(first,"attach")
            cases.put(JSONObject().put("case","pending-original-refused-then-fresh-finger").put("passed",true))
            stage="attached-hidden"
            ui{first.panel.visibility=View.GONE;must(first.controls.values.all{it.isAttachedToWindow&&!it.isShown},"HIDDEN_CONTROLS_MUST_REMAIN_ATTACHED")}
            retainedNoDispatch(first,"ATTACHED_HIDDEN_CALLBACK_ESCAPED")
            ui{first.panel.visibility=View.VISIBLE};reveal(first.controls.getValue("back"))
            cases.put(JSONObject().put("case","attached-gone-no-layout").put("passed",true))
            stage="attached-close"
            must(ui{first.panel.isAttachedToWindow&&first.panel.isShown},"CLOSE_CASE_REQUIRES_SHOWN_ATTACHED_PANEL")
            closePixels(first);retainedNoDispatch(first,"ATTACHED_CLOSED_CALLBACK_ESCAPED")
            cases.put(JSONObject().put("case","attached-close-all-eight-bitmaps").put("passed",true))
            stage="library-select";val select=install(a,true);physical(select,"select")
            ui{select.panel.visibility=View.GONE;must(select.controls.getValue("select").isAttachedToWindow,"HIDDEN_SELECT_MUST_REMAIN_ATTACHED")}
            retainedNoDispatch(select,"HIDDEN_LIBRARY_SELECT_ESCAPED")
            ui{select.panel.visibility=View.VISIBLE};reveal(select.controls.getValue("select"))
            ui{select.panel.close()};retainedNoDispatch(select,"CLOSED_LIBRARY_SELECT_ESCAPED")
            cases.put(JSONObject().put("case","library-select-current-hidden-close").put("passed",true))
            stage="detached-select";val detached=install(a,true);physical(detached,"select")
            // Fresh positive immediately precedes replacement: no explicit close masks detachment.
            val windowOld=install(a)
            must(ui{!detached.panel.isAttachedToWindow&&!detached.controls.getValue("select").isAttachedToWindow},"OLD_LIBRARY_NOT_DETACHED")
            retainedNoDispatch(detached,"DETACHED_LIBRARY_SELECT_ESCAPED")
            cases.put(JSONObject().put("case","fresh-library-detachment-auto-close").put("passed",true))
            physical(windowOld,"attach")
            stage="obsolete-window";val b=launch("b");val current=install(b)
            waitFor("OLD_WINDOW_NOT_HIDDEN"){ui{windowOld.panel.windowVisibility!=View.VISIBLE}}
            must(ui{windowOld.panel.isAttachedToWindow&&windowOld.controls.values.all{it.isAttachedToWindow}},"WINDOW_NEGATIVE_REQUIRES_RETAINED_ATTACHMENTS")
            retainedNoDispatch(windowOld,"OBSOLETE_WINDOW_CALLBACK_ESCAPED")
            physical(current,"back")
            cases.put(JSONObject().put("case","old-attached-window-vs-current-window").put("passed",true))
            stage="obsolete-panel";val replacement=install(b,true)
            must(ui{!current.panel.isAttachedToWindow},"REPLACED_PANEL_NOT_DETACHED")
            retainedNoDispatch(current,"OBSOLETE_PANEL_CALLBACK_ESCAPED")
            physical(replacement,"select")
            cases.put(JSONObject().put("case","obsolete-panel-vs-current-replacement").put("passed",true))
            report.put("passed",true);outcome=Activity.RESULT_OK
        }catch(t:Throwable){report.put("failureStage",stage).put("failureType",t.javaClass.simpleName);if(t is AssertionError)report.put("failureToken",t.message)}
        finally{
            val cleaned=cleanup()
            val resolution=ui{JSONObject().put("admittedLaunches",launches.size)
                .put("createdLaunches",launches.count{it.activity.get()!=null})
                .put("exactCreatedInstances",actors.size).put("destroyedInstances",actors.count{it.isDestroyed})
                .put("duplicateCreation",launches.any{it.duplicateCreation})
                .put("lateCreationFenceRetained",registered&&cleanupRequested)
                .put("state",if(cleaned)"CONFIRMED" else "UNCONFIRMED")}
            report.put("cleanupConfirmed",cleaned).put("cleanupResolution",resolution).put("cases",cases).put("taps",taps)
            if(!cleaned){report.put("passed",false);outcome=Activity.RESULT_CANCELED}
            finish(outcome,Bundle().apply{putString("lifecycleResult",report.toString())})
        }
    }
}
