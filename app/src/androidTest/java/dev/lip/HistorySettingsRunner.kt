package dev.lip

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual owned-emulator Settings controls, preference commit and landscape recreation.
 * No microphone, private history export, external provider or physical-phone action. */
class HistorySettingsRunner : Instrumentation() {
    private var restoreOnly=false
    private var forceAbort=false
    private var falseSwitchReturns=0
    private lateinit var ax:android.app.UiAutomation
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); restoreOnly=arguments?.getString("restore_only")=="true";forceAbort=arguments?.getString("force_abort")=="true"; start() }
    private val current=AtomicReference<Activity?>()
    private val created=java.util.concurrent.CopyOnWriteArrayList<Activity>()
    private val images=mutableListOf<String>()
    private val nonce=UUID.randomUUID().toString().take(8)
    private val callbacks=object:Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a:Activity,b:Bundle?){if(a is MainActivity)created.add(a)}
        override fun onActivityResumed(a:Activity){if(a is MainActivity)current.set(a)}
        override fun onActivityStarted(a:Activity){};override fun onActivityPaused(a:Activity){};override fun onActivityStopped(a:Activity){}
        override fun onActivitySaveInstanceState(a:Activity,b:Bundle){};override fun onActivityDestroyed(a:Activity){}
    }
    private fun mainAction(action:()->Unit) {
        val failure=AtomicReference<Throwable?>()
        runOnMainSync { try{action()}catch(e:Throwable){failure.set(e)} }
        failure.get()?.let{throw it}
    }
    private fun journal()=File(targetContext.noBackupFilesDir,"hx-ui-recovery.json")
    private fun syncJournalDirectory() {
        val fd=android.system.Os.open(targetContext.noBackupFilesDir.absolutePath,
            android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_CLOEXEC or android.system.OsConstants.O_NOFOLLOW,0)
        try{check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode));android.system.Os.fsync(fd)}finally{android.system.Os.close(fd)}
    }
    private fun saveJournal(original:Boolean?,rotation:Int) {
        val file=journal();check(file.createNewFile()){"Unresolved recovery journal; run restore_only, do not overwrite"}
        val bytes=JSONObject().put("version",1).put("package","dev.lip.android").put("present",original!=null)
            .put("value",original?:JSONObject.NULL).put("type",if(original==null)"absent" else "boolean").put("rotation",rotation).toString().toByteArray()
        java.io.FileOutputStream(file).use{it.write(bytes);it.fd.sync()}
        syncJournalDirectory() // Bind the exclusive directory entry before preferences/orientation mutate.
    }
    private fun recovery():Pair<Boolean?,Int> {
        val file=journal();check(file.isFile&&file.length() in 1..4096){"Recovery journal invalid; preserved"}
        val state=JSONObject(file.readText());check(state.getInt("version")==1&&state.getString("package")=="dev.lip.android")
        val present=state.getBoolean("present");check(state.getString("type")==if(present)"boolean" else "absent")
        val value=if(present){check(state.get("value") is Boolean);state.getBoolean("value")}else{check(state.isNull("value"));null}
        val rotation=state.getInt("rotation");check(rotation in 0..3);return value to rotation
    }
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    private fun waitFor(label:String,condition:()->Boolean){val until=SystemClock.uptimeMillis()+8000;var ready=condition();while(!ready&&SystemClock.uptimeMillis()<until){SystemClock.sleep(50);ready=condition()};check(ready){label}}
    private fun dialogNode(label:String,click:Boolean):Boolean {
        val windows=ax.windows
        try{for(window in windows){
            if(!window.isActive&&!window.isFocused)continue
            val root=window.root?:continue
            try{if(root.packageName?.toString()!="dev.lip.android")continue
                val nodes=root.findAccessibilityNodeInfosByText(label)
                try{val node=nodes.firstOrNull{it.text?.toString()?.equals(label,true)==true&&it.isVisibleToUser&&(!click||it.isClickable)}?:continue
                    if(click)check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){"Dialog action not accepted"}
                    return true
                }finally{nodes.forEach{it.recycle()}}
            }finally{root.recycle()}
        }}finally{windows.forEach{it.recycle()}}
        return false
    }
    private fun app()=checkNotNull(current.get()).also{check(!it.isDestroyed)}
    private fun switch(a:Activity)=views(a.window.decorView).filterIsInstance<Switch>().single{it.text.toString()=="Keep encrypted transcript history"}
    private fun clickTab(name:String){mainAction{check(views(app().window.decorView).filterIsInstance<Button>().single{it.text.toString()==name}.performClick())};waitForIdleSync()}
    private fun clickSwitch(warning:Boolean=true){
        mainAction{val s=switch(app());s.requestRectangleOnScreen(Rect(0,0,s.width,s.height),true);check(s.isShown);if(!s.performClick())falseSwitchReturns++}
        waitForIdleSync()
        if(warning)waitFor("Consent dialog not shown"){dialogNode("Keep future dictation history?",false)}
    }
    private fun dialogClick(text:String){waitFor("Dialog button not available: $text"){dialogNode(text,true)};waitForIdleSync()}
    private fun showPreference(expected:String) {
        waitFor("Expected preference text not rendered") {
            var rendered=false
            mainAction{val view=views(app().window.decorView).filterIsInstance<TextView>().firstOrNull{it.text.toString()==expected}
                if(view!=null){view.requestRectangleOnScreen(Rect(0,0,view.width,view.height),true);rendered=true}}
            rendered
        }
        waitFor("Preference status clipped or outside viewport"){
            var visible=false
            mainAction{val view=views(app().window.decorView).filterIsInstance<TextView>().first{it.text.toString()==expected};val bounds=Rect()
                visible=view.height>0&&view.getGlobalVisibleRect(bounds)&&bounds.height()==view.height}
            visible
        }
        waitForIdleSync()
    }
    private fun screenshot(label:String){
        var previous:android.graphics.Bitmap?=null
        val deadline=SystemClock.uptimeMillis()+8000
        var stableSince=SystemClock.uptimeMillis()
        try{while(true){
            val next=checkNotNull(ax.takeScreenshot());val now=SystemClock.uptimeMillis()
            val same=previous?.let{next.sameAs(it)}==true
            previous?.recycle();previous=next
            if(!same)stableSince=now
            if(now-stableSince>=500){
                val file=File(targetContext.getExternalFilesDir(null),"hx-ui-$nonce-$label.png")
                file.outputStream().use{check(next.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};images.add(file.absolutePath);return
            }
            check(now<deadline){"Screenshot never settled for500ms; no final-frame claim"}
            SystemClock.sleep(100)
        }}finally{previous?.recycle()}
    }
    private fun diskChoice():Boolean? {
        val file=File(targetContext.applicationInfo.dataDir,"shared_prefs/preferences.xml")
        if(!file.exists())return null
        file.inputStream().use { input ->
            val xml=android.util.Xml.newPullParser();xml.setInput(input,"UTF-8")
            while(xml.eventType!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){
                if(xml.eventType==org.xmlpull.v1.XmlPullParser.START_TAG&&xml.name=="boolean"&&xml.getAttributeValue(null,"name")=="history.consent.v1")return xml.getAttributeValue(null,"value")=="true"
                xml.next()
            }
        }
        return null
    }
    private fun preference(store:AppStore,value:Boolean){
        val done=CountDownLatch(1);val failed=AtomicReference<Throwable?>()
        store.setHistoryEnabled(value){if(it.failed)failed.set(IllegalStateException("Preference not confirmed"));done.countDown()}
        check(done.await(10,TimeUnit.SECONDS)){"Preference callback timed out"};failed.get()?.let{throw it}
    }
    override fun onStart(){
        val report=JSONObject().put("passed",false).put("scope","actual Android consent Settings, SharedPreferences and landscape recreation; no audio/lock/export-provider proof")
        val application=targetContext.applicationContext as Application
        var store:AppStore?=null;var original:Boolean?=null;var saved=false;var cleanup=true;var originalRotation:Int?=null
        try {
            check(Build.HARDWARE in listOf("ranchu","goldfish")&&Build.PRODUCT.contains("sdk")&&targetContext.packageName=="dev.lip.android")
            check(!targetContext.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked){"Locked emulator; no unlock attempted"}
            ax=getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ax.serviceInfo=ax.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            originalRotation=targetContext.getSystemService(android.view.WindowManager::class.java).defaultDisplay.rotation
            val dictation=Dictation.get(targetContext);check(!dictation.busy){"Active recording; refusing fixture"}
            store=dictation.store;check(!store.historyReadiness().preparing){"Another consent change pending"}
            if(restoreOnly){
                val prior=recovery();original=prior.first;originalRotation=prior.second;saved=true
                report.put("recovery_only",true).put("passed",true)
            }else{
            check(!journal().exists()){"Unresolved recovery journal; explicit restore_only required"}
            val raw=store.settings.all["history.consent.v1"];check(raw==null||raw is Boolean){"Unknown existing preference; refusing override"};original=raw as Boolean?
            saveJournal(original,checkNotNull(originalRotation));saved=true
            val legacy=store.settings.all["history"]
            val renderChoices=store.settings.all.filterKeys{it in listOf("language","style")}
            preference(store,false)
            application.registerActivityLifecycleCallbacks(callbacks)
            val initial=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            current.set(initial);if(!created.contains(initial))created.add(initial)
            waitForIdleSync();clickTab("Settings")
            mainAction{check(!switch(app()).isChecked)}
            val offRequest=store.historyReadiness().request
            clickSwitch();screenshot("on-warning")
            mainAction{check(!switch(app()).isChecked)};check(store.historyTicket()==null)
            dialogClick("Keep off");check(store.historyReadiness().request==offRequest&&store.historyTicket()==null)
            clickSwitch();dialogClick("Enable local history")
            waitFor("On preference/readiness not reflected"){store.historyReadiness().ready}
            check(store.settings.getBoolean("history.consent.v1",false)&&diskChoice()==true)
            val old=checkNotNull(store.historyTicket())
            waitFor("Ready preference widget not rendered") {
                var rendered=false
                mainAction{rendered=switch(app()).isChecked&&views(app().window.decorView).filterIsInstance<TextView>().any{it.text.toString().contains("On for eligible new recordings")}}
                rendered
            }
            showPreference(HistoryConsentPresentation.describe(store.historyReadiness()))
            screenshot("on-ready")
            check(!forceAbort){"Controlled abort after actual On commit"}
            val before=app();mainAction{before.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            waitFor("Landscape Activity not recreated"){current.get()!=before&&current.get()?.resources?.configuration?.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE&&current.get()?.hasWindowFocus()==true}
            waitForIdleSync();mainAction{check(switch(app()).isChecked)}
            clickSwitch(warning=false);waitFor("Off not confirmed"){!store.historyReadiness().preparing&&!store.historyReadiness().requested}
            check(!store.settings.getBoolean("history.consent.v1",true)&&diskChoice()==false);check(!store.historyResultCurrent(old))
            check(!store.save(Transcript(raw="synthetic never retained",clean="Synthetic never retained.",language="en-US",usedChatGpt=false),old))
            showPreference(HistoryConsentPresentation.describe(store.historyReadiness()))
            screenshot("landscape-off")
            check(store.settings.all["history"]==legacy&&store.settings.all.filterKeys{it in listOf("language","style")}==renderChoices){"Rendering settings changed unrelated choices"}
            report.put("decline_no_consent",true).put("confirm_persisted_ready",true).put("landscape_recreation_and_off_old_ticket_denied",true).put("passed",true)
            }
        }catch(e:Throwable){report.put("error",e.javaClass.simpleName+": "+(e.message?:"fixture failed"))}
        finally{
            try{mainAction{for(a in created)if(!a.isDestroyed){a.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;a.finish()}};waitForIdleSync()}catch(_:Throwable){cleanup=false}
            application.unregisterActivityLifecycleCallbacks(callbacks)
            if(saved&&store!=null)try{preference(store,original==true);if(original==null)check(store.settings.edit().remove("history.consent.v1").commit());check(store.settings.all["history.consent.v1"]==original&&diskChoice()==original)}catch(_:Throwable){cleanup=false}
            if(originalRotation!=null)try{waitFor("Original display rotation not restored"){targetContext.getSystemService(android.view.WindowManager::class.java).defaultDisplay.rotation==originalRotation}}catch(_:Throwable){cleanup=false}
            if(saved&&cleanup)try{check(journal().delete());syncJournalDirectory()}catch(_:Throwable){cleanup=false}
            report.put("switch_false_returns",falseSwitchReturns).put("recovery_journal_exists",journal().exists())
            report.put("original_display_rotation",originalRotation).put("final_display_rotation",targetContext.getSystemService(android.view.WindowManager::class.java).defaultDisplay.rotation)
            report.put("cleanup_ok",cleanup).put("screenshots",org.json.JSONArray(images)).put("phone_tested",false).put("process_restart_tested",false)
            if(!cleanup)report.put("passed",false)
        }
        finish(if(report.getBoolean("passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString(REPORT_KEY_STREAMRESULT,"\nHISTORY_SETTINGS_RESULT $report\n")})
    }
}
