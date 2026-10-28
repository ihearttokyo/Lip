package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.lip.keyboard.GifRequestOwner
import dev.lip.keyboard.LipInputMethodService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Debug app-worker barrier + real remote editor composition, not a forged callback or OS reflection. */
class GifPressureRunner:Instrumentation(){
    private lateinit var automation:UiAutomation
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun <T> ui(action:()->T):T{val out=AtomicReference<T>();val error=AtomicReference<Throwable?>();runOnMainSync{try{out.set(action())}catch(e:Throwable){error.set(e)}};error.get()?.let{throw it};return out.get()}
    private fun all():List<AccessibilityNodeInfo>{val nodes=ArrayList<AccessibilityNodeInfo>();fun walk(n:AccessibilityNodeInfo?,depth:Int){if(n==null||depth>32||nodes.size>2048)return;nodes.add(n);for(i in 0 until n.childCount)walk(n.getChild(i),depth+1)};automation.windows.forEach{walk(it.root,0)};return nodes}
    private fun find(description:String,prefix:Boolean=false):AccessibilityNodeInfo{
        val end=SystemClock.uptimeMillis()+5000
        do{val n=all().filter{it.packageName?.toString() in setOf("dev.lip.android","dev.lip.android.test")&&(if(prefix)it.contentDescription?.toString()?.startsWith(description)==true else it.contentDescription?.toString()==description)};if(n.size==1)return n.single();SystemClock.sleep(60)}while(SystemClock.uptimeMillis()<end)
        error("Owned GIF fixture control missing: $description")
    }
    private fun touch(description:String){
        find(description).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);automation.waitForIdle(200,5000)
        val node=find(description);check(node.isVisibleToUser&&node.isEnabled);val r=android.graphics.Rect();node.getBoundsInScreen(r);val time=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){val e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,r.exactCenterX(),r.exactCenterY(),0);e.source=InputDevice.SOURCE_TOUCHSCREEN;check(automation.injectInputEvent(e,true));e.recycle()};SystemClock.sleep(80)
    }
    private fun owner()=LipInputMethodService.debugInstance!!.get()!!.let{it.javaClass.getDeclaredField("gifOwner").apply{isAccessible=true}.get(it) as GifRequestOwner}
    override fun onStart(){
        val report=Bundle();var outcome=Activity.RESULT_CANCELED;val release=CountDownLatch(1)
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android")
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            val generation=java.util.UUID.randomUUID().toString()
            targetContext.startActivity(Intent().setClassName("dev.lip.android.test","dev.lip.ImeFixtureActivity").putExtra("fixtureGeneration",generation).putExtra("gifEditor",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            find("Lip synthetic generation $generation")
            val text=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"LEFT RIGHT")};check(find("Synthetic ordinary editor").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text));touch("Synthetic ordinary editor")
            val selection=Bundle().apply{putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,5);putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,5)}
            check(find("Synthetic ordinary editor").performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selection));find("Record with the selected local Bark model")
            touch("Show local GIF catalog")
            val entered=CountDownLatch(1)
            ui{val service=LipInputMethodService.debugInstance!!.get()!!;val worker=service.javaClass.getDeclaredField("gifWorker").apply{isAccessible=true}.get(service) as ThreadPoolExecutor;worker.execute{entered.countDown();check(release.await(20,TimeUnit.SECONDS))}}
            check(entered.await(5,TimeUnit.SECONDS));touch("Attach GIF: Grinning face");check(ui{owner().current!=null})
            check(find("Start synthetic host composition").performAction(AccessibilityNodeInfo.ACTION_CLICK));check(find("Inspect synthetic host composition").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val before=find("Synthetic composition 0:4",true).contentDescription.toString()
            val end=SystemClock.uptimeMillis()+3000;while(ui{owner().current!=null}&&SystemClock.uptimeMillis()<end)SystemClock.sleep(60)
            check(ui{owner().current==null}){"New composing span did not revoke GIF"}
            release.countDown();SystemClock.sleep(300);check(find("Inspect synthetic host composition").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            check(find("Synthetic composition 0:4",true).contentDescription.toString()==before){"GIF completion finished newer host composition or attached content"}
            check(before.endsWith("gifs=0"));check(find("Synthetic ordinary editor").text.toString()=="LEFT RIGHT")
            report.putBoolean("GIF-NEW-HOST-COMPOSITION-REVOKES-PENDING",true)
            check(find("Finish synthetic host composition").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            outcome=Activity.RESULT_OK;report.putString("result","PASS real composing-span/worker-barrier refusal; no phone/product acceptance")
        }catch(error:Throwable){report.putString("failure",error.stackTraceToString())}
        finally{
            // Revoke ownership before allowing queued completion to race with failed-case cleanup.
            // The barrier must still open if revocation itself fails.
            try{
                runCatching{ui{LipInputMethodService.debugInstance?.get()?.let{it.javaClass.getDeclaredMethod("stopGif").apply{isAccessible=true}.invoke(it)}}}.onFailure{report.putString("cleanup-failure",it.javaClass.simpleName);outcome=Activity.RESULT_CANCELED}
            }finally{release.countDown()}
        }
        finish(outcome,report)
    }
}
