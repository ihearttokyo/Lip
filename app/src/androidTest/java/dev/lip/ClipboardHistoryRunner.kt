package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.lip.auth.SecureStore
import dev.lip.keyboard.ClipboardHistoryController
import dev.lip.keyboard.LipInputMethodService
import dev.lip.keyboard.SavedClip
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Owned emulator only. GUI drives consent/history; direct controller access is read-only except fail-safe cleanup. */
class ClipboardHistoryRunner:Instrumentation(){
    private lateinit var automation:UiAutomation
    private lateinit var clipboard:ClipboardManager
    private val panel="Show clipboard actions and optional encrypted text history"
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun <T> ui(action:()->T):T{
        val value=AtomicReference<T>();val error=AtomicReference<Throwable?>()
        runOnMainSync{try{value.set(action())}catch(failure:Throwable){error.set(failure)}}
        error.get()?.let{throw IllegalStateException("History fixture operation failed: "+it.javaClass.simpleName,it)}
        return value.get()
    }
    private fun controller():ClipboardHistoryController{
        val service=checkNotNull(LipInputMethodService.debugInstance?.get()){"Owned debug IME is not connected"}
        return service.javaClass.getDeclaredField("history").apply{isAccessible=true}.get(service) as ClipboardHistoryController
    }
    private fun all():List<AccessibilityNodeInfo>{
        val result=ArrayList<AccessibilityNodeInfo>()
        fun walk(n:AccessibilityNodeInfo?,depth:Int){if(n==null||depth>32||result.size>2048)return;result.add(n);for(i in 0 until n.childCount)walk(n.getChild(i),depth+1)}
        automation.windows.forEach{walk(it.root,0)};return result
    }
    private fun find(description:String):AccessibilityNodeInfo{
        val deadline=SystemClock.uptimeMillis()+5000
        do{
            val nodes=all().filter{it.packageName?.toString() in setOf("dev.lip.android","dev.lip.android.test")&&it.contentDescription?.toString()==description}
            if(nodes.size==1)return nodes.single()
            SystemClock.sleep(60)
        }while(SystemClock.uptimeMillis()<deadline)
        error("Owned fixture control unavailable: $description")
    }
    private fun findClip(id:String,command:String):AccessibilityNodeInfo{
        val deadline=SystemClock.uptimeMillis()+5000
        do{
            val matches=all().filter{it.packageName?.toString()=="dev.lip.android"&&it.extras.getString("dev.lip.clipId")==id&&it.extras.getString("dev.lip.clipCommand")==command}
            if(matches.size==1)return matches.single()
            SystemClock.sleep(60)
        }while(SystemClock.uptimeMillis()<deadline)
        error("Owned saved-clip control unavailable: $command")
    }
    private fun touch(description:String)=touchNode({find(description)},description)
    private fun touchClip(id:String,command:String)=touchNode({findClip(id,command)},"saved clip $command")
    private fun touchNode(nodeProvider:()->AccessibilityNodeInfo,description:String){
        nodeProvider().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        automation.waitForIdle(150,5000)
        val node=nodeProvider();check(node.isVisibleToUser&&node.isEnabled){"Owned fixture control disabled or hidden: $description"}
        val r=android.graphics.Rect();node.getBoundsInScreen(r);check(!r.isEmpty)
        val time=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
            val e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,r.exactCenterX(),r.exactCenterY(),0);e.source=InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(e,true));e.recycle()
        }
        SystemClock.sleep(90)
    }
    private fun await(description:String,condition:()->Boolean){
        val deadline=SystemClock.uptimeMillis()+15000
        while(!condition()&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(60)
        check(condition()){description}
    }
    private fun items():List<SavedClip> = ui{controller().items.toList()}
    private fun settled(){await("History operation did not settle"){ui{!controller().busy}}}
    private fun clip(text:String,sensitive:Boolean=false){ui{
        val data=ClipData.newPlainText("Synthetic Lip history fixture",text)
        if(sensitive)data.description.extras=PersistableBundle().apply{putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true)}
        clipboard.setPrimaryClip(data)
    }}
    private fun clipText()=ui{clipboard.primaryClip?.getItemAt(0)?.text?.toString()}
    private fun editorText()=find("Synthetic ordinary editor").text?.toString().orEmpty()
    private fun cleanEditor(){
        val arguments=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"")}
        check(find("Synthetic ordinary editor").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,arguments))
        touch("Synthetic ordinary editor");find("Record with the selected local Bark model")
    }
    override fun onStart(){
        val report=Bundle();var outcome=Activity.RESULT_CANCELED;var ownsTestHistory=false;var captured=false;var saved:ClipData?=null
        var workerRelease:java.util.concurrent.CountDownLatch?=null
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android"){"Owned emulator/debug package required"}
            check(targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
            val prefs=targetContext.getSharedPreferences("preferences",0);val secure=SecureStore(targetContext)
            check(!prefs.getBoolean("clipboardHistoryEnabled",false)&&!prefs.getBoolean("clipboardHistoryErasePending",false)&&secure.names("clipboard-history-v1").isEmpty()){
                "History is not pristine; refusing to alter existing saved items"
            }
            check(!targetContext.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
            check(android.provider.Settings.Secure.getString(targetContext.contentResolver,android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)=="dev.lip.android/dev.lip.keyboard.LipInputMethodService")
            ownsTestHistory=true;clipboard=targetContext.getSystemService(ClipboardManager::class.java)
            saved=ui{clipboard.primaryClip};captured=true
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            val token=java.util.UUID.randomUUID().toString()
            targetContext.startActivity(Intent().setClassName("dev.lip.android.test","dev.lip.ImeFixtureActivity")
                .putExtra("fixtureGeneration",token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            find("Lip synthetic generation $token");cleanEditor()
            clip("LIP_BEFORE_OPT_IN");touch(panel);settled()
            check(!ui{controller().enabled}&&items().isEmpty()&&secure.names("clipboard-history-v1").isEmpty())
            report.putBoolean("HISTORY-OFF-NO-FILE-OR-CAPTURE",true)
            touch("Enable clipboard text history");touch("Agree and enable clipboard text history");settled()
            check(ui{controller().enabled}&&items().isEmpty()){"Enabling imported the previous clipboard"}
            report.putBoolean("HISTORY-CONSENT-NOT-RETROACTIVE",true)
            touch("Save current clipboard to encrypted history")
            await("Explicit import did not save current clipboard"){items().size==1};settled()
            check(items().single().text=="LIP_BEFORE_OPT_IN")
            touchClip(items().single().id,"delete");settled();check(items().isEmpty())
            report.putBoolean("HISTORY-EXPLICIT-CURRENT-IMPORT",true)
            val alpha="LIP_HISTORY_ALPHA_"+token;val beta="LIP_HISTORY_BETA_"+token+" 日本語 😀"
            clip(alpha);await("Future copy not saved"){items().any{it.text==alpha}};settled()
            val alphaId=items().single{it.text==alpha}.id
            val cipher=File(targetContext.noBackupFilesDir,"encrypted/clipboard-history-v1").readBytes()
            check(cipher.size>29&&cipher[0]==1.toByte()&&!String(cipher,Charsets.ISO_8859_1).contains(alpha))
            report.putBoolean("HISTORY-FUTURE-COPY-ENCRYPTED",true)
            clip("LIP_MARKED_SENSITIVE",true);SystemClock.sleep(300);settled();check(items().size==1)
            check(cipher.contentEquals(File(targetContext.noBackupFilesDir,"encrypted/clipboard-history-v1").readBytes()))
            report.putBoolean("HISTORY-SENSITIVE-FLAG-REFUSED",true)
            clip("x".repeat(4097));SystemClock.sleep(300);settled();check(items().size==1)
            check(cipher.contentEquals(File(targetContext.noBackupFilesDir,"encrypted/clipboard-history-v1").readBytes()))
            report.putBoolean("HISTORY-OVERSIZE-REFUSED-WITHOUT-WRITE",true)
            val stalePin=findClip(alphaId,"pin")
            touchClip(alphaId,"pin");settled();check(items().single().pinned)
            touchClip(alphaId,"pin");settled();check(!items().single().pinned)
            stalePin.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200);settled()
            check(!items().single().pinned){"Stale Pin node reversed the later Unpin action"}
            touchClip(alphaId,"pin");settled();check(items().single().pinned)
            val pinNode=findClip(alphaId,"pin")
            check(pinNode.isCheckable&&pinNode.isChecked&&pinNode.contentDescription.toString().startsWith("Unpin saved clipboard text:"))
            clip(alpha);SystemClock.sleep(300);settled();check(items().size==1&&items().single().id==alphaId&&items().single().pinned)
            report.putBoolean("HISTORY-PIN-AND-DEDUPE",true)
            clip(beta);await("Second copy not saved"){items().size==2};settled()
            check(items().any{it.text==beta});report.putBoolean("HISTORY-UNICODE-ROUNDTRIP",true)
            await("Ready history controls never became enabled"){
                val node=findClip(alphaId,"paste");node.isEnabled&&node.isVisibleToUser&&!ui{controller().busy}
            }
            automation.waitForIdle(300,5000)
            check(findClip(alphaId,"paste").isEnabled&&!ui{controller().busy})
            checkNotNull(automation.takeScreenshot()).let{bitmap->
                java.io.FileOutputStream(File(targetContext.filesDir,"clipboard-history-ready.png")).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            touchClip(alphaId,"paste");await("Saved clip paste mismatch"){editorText()==alpha}
            check(clipText()==beta)
            report.putBoolean("HISTORY-PASTE-LEAVES-SYSTEM-CLIPBOARD",true)
            val stale=findClip(alphaId,"paste")
            touchClip(alphaId,"delete");settled();check(items().none{it.id==alphaId})
            stale.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200)
            check(editorText()==alpha&&clipText()==beta)
            report.putBoolean("HISTORY-DELETED-STALE-ITEM-REFUSED",true)
            touch("Synthetic password editor");clip("LIP_PRIVATE_CONTEXT");SystemClock.sleep(300)
            check(items().isEmpty()){"Private view retained plaintext previews"}
            touch("Synthetic ordinary editor");touch(panel);await("History did not reload in ordinary context"){items().size==1};settled()
            check(items().single().text==beta)
            report.putBoolean("HISTORY-PRIVATE-CONTEXT-NO-CAPTURE",true)
            clip(beta);SystemClock.sleep(200);settled()
            val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);workerRelease=release
            ui{
                val c=controller();val executor=c.javaClass.getDeclaredField("worker").apply{isAccessible=true}.get(c) as java.util.concurrent.ExecutorService
                executor.execute{entered.countDown();check(release.await(20,java.util.concurrent.TimeUnit.SECONDS))}
            }
            check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val queued="LIP_QUEUED_BEFORE_CLEAR";clip(queued)
            await("Capture was not queued behind the controlled worker"){ui{controller().busy}}
            touch("Clear clipboard history");touch("Confirm erase clipboard history")
            check(prefs.getBoolean("clipboardHistoryErasePending",false)&&!ui{controller().canCapture()})
            release.countDown();workerRelease=null;settled()
            check(items().isEmpty()&&ui{controller().enabled}&&clipText()==queued)
            report.putBoolean("HISTORY-CLEAR-PENDING-CAPTURE-FENCED",true)
            report.putBoolean("HISTORY-CLEAR-ONLY-LIP-SAVED-ITEMS",true)
            touch("Turn off and erase clipboard history");touch("Confirm turn off and erase clipboard history");settled()
            check(!ui{controller().enabled}&&!ui{controller().paused}&&secure.names("clipboard-history-v1").isEmpty()&&clipText()==queued)
            report.putBoolean("HISTORY-REVOKE-AND-ERASE",true)
            touch("Enable clipboard text history")
            val expiredAgreement=find("Agree and enable clipboard text history")
            touch("Cancel");expiredAgreement.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200)
            check(!ui{controller().enabled}&&secure.names("clipboard-history-v1").isEmpty())
            report.putBoolean("HISTORY-STALE-CONSENT-CANNOT-REENABLE",true)
            outcome=Activity.RESULT_OK
        }catch(error:Throwable){
            report.putString("failure",error.javaClass.simpleName+": "+error.message)
            report.putString("failure-at",error.stackTrace.filter{it.className.startsWith("dev.lip")}.take(3).joinToString(";"))
        }
        finally{
            workerRelease?.countDown()
            try{
                if(ownsTestHistory){
                    ui{LipInputMethodService.debugInstance?.get()?.let{service->
                        val c=service.javaClass.getDeclaredField("history").apply{isAccessible=true}.get(service) as ClipboardHistoryController
                        if(c.enabled||c.paused)c.clear(disable=true)
                    }}
                    val prefs=targetContext.getSharedPreferences("preferences",0)
                    await("Synthetic history cleanup unresolved"){
                        !prefs.getBoolean("clipboardHistoryEnabled",false)&&!prefs.getBoolean("clipboardHistoryErasePending",false)&&SecureStore(targetContext).names("clipboard-history-v1").isEmpty()
                    }
                }
                if(captured){captured=false;ui{if(saved==null)clipboard.clearPrimaryClip()else clipboard.setPrimaryClip(saved!!)}
                    val restored=ui{clipboard.primaryClip}
                    check(LipImeRunner.sameClipContent(saved,restored)){"Prior clipboard payload restoration was not observed"}
                    report.putBoolean("HISTORY-TEST-PRIOR-CLIP-RESTORED",true)
                }
            }catch(cleanup:Throwable){report.putString("cleanup-failure",cleanup.javaClass.simpleName+": "+cleanup.message);outcome=Activity.RESULT_CANCELED}
        }
        if(outcome==Activity.RESULT_OK)report.putString("result","PASS named opt-in visible-keyboard history controls; no background/archive/device or product acceptance")
        finish(outcome,report)
    }
}
