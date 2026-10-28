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
import dev.lip.auth.SecureStore

/** Actual app storage/preferences → actual IME; all new words are synthetic, originals RAM-only/restored. */
class TypingStoreRunner:Instrumentation(){
    private lateinit var automation:UiAutomation
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun nodes():List<AccessibilityNodeInfo>{
        val out=ArrayList<AccessibilityNodeInfo>()
        fun walk(n:AccessibilityNodeInfo?,depth:Int){if(n==null||depth>32||out.size>2048)return;out.add(n);for(i in 0 until n.childCount)walk(n.getChild(i),depth+1)}
        automation.windows.forEach{walk(it.root,0)}
        return out.filter{it.packageName?.toString() in setOf("dev.lip.android","dev.lip.android.test")}
    }
    private fun find(description:String):AccessibilityNodeInfo{
        val end=SystemClock.uptimeMillis()+5000
        do{val found=nodes().filter{it.contentDescription?.toString()==description};if(found.size==1)return found.single();SystemClock.sleep(60)}while(SystemClock.uptimeMillis()<end)
        error("Synthetic typing store control unavailable: $description")
    }
    private fun touch(description:String){
        automation.waitForIdle(300,5000)
        var node=find(description);val end=SystemClock.uptimeMillis()+5000
        while(!node.isEnabled&&SystemClock.uptimeMillis()<end){SystemClock.sleep(60);node=find(description)}
        check(node.isVisibleToUser&&node.isEnabled)
        val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds);check(!bounds.isEmpty)
        val now=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
            val e=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0);e.source=InputDevice.SOURCE_TOUCHSCREEN
            try{check(automation.injectInputEvent(e,true))}finally{e.recycle()}
        }
        SystemClock.sleep(90)
    }
    private fun editor(){
        val generation=java.util.UUID.randomUUID().toString()
        targetContext.startActivity(Intent().setClassName("dev.lip.android.test","dev.lip.ImeFixtureActivity").putExtra("fixtureGeneration",generation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        find("Lip synthetic generation $generation");touch("Synthetic ordinary editor");find("Record with the selected local Bark model")
    }
    private fun spell(value:String){value.forEach{touch(it.toString())}}
    private fun expect(value:String){
        val end=SystemClock.uptimeMillis()+5000
        do{if(find("Synthetic ordinary editor").text?.toString().orEmpty()==value)return;SystemClock.sleep(60)}while(SystemClock.uptimeMillis()<end)
        error("Synthetic store typing result differs") // Never log stored original/private dictionary contents.
    }
    private fun restoreBoth(dictionary:()->Unit,settings:()->Unit){
        var failure:Throwable?=null
        try{dictionary()}catch(error:Throwable){failure=error}
        try{settings()}catch(error:Throwable){if(failure==null)failure=error else failure!!.addSuppressed(error)}
        failure?.let{throw it}
    }
    override fun onStart(){
        val report=Bundle();var outcome=Activity.RESULT_CANCELED;var restore:(()->Unit)?=null
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android")
            var preferencesRestored=false
            val injected=runCatching{restoreBoth({error("Synthetic dictionary restoration failure")},{preferencesRestored=true})}
            check(injected.isFailure&&preferencesRestored)
            report.putBoolean("STORE-CLEANUP-LEGS-INDEPENDENT",true)
            // Explicit non-scored warm setup. Target-app instrumentation stops the IME UID;
            // the retained startup failure is not repaired or waived by a later fresh window.
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            val setupGeneration=java.util.UUID.randomUUID().toString()
            targetContext.startActivity(Intent().setClassName("dev.lip.android.test","dev.lip.ImeFixtureActivity").putExtra("fixtureGeneration",setupGeneration).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            find("Lip synthetic generation $setupGeneration");touch("Synthetic ordinary editor")
            val setupVisible=runCatching{find("Record with the selected local Bark model")}.isSuccess
            report.putBoolean("SETUP-KEYBOARD-VISIBLE-NOT-STARTUP-QUALIFICATION",setupVisible)
            // Each scored case below gets a different unique fixture; no per-case retry.
            val store=AppStore(targetContext);val secure=SecureStore(targetContext)
            val suggestions=store.settings.contains("typingSuggestions") to store.typingSuggestions
            val automatic=store.settings.contains("typingAutocorrect") to store.typingAutocorrect
            val dictionary=secure.read("dictionary")
            restore={
                restoreBoth({
                    if(dictionary==null)secure.delete("dictionary")else secure.write("dictionary",dictionary)
                    check(secure.read("dictionary")==dictionary){"Dictionary restoration not observed"}
                },{
                    val edit=store.settings.edit()
                    if(suggestions.first)edit.putBoolean("typingSuggestions",suggestions.second)else edit.remove("typingSuggestions")
                    if(automatic.first)edit.putBoolean("typingAutocorrect",automatic.second)else edit.remove("typingAutocorrect")
                    check(edit.commit()){ "Settings restoration failed" }
                    check(store.settings.contains("typingSuggestions")==suggestions.first&&store.typingSuggestions==suggestions.second)
                    check(store.settings.contains("typingAutocorrect")==automatic.first&&store.typingAutocorrect==automatic.second)
                })
            }
            store.saveDictionary(listOf("Teh","ZyxTestword"));check(store.dictionary()==listOf("Teh","ZyxTestword"))
            store.typingSuggestions=false;store.typingAutocorrect=true
            editor();spell("adn");touch("Space");SystemClock.sleep(300);expect("adn ")
            check(nodes().none{it.contentDescription?.toString()?.startsWith("Choose typing suggestion ")==true})
            report.putBoolean("STORE-SUGGESTIONS-OFF-NATIVE-LITERAL",true)
            editor();spell("i");touch("Space");spell("want");touch("Space");SystemClock.sleep(300);expect("i want ")
            check(nodes().none{it.contentDescription?.toString()?.startsWith("Insert next word ")==true})
            report.putBoolean("STORE-SUGGESTIONS-OFF-NO-NEXT-WORD",true)
            store.typingSuggestions=true;store.typingAutocorrect=false
            editor();spell("adn");touch("Space");SystemClock.sleep(300);expect("adn ")
            touch("Choose typing suggestion and");expect("and ")
            report.putBoolean("STORE-AUTOCORRECT-OFF-MANUAL-CHOICE-WORKS",true)
            store.typingSuggestions=true;store.typingAutocorrect=true
            editor();spell("teh");touch("Space");SystemClock.sleep(300);expect("teh ")
            report.putBoolean("STORE-SAVED-SPELLING-PROTECTED-FROM-AUTO",true)
            editor();spell("zyxt");touch("Choose typing suggestion ZyxTestword");expect("ZyxTestword")
            report.putBoolean("STORE-SAVED-WORD-PREFIX-AND-DISPLAY",true)
            outcome=Activity.RESULT_OK
            report.putString("result","PASS app-owned storage/settings APIs to native synthetic editor only; settings UI taps/device/quality not qualified")
        }catch(error:Throwable){report.putString("failure",error.javaClass.simpleName+": "+error.message)}
        finally{
            try{restore?.invoke();if(restore!=null)report.putBoolean("STORE-EXACT-ORIGINAL-VALUES-RESTORED",true)}
            catch(error:Throwable){outcome=Activity.RESULT_CANCELED;report.putString("cleanup-failure",error.javaClass.simpleName)}
        }
        finish(outcome,report)
    }
}
