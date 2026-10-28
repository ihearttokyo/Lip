package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputConnectionWrapper
import dev.lip.keyboard.*

/** Owned debug failure-state/local-wrapper faults + actual remote synthetic editor. Not physical/fault-chain proof. */
class EmojiPressureRunner:Instrumentation(){
    private lateinit var automation:UiAutomation
    private fun nodes():List<AccessibilityNodeInfo>{
        val out=ArrayList<AccessibilityNodeInfo>()
        fun walk(n:AccessibilityNodeInfo?,depth:Int){if(n==null||depth>32||out.size>4096)return;out.add(n);for(i in 0 until n.childCount)walk(n.getChild(i),depth+1)}
        automation.windows.forEach{walk(it.root,0)};return out
    }
    private fun find(description:String):AccessibilityNodeInfo{
        val end=SystemClock.uptimeMillis()+5000
        do{val matches=nodes().filter{it.isVisibleToUser&&it.contentDescription?.toString()==description};if(matches.size==1)return matches.single();SystemClock.sleep(60)}while(SystemClock.uptimeMillis()<end)
        error("Native emoji control unavailable: $description")
    }
    private fun tap(description:String){
        automation.waitForIdle(300,3000);val r=Rect();find(description).getBoundsInScreen(r);val down=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,r.exactCenterX(),r.exactCenterY(),0);event.source=InputDevice.SOURCE_TOUCHSCREEN;check(automation.injectInputEvent(event,true));event.recycle()};SystemClock.sleep(120)
    }
    private fun ui(action:()->Unit){
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
        runOnMainSync{try{action()}catch(error:Throwable){failure.set(error)}}
        failure.get()?.let{throw it}
    }
    private fun service()=checkNotNull(LipInputMethodService.debugInstance?.get())
    private fun field(name:String)=service().javaClass.getDeclaredField(name).apply{isAccessible=true}
    private fun call(name:String){val s=service();s.javaClass.getDeclaredMethod(name).apply{isAccessible=true}.invoke(s)}
    private fun editorText()=find("Synthetic ordinary editor").text.toString()
    private fun selectEnd(){
        tap("Synthetic ordinary editor") // Actual touch beyond short SAFE text places the original field caret at its end.
        val deadline=SystemClock.uptimeMillis()+5000
        var ready=false
        do{ui{val s=service();ready=s.javaClass.getDeclaredField("selectionStart").apply{isAccessible=true}.getInt(s)==4&&s.javaClass.getDeclaredField("selectionEnd").apply{isAccessible=true}.getInt(s)==4};if(!ready)SystemClock.sleep(40)}while(!ready&&SystemClock.uptimeMillis()<deadline)
        check(ready){"Original field caret4 was not observed before wrapper injection"}
    }
    private fun waitText(value:String){val end=SystemClock.uptimeMillis()+5000;while(editorText()!=value&&SystemClock.uptimeMillis()<end)SystemClock.sleep(60);check(editorText()==value){"Native synthetic emoji editor mismatch; expected=$value observed=${editorText()}"}}
    private fun searchCoffee(){tap("Show emoji");tap("Search offline emoji");for(c in "coffee")tap(c.toString());tap("Show offline emoji search results");find("Insert emoji hot beverage")}
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val report=Bundle();var result=Activity.RESULT_CANCELED;var stage="setup"
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android")
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            val start=SystemClock.uptimeMillis();val all=EmojiAssets.load(targetContext);report.putLong("diagnostic-catalog-load-ms",SystemClock.uptimeMillis()-start);check(all.items.size==3944)
            val raw=EmojiCatalog.parse(targetContext.assets.open("emoji/english.tsv").bufferedReader().use{it.readText()})
            val index=EmojiCatalog::class.java.getDeclaredField("index").apply{isAccessible=true};check(raw.items==all.items&&index.get(raw)==index.get(all));report.putBoolean("EMOJI-NATIVE-PRECOMPUTED-INDEX-EQUAL",true)
            val corrupt=targetContext.assets.open("emoji/english-indexed.tsv").use{it.readBytes()}.apply{this[100]=(this[100].toInt() xor 1).toByte()}
            check(runCatching{EmojiAssets.verify(corrupt)}.isFailure);report.putBoolean("EMOJI-ACTUAL-APK-ASSET-INTEGRITY",true)
            val generation=java.util.UUID.randomUUID().toString()
            targetContext.startActivity(Intent().setClassName("dev.lip.android.test","dev.lip.ImeFixtureActivity").putExtra("fixtureGeneration",generation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val marker=find("Lip synthetic generation $generation")
            val editor=find("Synthetic ordinary editor");check(editor.windowId==marker.windowId){"Fixture window incarnation mismatch"};check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"SAFE")}));tap("Synthetic ordinary editor");find("Show emoji")
            check(find("Synthetic ordinary editor").windowId==find("Lip synthetic generation $generation").windowId)
            automation.waitForIdle(300,3000);searchCoffee()
            check(find("Synthetic ordinary editor").windowId==find("Lip synthetic generation $generation").windowId)
            // Debug-injected TERMINAL ERROR STATE, not claimed to reproduce a live file-system fault chain.
            ui{check(field("emojiLoad").get(service())==null);field("emojiCatalog").set(service(),null);field("emojiLoadError").set(service(),"Emoji unavailable. Close and reopen to retry.");call("renderKeys")}
            val message="Emoji unavailable. Close and reopen to retry."
            check(nodes().any{it.isVisibleToUser&&it.text?.toString()==message});check(nodes().none{it.isVisibleToUser&&it.text?.toString()=="Loading offline emoji…"})
            SystemClock.sleep(300);ui{check(field("emojiLoad").get(service())==null&&field("emojiCatalog").get(service())==null)}
            report.putBoolean("EMOJI-SIMULATED-LOAD-ERROR-VISIBLE-NO-AUTO-RETRY",true)
            tap("Return from emoji to letters");searchCoffee();report.putBoolean("EMOJI-EXPLICIT-REOPEN-RECOVERS",true)
            for(mode in listOf("finish-refuse","commit-refuse","ambiguous-commit","cleanup-revokes")){
                stage=mode
                tap("Return from emoji to letters")
                check(find("Synthetic ordinary editor").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"SAFE")}))
                selectEnd()
                val finishes=java.util.concurrent.atomic.AtomicInteger();val commits=java.util.concurrent.atomic.AtomicInteger()
                val injected=java.util.concurrent.atomic.AtomicReference<android.view.inputmethod.InputConnection>()
                check(find("Synthetic ordinary editor").windowId==find("Lip synthetic generation $generation").windowId)
                ui{
                    val s=service();val actual=checkNotNull(s.currentInputConnection)
                    val wrapped=object:InputConnectionWrapper(actual,false){
                        override fun finishComposingText():Boolean{
                            finishes.incrementAndGet();if(mode=="finish-refuse")return false
                            val accepted=super.finishComposingText()
                            if(mode=="cleanup-revokes")(field("session").get(s) as ImeSession).editorChanged()
                            return accepted
                        }
                        override fun commitText(value:CharSequence?,position:Int):Boolean{commits.incrementAndGet();if(mode=="commit-refuse")return false;val accepted=super.commitText(value,position);return mode!="ambiguous-commit"&&accepted}
                    }
                    field("debugConnectionOverride").set(s,wrapped);injected.set(wrapped)
                }
                searchCoffee();ui{check(service().currentInputConnection===injected.get()){ "Injected connection lease expired before pick" }};val stale=find("Insert emoji hot beverage");tap("Insert emoji hot beverage");SystemClock.sleep(250)
                waitText(if(mode=="ambiguous-commit")"SAFE☕" else "SAFE")
                val count=commits.get();stale.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(120);check(commits.get()==count&&finishes.get()==1)
                check(commits.get()==if(mode in listOf("commit-refuse","ambiguous-commit"))1 else 0)
                ui{field("debugConnectionOverride").set(service(),null)}
            }
            report.putBoolean("EMOJI-LOCAL-WRAPPER-REFUSAL-AMBIGUITY-NO-RETRY",true)
            report.putBoolean("EMOJI-CLEANUP-REVOCATION-NO-COMMIT",true)
            report.putString("result","PASS APK metadata, simulated load-error UI and local-wrapper faults against actual synthetic editor only; not physical/all-host fault qualification");result=Activity.RESULT_OK
        }catch(error:Throwable){
            report.putString("stage",stage);report.putString("failure",error.stackTraceToString())
            runCatching{ui{
                val keys=field("keys").get(service()) as? android.view.View
                val trace=StringBuilder()
                fun walk(view:android.view.View?,depth:Int){if(view==null||depth>15)return;val description=view.contentDescription?.toString();if(description?.contains("emoji")==true||description?.contains("beverage")==true)trace.append(view.javaClass.simpleName).append("|").append(description).append("|important=").append(view.importantForAccessibility).append("|shown=").append(view.isShown).append("|attached=").append(view.isAttachedToWindow).append("|tempDetached=").append(view.isTemporarilyDetached).append("|token=").append(view.windowToken!=null).append("|localChildren=").append(view.createAccessibilityNodeInfo().childCount).append("|sensitive=").append(if(android.os.Build.VERSION.SDK_INT>=34)view.isAccessibilityDataSensitive else false).append("|obscuredFilter=").append(view.filterTouchesWhenObscured).append("\n");if(view is android.view.ViewGroup)for(i in 0 until view.childCount)walk(view.getChildAt(i),depth+1)}
                walk(keys,0);java.io.File(targetContext.filesDir,"emoji-view-failure.txt").writeText(trace.toString())
            }}
            runCatching{val bitmap=automation.takeScreenshot();if(bitmap!=null){java.io.FileOutputStream(java.io.File(targetContext.filesDir,"emoji-pressure-failure.png")).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
        }
        finally{runCatching{ui{LipInputMethodService.debugInstance?.get()?.let{s->s.javaClass.getDeclaredField("debugConnectionOverride").apply{isAccessible=true}.set(s,null)}}}.onFailure{report.putString("cleanup-failure",it.javaClass.simpleName);result=Activity.RESULT_CANCELED}}
        finish(result,report)
    }
}
