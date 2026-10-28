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
import dev.lip.keyboard.LipInputMethodService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Debug-target instrumentation; controls only disposable fixture + Lip's own worker, not OS internals. */
class SwipePressureRunner:Instrumentation() {
    private lateinit var automation:UiAutomation
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun all():List<AccessibilityNodeInfo> {
        val result=ArrayList<AccessibilityNodeInfo>()
        fun walk(node:AccessibilityNodeInfo?,depth:Int){if(node==null||depth>32||result.size>2048)return;result.add(node);for(i in 0 until node.childCount)walk(node.getChild(i),depth+1)}
        automation.windows.forEach{walk(it.root,0)};return result
    }
    private fun find(description:String,prefix:Boolean=false):AccessibilityNodeInfo {
        val deadline=SystemClock.uptimeMillis()+5000
        do {
            val matches=all().filter{it.isVisibleToUser&&it.packageName?.toString() in setOf("dev.lip.android","dev.lip.android.test")&&
                (if(prefix)it.contentDescription?.toString()?.startsWith(description)==true else it.contentDescription?.toString()==description)}
            if(matches.size==1)return matches.single()
            SystemClock.sleep(70)
        }while(SystemClock.uptimeMillis()<deadline)
        error("Disposable fixture control unavailable: $description")
    }
    private fun touch(x:Float,y:Float){
        val now=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0);event.source=InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event,true));event.recycle()
        }
    }
    private fun show(refuse:Boolean){
        val generation=java.util.UUID.randomUUID().toString()
        targetContext.startActivity(Intent().setClassName("dev.lip.android.test","dev.lip.ImeFixtureActivity")
            .putExtra("refuseImeCommit",refuse).putExtra("fixtureGeneration",generation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        find("Lip synthetic generation $generation") // Never bind an override to the previous activity incarnation.
        val editor=find("Synthetic ordinary editor");val text=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"")}
        check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text));automation.waitForIdle(300,5000)
        val rect=android.graphics.Rect();find("Synthetic ordinary editor").getBoundsInScreen(rect);touch(rect.exactCenterX(),rect.exactCenterY())
        find("Record with the selected local Bark model");automation.waitForIdle(300,5000)
    }
    private fun positions(capitals:Boolean=false):Map<Char,Pair<Float,Float>> = "qwertyuiopasdfghjklzxcvbnm".associateWith { c->
        val rect=android.graphics.Rect();find(if(capitals)c.uppercase() else c.toString()).getBoundsInScreen(rect);rect.exactCenterX() to rect.exactCenterY()
    }
    private fun glide(word:String,positions:Map<Char,Pair<Float,Float>>){
        val path=word.fold(""){out,c->if(out.lastOrNull()==c)out else out+c};val start=SystemClock.uptimeMillis()
        fun event(action:Int,x:Float,y:Float){val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;check(automation.injectInputEvent(e,true));e.recycle()}
        val first=positions.getValue(path.first());event(MotionEvent.ACTION_DOWN,first.first,first.second)
        try{for(i in 1 until path.length)for(step in 1..20){val a=positions.getValue(path[i-1]);val b=positions.getValue(path[i]);val f=step/20f;SystemClock.sleep(10);event(MotionEvent.ACTION_MOVE,a.first+(b.first-a.first)*f,a.second+(b.second-a.second)*f)}}
        finally{val last=positions.getValue(path.last());event(MotionEvent.ACTION_UP,last.first,last.second)}
    }
    private fun text()=find("Synthetic ordinary editor").text?.toString().orEmpty()
    private fun awaitText(expected:String){val deadline=SystemClock.uptimeMillis()+15000;while(text()!=expected&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);check(text()==expected){"Disposable editor text mismatch: ${text()}"}}
    override fun onStart(){
        val report=Bundle();var result=Activity.RESULT_CANCELED;val release=CountDownLatch(1)
        try {
            check(targetContext.packageName=="dev.lip.android")
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            show(false)
            val coords=positions();val entered=CountDownLatch(1)
            runOnMainSync {
                val service=checkNotNull(LipInputMethodService.debugInstance?.get())
                val method=service.javaClass.getDeclaredMethod("worker").apply{isAccessible=true}
                val worker=method.invoke(service)
                val executor=worker.javaClass.getDeclaredField("executor").apply{isAccessible=true}.get(worker) as ThreadPoolExecutor
                executor.execute{entered.countDown();check(release.await(20,TimeUnit.SECONDS))}
            }
            check(entered.await(15,TimeUnit.SECONDS))
            glide("hello",coords)
            find("Keyboard input paused while the accepted swipe is decoding. Wait or tap Cancel.")
            glide("world",coords) // Deliberately refused with visible busy state, not accepted then discarded.
            check(text().isEmpty())
            release.countDown();awaitText("hello ")
            report.putBoolean("DELAYED-WORKER-PRESERVES-ACCEPTED-FIRST-WORD",true)
            automation.waitForIdle(300,5000);glide("world",positions());awaitText("hello world ")
            report.putBoolean("NEXT-WORD-ACCEPTED-AFTER-BACKPRESSURE",true)
            show(false)
            val refused=java.util.concurrent.atomic.AtomicInteger()
            runOnMainSync {
                val service=checkNotNull(LipInputMethodService.debugInstance?.get())
                val base=checkNotNull(service.currentInputConnection)
                val wrapper=object:android.view.inputmethod.InputConnectionWrapper(base,false){
                    override fun commitText(text:CharSequence?,position:Int):Boolean{refused.incrementAndGet();return false}
                }
                service.javaClass.getDeclaredField("debugConnectionOverride").apply{isAccessible=true}.set(service,wrapper)
            }
            val shift=find("Shift; double-tap to lock capitals");val rect=android.graphics.Rect();shift.getBoundsInScreen(rect);touch(rect.exactCenterX(),rect.exactCenterY())
            automation.waitForIdle(300,5000);glide("hello",positions(true))
            find("Check the editor; swipe insertion could not be confirmed",true)
            check(refused.get()==1)
            check(text().isEmpty());SystemClock.sleep(300)
            check(refused.get()==1)
            report.putBoolean("SHIFT-FALSE-COMMIT-WARNING-RETAINED-NO-RETRY",true)
            show(false)
            val finishAttempts=java.util.concurrent.atomic.AtomicInteger()
            val blockedCommits=java.util.concurrent.atomic.AtomicInteger()
            runOnMainSync {
                val service=checkNotNull(LipInputMethodService.debugInstance?.get())
                val base=checkNotNull(service.currentInputConnection)
                val wrapper=object:android.view.inputmethod.InputConnectionWrapper(base,false){
                    override fun finishComposingText():Boolean{finishAttempts.incrementAndGet();return false}
                    override fun commitText(text:CharSequence?,position:Int):Boolean{blockedCommits.incrementAndGet();return super.commitText(text,position)}
                }
                service.javaClass.getDeclaredField("debugConnectionOverride").apply{isAccessible=true}.set(service,wrapper)
            }
            glide("hello",positions())
            find("Check the editor; swipe composition could not be finished",true)
            check(text().isEmpty()&&finishAttempts.get()==1&&blockedCommits.get()==0)
            report.putBoolean("PRIMARY-COMPOSITION-REFUSAL-VISIBLE-NO-COMMIT",true)
            // Injected client outcomes, not claims about booleans propagated by an editor across Binder.
            for(mode in listOf("region-refusal","ambiguous-write","stale-during-region")) {
                show(false)
                val injected=java.util.concurrent.atomic.AtomicReference<android.view.inputmethod.InputConnection>()
                val regions=java.util.concurrent.atomic.AtomicInteger()
                val writes=java.util.concurrent.atomic.AtomicInteger()
                runOnMainSync {
                    val service=checkNotNull(LipInputMethodService.debugInstance?.get())
                    val base=checkNotNull(service.currentInputConnection)
                    val wrapper=object:android.view.inputmethod.InputConnectionWrapper(base,false){
                        override fun setComposingRegion(start:Int,end:Int):Boolean {
                            regions.incrementAndGet()
                            if(mode=="region-refusal")return false
                            val result=super.setComposingRegion(start,end)
                            if(mode=="stale-during-region") {
                                val session=service.javaClass.getDeclaredField("session").apply{isAccessible=true}.get(service) as dev.lip.keyboard.ImeSession
                                session.editorChanged()
                            }
                            return result
                        }
                        override fun setComposingText(text:CharSequence?,position:Int):Boolean {
                            writes.incrementAndGet()
                            val result=super.setComposingText(text,position)
                            return if(mode=="ambiguous-write")false else result
                        }
                    }
                    injected.set(wrapper)
                    service.javaClass.getDeclaredField("debugConnectionOverride").apply{isAccessible=true}.set(service,wrapper)
                }
                glide("hello",positions());awaitText("hello ")
                val deadline=SystemClock.uptimeMillis()+5000
                var choices:List<AccessibilityNodeInfo>
                do {
                    choices=all().filter{it.isVisibleToUser&&it.isEnabled&&it.packageName?.toString()=="dev.lip.android"&&it.contentDescription?.toString()?.startsWith("Choose swipe suggestion ")==true}
                    if(choices.size==3)break
                    SystemClock.sleep(80)
                }while(SystemClock.uptimeMillis()<deadline)
                check(choices.size==3){"Verified candidate controls missing"}
                runOnMainSync{check(LipInputMethodService.debugInstance?.get()?.currentInputConnection===injected.get()){"$mode injected connection expired before touch"}}
                val option=choices[1];val replacement=option.contentDescription.toString().removePrefix("Choose swipe suggestion ")
                val bounds=android.graphics.Rect();option.getBoundsInScreen(bounds);touch(bounds.exactCenterX(),bounds.exactCenterY())
                find("Check the editor; correction could not be confirmed",true)
                SystemClock.sleep(400)
                check(regions.get()==1){"$mode region attempts=${regions.get()}"}
                check(writes.get()==if(mode=="ambiguous-write")1 else 0){"$mode write attempts=${writes.get()}"}
                check(text()==if(mode=="ambiguous-write")"$replacement " else "hello "){"$mode unexpected text=${text()}"}
                check(all().none{it.isVisibleToUser&&it.contentDescription?.toString()?.startsWith("Choose swipe suggestion ")==true}){"$mode stale candidate controls remained"}
                report.putBoolean("CANDIDATE-${mode.uppercase()}-NO-RETRY",true)
            }
            report.putString("result","PASS delayed real-touch/IME/neural backpressure and debug client false-commit feedback controls; not complete keyboard/product acceptance")
            result=Activity.RESULT_OK
        }catch(error:Throwable){report.putString("failure",error.javaClass.simpleName+": "+error.message)}
        finally{
            val cleanup=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            runOnMainSync {
                try{LipInputMethodService.debugInstance?.get()?.let{service->
                    service.javaClass.getDeclaredMethod("stopSwipe").apply{isAccessible=true}.invoke(service)
                    service.javaClass.getDeclaredField("debugConnectionOverride").apply{isAccessible=true}.set(service,null)
                }}catch(error:Throwable){cleanup.set(error)}
            }
            release.countDown()
            if(cleanup.get()!=null){report.putString("cleanup-failure",cleanup.get()!!.javaClass.simpleName);result=Activity.RESULT_CANCELED}
        }
        finish(result,report)
    }
}
