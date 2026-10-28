package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo as Node
import dev.lip.keyboard.*
import java.util.concurrent.atomic.AtomicReference

/** Owned emulator/synthetic editor, actual controls and settings file; no physical haptic claim. */
class KeyboardPreferencesRunner:Instrumentation(){
    private lateinit var ax:UiAutomation
    private val report=Bundle()
    private var generation=""
    private var stage="setup"
    private var landscape=false
    private var warmGeometry=false
    private var lastTouch="none"
    private var resumed:Activity?=null
    private val lifecycle=ArrayList<String>()
    private val callbacks=object:android.app.Application.ActivityLifecycleCallbacks {
        private fun event(name:String,a:Activity){lifecycle.add("${SystemClock.uptimeMillis()} $name ${a.javaClass.simpleName}@${System.identityHashCode(a)} finishing=${a.isFinishing} requested=${a.requestedOrientation}")}
        override fun onActivityCreated(a:Activity,s:Bundle?){event("create",a)}
        override fun onActivityStarted(a:Activity){event("start",a)}
        override fun onActivityResumed(a:Activity){resumed=a;event("resume",a)}
        override fun onActivityPaused(a:Activity){event("pause",a)}
        override fun onActivityStopped(a:Activity){event("stop",a)}
        override fun onActivitySaveInstanceState(a:Activity,s:Bundle){event("save",a)}
        override fun onActivityDestroyed(a:Activity){event("destroy",a)}
    }
    private fun nodes():List<Node>{
        val out=ArrayList<Node>()
        fun walk(n:Node?,depth:Int){if(n==null||depth>36||out.size>5000)return;out.add(n);for(i in 0 until n.childCount)walk(n.getChild(i),depth+1)}
        ax.windows.forEach{walk(it.root,0)};return out
    }
    private fun match(name:String)=nodes().filter{it.isVisibleToUser&&(it.contentDescription?.toString()==name||it.text?.toString()==name)}
    private fun find(name:String):Node{
        val until=SystemClock.uptimeMillis()+5000
        do{val found=match(name);if(found.size==1)return found.single();SystemClock.sleep(60)}while(SystemClock.uptimeMillis()<until)
        error("Missing unique visible control: $name")
    }
    private fun tap(name:String){
        ax.waitForIdle(200,3000);val r=Rect();val node=find(name);node.getBoundsInScreen(r);check(!r.isEmpty)
        lastTouch="$name bounds=$r window=${node.windowId} uptime=${SystemClock.uptimeMillis()}"
        val now=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
            val e=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,r.exactCenterX(),r.exactCenterY(),0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;check(ax.injectInputEvent(e,true));e.recycle()
        };SystemClock.sleep(100)
    }
    private fun reveal(name:String,backwards:Boolean=false){
        repeat(8){
            if(match(name).size==1)return
            val scroller=nodes().singleOrNull{it.isVisibleToUser&&it.className?.toString()=="android.widget.ScrollView"}?:error("Settings scroller absent")
            check(scroller.performAction(if(backwards)Node.ACTION_SCROLL_BACKWARD else Node.ACTION_SCROLL_FORWARD)){"Cannot reveal $name"};SystemClock.sleep(100)
        };error("Settings control not reached: $name")
    }
    private fun ui(action:()->Unit){val error=AtomicReference<Throwable>();runOnMainSync{try{action()}catch(t:Throwable){error.set(t)}};error.get()?.let{throw it}}
    private fun service()=checkNotNull(LipInputMethodService.debugInstance?.get())
    private fun view(name:String):View{
        var result:View?=null
        fun visit(v:View){if(v.contentDescription?.toString()==name&&v.isAttachedToWindow){check(result==null);result=v};if(v is ViewGroup)for(i in 0 until v.childCount)visit(v.getChildAt(i))}
        val keys=service().javaClass.getDeclaredField("keys").apply{isAccessible=true}.get(service()) as View
        visit(keys);return checkNotNull(result)
    }
    private fun text()=find("Synthetic ordinary editor").text?.toString().orEmpty()
    private fun waitText(value:String){val end=SystemClock.uptimeMillis()+5000;while(text()!=value&&SystemClock.uptimeMillis()<end)SystemClock.sleep(60);check(text()==value){"Unexpected owned editor text: ${text()} expected $value"}}
    private fun settings(){tap("Lip keyboard menu");tap("Keyboard preferences");find("Your keyboard.")}
    private fun back(){
        reveal("Back from keyboard preferences",true)
        ui{
            fun visit(v:View){if(v.contentDescription=="Back from keyboard preferences"){
                val p=IntArray(2);v.getLocationOnScreen(p);report.putString("diagnostic-back-actual-view", "${v.javaClass.simpleName}@${System.identityHashCode(v)} bounds=${Rect(p[0],p[1],p[0]+v.width,p[1]+v.height)} attached=${v.isAttachedToWindow} shown=${v.isShown}")
            };if(v is ViewGroup)for(i in 0 until v.childCount)visit(v.getChildAt(i))}
            resumed?.window?.decorView?.let{visit(it)}
        }
        tap("Back from keyboard preferences")
    }
    private fun typing(){back();tap("Synthetic ordinary editor");find("q");find("Lip synthetic generation $generation")}
    private fun glide(word:String){
        ax.waitForIdle(300,3000)
        val points=word.fold(mutableListOf<Char>()){chars,c->if(chars.lastOrNull()!=c)chars.add(c);chars}.map{bounds(it.toString())}
        val start=SystemClock.uptimeMillis()
        fun event(action:Int,x:Float,y:Float){val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x,y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(ax.injectInputEvent(e,true))}finally{e.recycle()}}
        event(MotionEvent.ACTION_DOWN,points.first().exactCenterX(),points.first().exactCenterY())
        try{for(i in 1 until points.size)for(step in 1..20){SystemClock.sleep(10);val f=step/20f;val a=points[i-1];val b=points[i];event(MotionEvent.ACTION_MOVE,a.exactCenterX()+(b.exactCenterX()-a.exactCenterX())*f,a.exactCenterY()+(b.exactCenterY()-a.exactCenterY())*f)}}
        finally{event(MotionEvent.ACTION_UP,points.last().exactCenterX(),points.last().exactCenterY())}
    }
    private fun waitGlideText(value:String,metric:String="diagnostic-resized-glide"){
        val start=SystemClock.uptimeMillis();var atFive:String?=null
        while(text()!=value&&SystemClock.uptimeMillis()-start<15000){
            if(atFive==null&&SystemClock.uptimeMillis()-start>=5000){
                atFive=text()
                val stacks=Thread.getAllStackTraces().filter{(_,frames)->frames.any{it.className.startsWith("dev.lip.keyboard.Swipe")}}
                report.putString("$metric-five-second-worker-stacks",stacks.entries.joinToString("\n"){(thread,frames)->thread.name+": "+frames.take(8).joinToString(" | ")})
            }
            SystemClock.sleep(100)
        }
        report.putLong("$metric-observed-ms",SystemClock.uptimeMillis()-start)
        if(atFive!=null)report.putBoolean("$metric-not-complete-at-five-seconds",true)
        check(text()==value){"Resized glide mismatch under existing15s semantic observer; actual=${text()}"}
    }
    private fun clearEditor(){
        check(find("Synthetic ordinary editor").performAction(Node.ACTION_SET_TEXT,Bundle().apply{putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"")}));tap("Synthetic ordinary editor");find("q");waitText("")
    }
    private fun bounds(name:String)=Rect().also{find(name).getBoundsInScreen(it)}
    private fun image(name:String){val bitmap=checkNotNull(ax.takeScreenshot());java.io.FileOutputStream(java.io.File(targetContext.getExternalFilesDir(null),"preferences-$name.png")).use{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()}
    override fun onCreate(args:Bundle?){
        super.onCreate(args)
        val variant=args?.getString("visualVariant")
        if(variant!=null&&variant !in listOf("portrait","landscape")){finish(Activity.RESULT_CANCELED,Bundle().apply{putString("failure","Unknown explicit settings visual variant")});return}
        val warm=args?.getString("warmGeometrySwipe")
        if(warm!=null&&warm !in listOf("true","false")){finish(Activity.RESULT_CANCELED,Bundle().apply{putString("failure","Unknown explicit warm-geometry flag")});return}
        warmGeometry=warm=="true";landscape=variant=="landscape";start()
    }
    override fun onStart(){
        var code=Activity.RESULT_CANCELED
        val prefs=targetContext.getSharedPreferences("keyboard_presentation_v1",0);val before=prefs.all.toMap()
        try{
            check(android.os.Build.HARDWARE=="ranchu"&&targetContext.packageName=="dev.lip.android")
            ui{(targetContext.applicationContext as android.app.Application).registerActivityLifecycleCallbacks(callbacks)}
            ax=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ax.serviceInfo=ax.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            generation=java.util.UUID.randomUUID().toString()
            targetContext.startActivity(Intent().setClassName("dev.lip.android.test","dev.lip.ImeFixtureActivity").putExtra("fixtureGeneration",generation).putExtra("landscapeFixture",landscape).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val marker=find("Lip synthetic generation $generation");val editor=find("Synthetic ordinary editor");check(marker.windowId==editor.windowId)
            check(editor.performAction(Node.ACTION_SET_TEXT,Bundle().apply{putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"SAFE")}));tap("Synthetic ordinary editor");find("q")
            ui{val config=service().resources.configuration;check((config.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE)==landscape);report.putString("actual-configuration",config.toString())}
            stage="settings-entry";settings()
            report.putBoolean("KB05-ACTUAL-SETTINGS-ENTRY",true)
            tap("Layout: Left hand");reveal("Key height: Compact");tap("Key height: Compact");reveal("Material: Dark matte");tap("Material: Dark matte");reveal("Key haptics")
            if(!find("Key haptics").isChecked)tap("Key haptics")
            image("settings")
            check(KeyboardPreferencesStore(targetContext).load()==KeyboardPreferences(KeyboardHand.LEFT,KeyboardHeight.COMPACT,KeyboardTheme.MATTE,true))
            report.putBoolean("KB05-UI-SAVES-EXACT-CONFIG",true)
            typing();stage="left"
            val leftQ=bounds("q");val leftP=bounds("p");val rail=bounds("Use full-width keyboard")
            check(leftP.right<=rail.left&&leftQ.left<rail.left)
            ui{check(GlassMaterial.matte&&GlassMaterial.haptics);check(view("q").isHapticFeedbackEnabled)}
            tap("q");waitText("SAFEq");image("left-compact-matte")
            report.putBoolean("KB05-LEFT-ACTUAL-TAP-AND-BOUNDS",true)
            lateinit var stale:View
            ui{stale=view("q")}
            tap("Move keyboard right");stage="right"
            val rightQ=bounds("q");val rightP=bounds("p");check(rightQ.left>leftQ.left&&rightP.right>leftP.right&&bounds("Use full-width keyboard").right<=rightQ.left)
            ui{check(!stale.isAttachedToWindow);check(!stale.performClick())};waitText("SAFEq")
            tap("p");waitText("SAFEqp");image("right-compact-matte")
            stage="resized-swipe";clearEditor();glide("hello");waitGlideText("hello ")
            report.putBoolean("KB05-RESIZED-REAL-NEURAL-SWIPE",true)
            stage="resized-surfaces";tap("Switch letters and symbols");tap("@");waitText("hello @");tap("Show number pad");tap("1");waitText("hello @1");tap("Switch letters and symbols")
            tap("Show emoji");find("Search offline emoji");tap("Return from emoji to letters")
            tap("Show local GIF catalog");find("Return from GIFs to letters");tap("Return from GIFs to letters")
            report.putBoolean("KB05-RESIZED-SYMBOL-NUMERIC-EMOJI-GIF",true)
            report.putBoolean("KB05-RIGHT-RELOCATES-AND-STALE-REFUSED",true)
            tap("Use full-width keyboard");val compact=bounds("q").height();check(match("Use full-width keyboard").isEmpty());check(bounds("q").left<=leftQ.left)
            report.putBoolean("KB05-RESTORE-FULL-WIDTH",true)
            if(warmGeometry){
                stage="actual-warm-geometry"
                clearEditor();glide("hello");waitGlideText("hello ","diagnostic-warm-full-width-glide")
                tap("Switch letters and symbols");tap("@");waitText("hello @");tap("Show number pad");tap("1");waitText("hello @1");tap("Switch letters and symbols")
                report.putBoolean("KB05-WARM-GEOMETRY-REAL-SWIPE",true)
            }
            stage="simulated-failed-commit"
            val shared=KeyboardPreferencesStore::class.java.getDeclaredField("shared").apply{isAccessible=true}
            var originalRepo:Any?=null
            val confirmed=KeyboardPreferencesStore(targetContext).load()
            var failedCache=confirmed.encode()
            val refused=KeyboardPreferenceRepository({failedCache}){next->failedCache=next.encode();false}
            try{
                ui{originalRepo=shared.get(null);shared.set(null,refused)}
                settings();tap("Layout: Left hand")
                check(find("Layout: Full width").isChecked)
                check(KeyboardPreferencesStore(targetContext).load()==confirmed)
                check(KeyboardPreferences.decode(failedCache).hand==KeyboardHand.LEFT)
                typing()
                check(match("Use full-width keyboard").isEmpty())
                check(KeyboardPreferencesStore(targetContext).load()==confirmed)
                report.putBoolean("KB05-SIMULATED-CACHE-MUTATING-FAILURE-RECONSTRUCTION-APPLY",true)
            }finally{ui{shared.set(null,originalRepo)}}
            stage="tall-settings";settings();reveal("Key height: Tall");tap("Key height: Tall");reveal("Material: Dark glass");tap("Material: Dark glass");reveal("Key haptics");if(find("Key haptics").isChecked)tap("Key haptics");typing()
            val tall=bounds("q").height();check(tall>compact)
            ui{check(!GlassMaterial.matte&&!GlassMaterial.haptics);check(!view("q").isHapticFeedbackEnabled)}
            ui{
                for(name in listOf("Space","Switch letters and symbols","Emoji keyboard",".","Send in the current editor")){
                    val key=view(name);val xy=IntArray(2);key.getLocationOnScreen(xy);val expected=Rect(xy[0],xy[1],xy[0]+key.width,xy[1]+key.height);val visible=Rect()
                    val shown=key.getGlobalVisibleRect(visible);val origin=IntArray(2);key.rootView.getLocationOnScreen(origin);visible.offset(origin[0],origin[1])
                    check(shown&&visible==expected){"Tall bottom key clipped: $name expected=$expected visible=$visible"}
                }
            }
            tap("Space");waitText("hello @1 ");tap("Switch letters and symbols");tap("@");waitText("hello @1 @");tap("Switch letters and symbols")
            tap("Send in the current editor");waitText("hello @1 @")
            report.putBoolean("KB05-TALL-BOTTOM-ROW-FULL-BOUNDS-AND-REAL-TAPS",true)
            image("full-tall-glass");report.putBoolean("KB05-HEIGHT-MATERIAL-HAPTIC-WIRING",true)
            settings();reveal("Key height: Tall");check(find("Key height: Tall").isChecked);reveal("Material: Dark glass");check(find("Material: Dark glass").isChecked);report.putBoolean("KB05-REOPEN-PERSISTS-SELECTION",true)
            code=Activity.RESULT_OK
        }catch(t:Throwable){report.putString("failure",stage+": "+android.util.Log.getStackTraceString(t));report.putString("diagnostic-last-touch",lastTouch)
            runCatching{ui{report.putString("diagnostic-activity-events",lifecycle.joinToString("\n"));report.putString("diagnostic-last-resumed",resumed?.let{"${it.javaClass.simpleName}@${System.identityHashCode(it)} finishing=${it.isFinishing} config=${it.resources.configuration}"})}};runCatching{image("failure")};runCatching{ui{
            val lines=ArrayList<String>();var parent:Any?=view("Space")
            while(parent is View){val v=parent as View;val r=Rect();v.getGlobalVisibleRect(r);lines.add("${v.javaClass.name} size=${v.width}x${v.height} measured=${v.measuredWidth}x${v.measuredHeight} visible=$r padding=${v.paddingTop},${v.paddingBottom} lp=${v.layoutParams?.width},${v.layoutParams?.height}");parent=v.parent}
            report.putString("diagnostic-view-parent-chain",lines.joinToString("\n"))
        }}}
        finally{
            try{
                ui{(targetContext.applicationContext as android.app.Application).unregisterActivityLifecycleCallbacks(callbacks)}
                ui{val edit=prefs.edit().clear();before.forEach{(key,value)->when(value){is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value);is Set<*>->@Suppress("UNCHECKED_CAST") edit.putStringSet(key,value as Set<String>);else->error("Unexpected saved UI preference type")}};check(edit.commit());check(prefs.all==before)
                    runCatching{KeyboardPreferencesStore::class.java.getDeclaredField("shared").apply{isAccessible=true}.set(null,null)}
                }
                report.putBoolean("KB05-ORIGINAL-SETTINGS-RESTORED",true)
            }catch(t:Throwable){code=Activity.RESULT_CANCELED;report.putString("cleanup-failure",android.util.Log.getStackTraceString(t))}
        }
        finish(code,report)
    }
}
