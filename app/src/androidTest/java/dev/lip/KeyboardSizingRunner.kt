package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo as Node
import dev.lip.keyboard.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Manual runner: isolated UID + owned qemu ONLY; real settings/IME, synthetic editor. */
class KeyboardSizingRunner:Instrumentation(){
    private lateinit var ax:UiAutomation
    private val screenshots=org.json.JSONArray()
    private val result=JSONObject().put("harnessVersion",11).put("screenshots",screenshots).put("passed",false).put("scope","isolated native settings/IME sizing: actual-view physical IME coordinates, native AX settings; synthetic editor, not TalkBack/phone/full9.2")
    private var stage="guard"
    private var compactLandscape=false
    override fun onCreate(args:Bundle?){
        super.onCreate(args)
        val variant=args?.getString("variant")?:"portrait"
        if(variant !in listOf("portrait","compact-landscape-font2")){finish(Activity.RESULT_CANCELED,Bundle());return}
        compactLandscape=variant!="portrait";start()
    }
    private fun nodes():List<Node>{
        val out=ArrayList<Node>()
        fun visit(n:Node?,depth:Int){if(n==null||depth>32||out.size>4000)return;out.add(n);for(i in 0 until n.childCount)visit(n.getChild(i),depth+1)}
        ax.windows.forEach{visit(it.root,0)};return out
    }
    private fun matching(label:String)=nodes().filter{it.packageName?.toString() in setOf(targetContext.packageName,context.packageName)&&it.isVisibleToUser&&(it.contentDescription?.toString()==label||it.text?.toString()==label)}
    private fun find(label:String):Node{
        val until=SystemClock.uptimeMillis()+8000
        do{val items=matching(label);if(items.size==1)return items.single();SystemClock.sleep(80)}while(SystemClock.uptimeMillis()<until)
        error("Expected unique synthetic control: $label")
    }
    private fun tap(label:String){
        ax.waitForIdle(200,3000)
        check(android.provider.Settings.Secure.getString(targetContext.contentResolver,"default_input_method")==targetContext.packageName+"/dev.lip.keyboard.LipInputMethodService") { "Owned keyboard routing lost" }
        val node=find(label);check(node.isEnabled)
        val actual=ui{
            var bounds:Rect?=null
            fun visit(v:View){
                if(v.contentDescription?.toString()==label&&v.isAttachedToWindow&&v.isShown&&v.windowVisibility==View.VISIBLE){
                    val xy=IntArray(2);v.getLocationOnScreen(xy);check(bounds==null)
                    bounds=Rect(xy[0],xy[1],xy[0]+v.width,xy[1]+v.height)
                }
                if(v is ViewGroup)for(i in 0 until v.childCount)visit(v.getChildAt(i))
            }
            LipInputMethodService.debugInstance?.get()?.window?.window?.decorView?.let{visit(it)}
            bounds
        }
        if(actual==null&&label!="Synthetic ordinary editor"&&node.isClickable){
            check(node.performAction(Node.ACTION_CLICK)) { "Native activity accessibility click refused: $label" }
        }else{
            val axBounds=Rect();node.getBoundsInScreen(axBounds)
            val rect=actual?:axBounds;check(!rect.isEmpty)
            val diagnostics=result.optJSONArray("tapCoordinates")?:org.json.JSONArray().also{result.put("tapCoordinates",it)}
            diagnostics.put(JSONObject().put("label",label).put("native",rect.toShortString()).put("ax",axBounds.toShortString()))
            val down=SystemClock.uptimeMillis()
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){
                val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,rect.exactCenterX(),rect.exactCenterY(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try{check(ax.injectInputEvent(event,true))}finally{event.recycle()}
            }
        }
        ax.waitForIdle(100,1500)
    }
    private fun reveal(label:String,back:Boolean=false){
        repeat(24){
            if(matching(label).size==1)return
            val scroll=nodes().singleOrNull{it.isVisibleToUser&&it.className?.toString()=="android.widget.ScrollView"}?:error("No settings scroll")
            check(scroll.performAction(if(back)Node.ACTION_SCROLL_BACKWARD else Node.ACTION_SCROLL_FORWARD));SystemClock.sleep(80)
        };error("Control not reachable: $label")
    }
    private fun <T> ui(action:()->T):T{
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return action()
        val value=AtomicReference<T>();val error=AtomicReference<Throwable>()
        runOnMainSync{try{value.set(action())}catch(t:Throwable){error.set(t)}};error.get()?.let{throw it};return value.get()
    }
    private fun writeNewDurable(file:File,bytes:ByteArray){
        check(file.createNewFile()) { "Owned recovery file already exists" }
        java.io.FileOutputStream(file).use{out->out.write(bytes);out.fd.sync()}
        val directory=android.system.Os.open(file.parentFile!!.absolutePath,android.system.OsConstants.O_RDONLY,0)
        try{check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(directory).st_mode));android.system.Os.fsync(directory)}finally{android.system.Os.close(directory)}
    }
    private fun service()=checkNotNull(LipInputMethodService.debugInstance?.get())
    private fun key(label:String):GlassKeyButton=ui{
        var found:GlassKeyButton?=null
        fun visit(v:View){if(v is GlassKeyButton&&v.contentDescription?.toString()==label&&v.isShown){check(found==null);found=v};if(v is ViewGroup)for(i in 0 until v.childCount)visit(v.getChildAt(i))}
        val root=service().javaClass.getDeclaredField("keys").apply{isAccessible=true}.get(service()) as View
        visit(root);checkNotNull(found)
    }
    private fun measure(label:String):JSONObject=ui{
        val v=key(label);val xy=IntArray(2);v.getLocationOnScreen(xy);val visible=Rect();v.getGlobalVisibleRect(visible)
        JSONObject().put("fontPixels",v.textSize.toDouble()).put("width",v.width).put("height",v.height)
            .put("x",xy[0]).put("y",xy[1]).put("scale",v.labelScale.name)
            .put("visibleHeight",visible.height()).put("layoutHeight",v.layout?.height?:-1).put("contentHeight",v.height-v.compoundPaddingTop-v.compoundPaddingBottom)
            .put("lineWidth",v.layout?.getLineWidth(0)?.toDouble()?:-1.0).put("contentWidth",v.width-v.compoundPaddingLeft-v.compoundPaddingRight)
    }
    private fun capture(name:String){
        val bitmap=checkNotNull(ax.takeScreenshot())
        FileOutputStreamCompat.write(File(targetContext.noBackupFilesDir,"sizing-$name.png"),bitmap)
        screenshots.put("sizing-$name.png")
    }
    private fun settings(){tap("Lip keyboard menu");tap("Keyboard preferences");find("Your keyboard.")}
    private fun back(){reveal("Back from keyboard preferences",true);tap("Back from keyboard preferences");tap("Synthetic ordinary editor");find("q")}
    override fun onStart(){
        var code=Activity.RESULT_CANCELED
        var store:KeyboardPreferencesStore?=null
        var original:KeyboardPreferences?=null
        var before:Map<String,*>?=null
        var admitted=false
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android.sizingfixture")
            check(context.packageName=="dev.lip.android.sizingfixture.test")
            ax=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);val serviceInfo=ax.serviceInfo
            serviceInfo.flags=serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ax.serviceInfo=serviceInfo
            store=KeyboardPreferencesStore(targetContext);original=store.load()
            val snapshot=targetContext.getSharedPreferences("keyboard_presentation_v1",0).all.toMap()
            val journal=File(targetContext.noBackupFilesDir,"sizing-recovery.json")
            check(!journal.exists()&&!File(targetContext.noBackupFilesDir,"sizing-restored.json").exists()){ "Previous owned sizing rollback must be reconciled first" }
            before=snapshot
            val originalBytes=JSONObject().put("owner",java.util.UUID.randomUUID().toString()).put("preferences",JSONObject(snapshot)).put("state","original-before-native-controls").toString().toByteArray()
            writeNewDurable(journal,originalBytes);admitted=true
            result.put("originalJournalSHA256",java.security.MessageDigest.getInstance("SHA-256").digest(originalBytes).joinToString(""){"%02x".format(it.toInt() and 255)})
            // Starting instrumentation can restart the target IME process. Select only
            // after this guarded fixture process is live; never operate fallback keys.
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ax.executeShellCommand("ime set dev.lip.android.sizingfixture/dev.lip.keyboard.LipInputMethodService")).use{it.readBytes()}
            result.put("selectedIME",android.provider.Settings.Secure.getString(targetContext.contentResolver,"default_input_method"))
            check(store.save(KeyboardPreferences(height=if(compactLandscape)KeyboardHeight.COMPACT else KeyboardHeight.STANDARD)))
            val token=java.util.UUID.randomUUID().toString()
            targetContext.startActivity(Intent().setClassName(context.packageName,"dev.lip.KeyboardSizingEditorActivity")
                .putExtra("fixtureGeneration",token).putExtra("darkFixture",true).putExtra("landscapeFixture",compactLandscape)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            find("Lip synthetic generation $token");tap("Synthetic ordinary editor")
            val shownStart=SystemClock.uptimeMillis();val shownLimit=shownStart+20000
            while(SystemClock.uptimeMillis()<shownLimit&&!ui{LipInputMethodService.debugInstance?.get()?.let{it.isInputViewShown&&it.currentInputEditorInfo?.packageName==context.packageName}==true})SystemClock.sleep(100)
            check(ui{LipInputMethodService.debugInstance?.get()?.isInputViewShown==true}) { "Owned IME did not reach shown precondition" }
            result.put("ownedImeShownWaitMs",SystemClock.uptimeMillis()-shownStart)
            find("q")
            ui{check(service().packageName==targetContext.packageName);check(service().currentInputEditorInfo?.packageName==context.packageName)}
            result.put("actualKeyboardPackage",targetContext.packageName).put("actualEditorPackage",context.packageName)
            if(compactLandscape)ui{check(service().resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE);check(service().resources.configuration.fontScale>=1.99f)}
            result.put("variant",if(compactLandscape)"compact-landscape-font2" else "portrait")
            stage="default-key";val base=measure("q");result.put("defaultQ",base);capture("system-keys")
            stage="settings-large";settings();reveal("Key label size: Extra large");tap("Key label size: Extra large")
            reveal("Emoji preview size: Extra large");tap("Emoji preview size: Extra large");capture("settings-large")
            check(store.load().keyLabels==KeyboardScale.EXTRA_LARGE&&store.load().emojiPreview==KeyboardScale.EXTRA_LARGE)
            back();stage="large-key";val large=measure("q");result.put("largeQ",large)
            check(if(compactLandscape)large.getDouble("fontPixels")>=base.getDouble("fontPixels") else large.getDouble("fontPixels")>base.getDouble("fontPixels"))
            check(large.getInt("layoutHeight") in 1..large.getInt("contentHeight")){"Requested label clipped vertically"}
            check(large.getDouble("lineWidth") in 0.0..large.getInt("contentWidth").toDouble()){ "Requested label clipped horizontally" }
            for(k in listOf("width","height","x","y"))check(base.getInt(k)==large.getInt(k)){"Sizing moved key geometry: $k"}
            tap("q");check(find("Synthetic ordinary editor").text?.toString()=="q")
            capture("large-keys")
            tap("Emoji keyboard");find("Offline emoji results")
            val largeEmoji=waitEmoji();result.put("largeEmoji",largeEmoji);capture("large-emoji")
            check(largeEmoji.getInt("visibleHeight")>=largeEmoji.getInt("height")){"Largest emoji cell clipped by panel viewport"}
            stage="small-settings";tap("Return from emoji to letters");settings()
            reveal("Key label size: Small");tap("Key label size: Small");reveal("Emoji preview size: Small");tap("Emoji preview size: Small")
            back();val small=measure("q");result.put("smallQ",small);check(if(compactLandscape)small.getDouble("fontPixels")<=base.getDouble("fontPixels") else small.getDouble("fontPixels")<base.getDouble("fontPixels"))
            for(k in listOf("width","height","x","y"))check(base.getInt(k)==small.getInt(k))
            tap("Emoji keyboard");find("Offline emoji results");val smallEmoji=waitEmoji();result.put("smallEmoji",smallEmoji)
            check(largeEmoji.getDouble("fontPixels")>smallEmoji.getDouble("fontPixels"))
            check(largeEmoji.getInt("height")>smallEmoji.getInt("height"));capture("small-emoji")
            stage="persistence";tap("Return from emoji to letters");settings()
            reveal("Key label size: Small");check(find("Key label size: Small").isChecked)
            reveal("Emoji preview size: Small");check(find("Emoji preview size: Small").isChecked)
            result.put("actualSettingsPersistence",true).put("actualQTap",true).put("geometryUnchanged",true)
            code=Activity.RESULT_OK
        }catch(t:Throwable){result.put("failure",stage+": "+android.util.Log.getStackTraceString(t));if(::ax.isInitialized)runCatching{capture("failure")}}
        finally{
            if(admitted&&store!=null&&original!=null&&before!=null)try{
                check(store.save(original))
                val prefs=targetContext.getSharedPreferences("keyboard_presentation_v1",0);val edit=prefs.edit()
                for(key in listOf("version","hand","height","theme","haptics","keyLabels","emojiPreview")){
                    val v=before[key]
                    when(v){null->edit.remove(key);is Int->edit.putInt(key,v);is String->edit.putString(key,v);is Boolean->edit.putBoolean(key,v);else->error("Unknown fixture pref type")}
                }
                check(edit.commit());check(prefs.all==before)
                val journal=File(targetContext.noBackupFilesDir,"sizing-recovery.json")
                val digest=java.security.MessageDigest.getInstance("SHA-256").digest(journal.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
                check(digest==result.getString("originalJournalSHA256")) { "Original recovery snapshot changed" }
                writeNewDurable(File(targetContext.noBackupFilesDir,"sizing-restored.json"),
                    JSONObject().put("state","restored-exact-original").put("originalJournalSHA256",digest).toString().toByteArray())
                result.put("fixturePreferencesRestored",true)
            }catch(t:Throwable){code=Activity.RESULT_CANCELED;result.put("rollbackFailure",android.util.Log.getStackTraceString(t))}
        }
        result.put("passed",code==Activity.RESULT_OK)
        File(targetContext.noBackupFilesDir,"sizing-result.json").writeText(result.toString(2))
        finish(code,Bundle().apply{putString("sizingResult",result.toString())})
    }
    private fun waitEmoji():JSONObject{
        val until=SystemClock.uptimeMillis()+8000
        do{
            val item=nodes().firstOrNull{it.packageName?.toString()==targetContext.packageName&&it.isVisibleToUser&&it.contentDescription?.toString()?.startsWith("Insert emoji ")==true}
            if(item!=null)return measure(item.contentDescription.toString())
            SystemClock.sleep(100)
        }while(SystemClock.uptimeMillis()<until)
        error("No actual emoji tile")
    }
    private object FileOutputStreamCompat {
        fun write(file:File,bitmap:android.graphics.Bitmap){java.io.FileOutputStream(file).use{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()}
    }
}
