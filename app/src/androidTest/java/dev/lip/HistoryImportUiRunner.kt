package dev.lip

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.widget.*
import dev.lip.testing.HistoryImportDocuments
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Same production MainActivity, isolated DEBUG app ID and separate provider UID.
 * Real system OPEN UI/callback only, no state injection or shell grants. */
class HistoryImportUiRunner:Instrumentation(){
    private var restoreOnly=false
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);restoreOnly=arguments?.getString("restore_only")=="true";start()}
    private lateinit var ax:UiAutomation
    private val latest=AtomicReference<MainActivity?>()
    private val resumed=AtomicReference<MainActivity?>()
    private val actors=java.util.concurrent.CopyOnWriteArrayList<MainActivity>()
    private val events=java.util.concurrent.CopyOnWriteArrayList<String>()
    private var pickerOutstanding=false
    private fun main(block:()->Unit){if(Looper.myLooper()==Looper.getMainLooper()){block();return};val failure=AtomicReference<Throwable?>();runOnMainSync{try{block()}catch(e:Throwable){failure.set(e)}};failure.get()?.let{throw it}}
    private fun waitFor(label:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+8000;while(!condition()){check(SystemClock.uptimeMillis()<end){label};SystemClock.sleep(50)}}
    private fun app()=checkNotNull(latest.get()).also{check(!it.isDestroyed)}
    private fun field(name:String):Any?{var value:Any?=null;main{value=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(app())};return value}
    private fun views(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun appButton(text:String){
        main{val button=views(app().window.decorView).filterIsInstance<Button>().single{it.text.toString()==text};button.requestRectangleOnScreen(android.graphics.Rect(0,0,button.width,button.height),true)}
        waitFor("Actual app control not visible/focused") {var okay=false;main{val button=views(app().window.decorView).filterIsInstance<Button>().single{it.text.toString()==text};val rect=android.graphics.Rect();okay=resumed.get()===app()&&app().hasWindowFocus()&&button.isShown&&button.isEnabled&&button.getGlobalVisibleRect(rect)&&rect.height()==button.height};okay}
        main{views(app().window.decorView).filterIsInstance<Button>().single{it.text.toString()==text}.performClick()};waitForIdleSync()
    }
    private fun dialog()=field("historyImportDialog") as? AlertDialog
    private fun dialogButton(which:Int){
        waitFor("Actual dialog button not visible/focused"){var okay=false;main{val d=dialog();val b=d?.getButton(which);okay=d?.window?.decorView?.hasWindowFocus()==true&&b?.isShown==true&&b.isEnabled};okay}
        main{checkNotNull(dialog()).getButton(which).performClick()};waitForIdleSync()
    }
    private fun drain(){val latch=CountDownLatch(1);Work.io.execute{latch.countDown()};check(latch.await(8,java.util.concurrent.TimeUnit.SECONDS));waitForIdleSync()}
    private fun picker():Boolean{val windows=ax.windows;try{return windows.any{val r=it.root;try{r?.packageName?.toString() in setOf("com.google.android.documentsui","com.android.documentsui")&&(it.isActive||it.isFocused)}finally{r?.recycle()}}}finally{windows.forEach{it.recycle()}}}
    private fun systemClick(text:String,description:Boolean=false,document:Boolean=false):Boolean {
        val windows=ax.windows
        try{for(w in windows){if(!w.isActive&&!w.isFocused)continue;val root=w.root?:continue
            try{val pkg=root.packageName?.toString();if(pkg !in setOf("com.google.android.documentsui","com.android.documentsui"))continue
                val matches=ArrayList<Node>();var count=0
                fun find(n:Node,depth:Int) {
                    if(depth>24||count++>500)return
                    val value=if(description)n.contentDescription?.toString() else n.text?.toString()
                    if(n.isVisibleToUser&&value==text)matches.add(Node.obtain(n))
                    for(i in 0 until n.childCount){val child=n.getChild(i)?:continue;try{find(child,depth+1)}finally{child.recycle()}}
                }
                find(root,0)
                try{for(label in matches){var node=Node.obtain(label)
                    try{var hops=0
                        while(hops<16&&(if(document)node.viewIdResourceName!="$pkg:id/item_root" else !node.isClickable)){val parent=node.parent?:break;node.recycle();node=parent;hops++}
                        val eligible=node.packageName?.toString()==pkg&&node.isClickable&&(!document||node.viewIdResourceName=="$pkg:id/item_root")
                        if(events.size<120)events.add("fixture_control $text matches=${matches.size} hops=$hops eligible=$eligible")
                        if(!eligible)continue
                        check(node.performAction(Node.ACTION_CLICK)){"Exact fixture control click refused"};return true
                    }finally{node.recycle()}
                }}finally{matches.forEach{it.recycle()}}
            }finally{root.recycle()}
        }}finally{windows.forEach{it.recycle()}}
        return false
    }
    private fun chooseFile(name:String){
        waitFor("System OPEN picker absent"){picker()}
        waitFor("Picker navigation menu absent"){systemClick("Show roots",description=true)||systemClick("Show navigation menu",description=true)}
        waitFor("Synthetic source provider root absent"){systemClick(HistoryImportDocuments.TITLE)}
        waitFor("Exact synthetic source absent"){systemClick(name,document=true)||systemClick(name.removeSuffix(".json"),document=true)}
        waitFor("Picker did not return"){!picker()};waitForIdleSync();settledPickerReturn()
    }
    private fun openPicker(){appButton("Import transcript JSON…");waitFor("Import warning absent"){dialog()!=null};dialogButton(AlertDialog.BUTTON_POSITIVE);waitFor("Actual OPEN not launched"){picker()};pickerOutstanding=true}
    private fun preview(name:String="valid-transcript.json") {openPicker();chooseFile(name);waitFor("Actual import preview absent"){dialog()!=null};main{check(dialog()?.findViewById<TextView>(android.R.id.message)?.text.toString().contains("ui-fixture-20261027-v1"))}}
    private fun awaitingPicker():Boolean {
        val operation=field("historyImportOperation")?:return false
        var value=false;main{value=operation.javaClass.getDeclaredField("awaitingPicker").apply{isAccessible=true}.getBoolean(operation)}
        return value
    }
    private fun cancelOwnedPicker(){
        // Drawer close is not picker cancellation. Demand the real result callback
        // plus resumed caller; a transient missing AX window cannot retire a journal.
        repeat(3){
            if(pickerOutstanding&&picker()){sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();ax.waitForIdle(200,2000)}
        }
        settledPickerReturn()
    }
    private fun settledPickerReturn(){
        var since=0L
        waitFor("Owned picker callback/focus did not settle") {
            var focus=false;main{focus=latest.get()?.hasWindowFocus()==true||dialog()?.window?.decorView?.hasWindowFocus()==true}
            val okay=!picker()&&!awaitingPicker()&&resumed.get()===latest.get()&&focus
            if(!okay)since=0L else if(since==0L)since=SystemClock.uptimeMillis()
            okay&&SystemClock.uptimeMillis()-since>=500
        }
        pickerOutstanding=false
    }
    private fun syncDir(){val fd=Os.open(targetContext.noBackupFilesDir.path,OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,0);try{check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode));Os.fsync(fd)}finally{Os.close(fd)}}
    override fun onStart(){
        val report=JSONObject().put("passed",false).put("scope","same production import screen in isolated debug application ID, real system picker/separate-UID synthetic source; no Downloads/cloud/phone/Mac proof")
        val journal=File(targetContext.noBackupFilesDir,"import-ui-journal.json")
        val expected=HistoryImport.decode(HistoryImportDocuments.VALID.toByteArray()).record
        var owned=false;var cleanup=true;var store:AppStore?=null;var originalSettings:Map<String,*>?=null
        val clipboard=targetContext.getSystemService(ClipboardManager::class.java);val changes=java.util.concurrent.atomic.AtomicInteger();val listener=ClipboardManager.OnPrimaryClipChangedListener{changes.incrementAndGet()}
        val callbacks=object:Application.ActivityLifecycleCallbacks{
            override fun onActivityCreated(a:Activity,b:Bundle?){if(a is MainActivity){latest.set(a);actors.add(a)}}
            override fun onActivityResumed(a:Activity){if(a is MainActivity){latest.set(a);resumed.set(a)}}
            override fun onActivityStarted(a:Activity){};override fun onActivityPaused(a:Activity){if(a is MainActivity)resumed.compareAndSet(a,null)};override fun onActivityStopped(a:Activity){};override fun onActivitySaveInstanceState(a:Activity,b:Bundle){};override fun onActivityDestroyed(a:Activity){}
        }
        val application=targetContext.applicationContext as Application
        try{
            check(targetContext.packageName=="dev.lip.android.historyfixture"&&context.packageName=="dev.lip.android.historyfixture.test")
            check(Build.HARDWARE in listOf("ranchu","goldfish")&&Build.PRODUCT.contains("sdk"))
            check(!targetContext.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            ax=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);ax.serviceInfo=ax.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
            if(restoreOnly){
                check(!picker()) { "Picker outstanding; no blind recovery" }
                val recoverySecure=dev.lip.auth.SecureStore(targetContext)
                val usage=recoverySecure.historyUsage()
                check(!usage.legacy&&usage.entries in 0..1&&usage.bytes in 0..1048576) { "Unexpected recovery storage; preserve journal" }
                check(targetContext.getSharedPreferences("preferences",Context.MODE_PRIVATE).all.isEmpty()) { "Recovery preferences differ from known empty baseline" }
                check(!java.nio.file.Files.isSymbolicLink(journal.toPath()))
                val actual=journal.inputStream().use{it.readNBytes(4097)};check(actual.size<=4096)
                val planned=JSONObject().put("version",1).put("id",expected.id).put("sourceSha256",java.security.MessageDigest.getInstance("SHA-256").digest(HistoryImportDocuments.VALID.toByteArray()).joinToString(""){"%02x".format(it)}).toString().toByteArray()
                check(actual.contentEquals(planned)) { "Recovery marker differs; no retirement" }
                val payload=AppStore.encodeHistory(listOf(HistoryImport.local(expected)))
                val hash=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()))
                val exactName="history-"+java.lang.Long.toHexString(expected.createdAtUnixMs xor Long.MIN_VALUE).padStart(16,'0')+"-"+hash
                val names=recoverySecure.names("history-")
                check(names.isEmpty()||names==listOf(exactName)) { "Unknown recovery record; no content read" }
                if(names.isNotEmpty()){check(recoverySecure.read(exactName)==payload) { "Recovery payload differs; preserve" };recoverySecure.delete(exactName)}
                check(recoverySecure.historyUsage()==HistoryUsage(0,0,false))
                check(journal.delete());syncDir();report.put("passed",true).put("recovery_only",true);events.add("exact_prepared_empty_fixture_journal_recovered")
            }else{
            check(!journal.exists()){"Prior fixture journal unresolved; no mutation"}
            check(dev.lip.auth.SecureStore(targetContext).historyUsage()==HistoryUsage(0,0,false)){"Fixture history is not empty; no private content retrieval"}
            val s=AppStore(targetContext);store=s;originalSettings=s.settings.all.toMap();check(!s.settings.contains("history.consent.v1"))
            check(s.historyPage().entries.isEmpty());check(!Dictation.get(targetContext).busy)
            val source=DocumentsContract.buildDocumentUri("${context.packageName}.historyfixtures","valid-transcript.json")
            check(targetContext.checkUriPermission(source,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED){"Source already granted; fresh grant witness unavailable"}
            check(journal.createNewFile());owned=true
            java.io.FileOutputStream(journal).use{it.write(JSONObject().put("version",1).put("id",expected.id).put("sourceSha256",java.security.MessageDigest.getInstance("SHA-256").digest(HistoryImportDocuments.VALID.toByteArray()).joinToString(""){"%02x".format(it)}).toString().toByteArray());it.fd.sync()};syncDir()
            application.registerActivityLifecycleCallbacks(callbacks);clipboard.addPrimaryClipChangedListener(listener)
            // startActivitySync can wait forever when AM delivers to a retained task.
            // Use an actual new task and bounded lifecycle observation instead.
            main{targetContext.startActivity(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))}
            waitFor("Main activity was not created"){latest.get()!=null};waitFor("Created Main not resumed/focused"){var ready=false;main{ready=latest.get()!=null&&resumed.get()===latest.get()&&latest.get()?.hasWindowFocus()==true};ready};waitForIdleSync();appButton("History");drain()
            appButton("Import transcript JSON…");waitFor("Warning absent"){dialog()!=null};dialogButton(AlertDialog.BUTTON_NEGATIVE);check(field("historyImportOperation")==null&&!picker());check(s.historyPage().entries.isEmpty());events.add("warning_cancel_no_record")
            openPicker();sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitFor("OPEN cancel not drained"){!picker()&&field("historyImportOperation")==null};check(s.historyPage().entries.isEmpty());settledPickerReturn();events.add("real_picker_cancel_no_record")
            preview();check(targetContext.checkUriPermission(source,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED);dialogButton(AlertDialog.BUTTON_NEGATIVE);drain();check(s.historyPage().entries.isEmpty());events.add("real_preview_cancel_and_actual_uri_grant")
            // Run BEFORE the first admission: a stale NEW import cannot hide as an identical no-op.
            openPicker();val before=app();main{before.recreate()};waitFor("Main not recreated"){latest.get()!==before&&before.isDestroyed};chooseFile("valid-transcript.json");drain();check(field("historyImportOperation")==null&&dialog()==null);check(s.historyPage().entries.isEmpty());events.add("recreated_picker_NEW_result_no_stale_import")
            preview();dialogButton(AlertDialog.BUTTON_POSITIVE);waitFor("Confirm write unresolved"){field("historyImportOperation")==null};drain()
            check(s.historyPage().entries.map{HistoryImport.portable(it)}==listOf(expected));check(!s.settings.contains("history.consent.v1"));events.add("actual_confirm_exact_record_without_capture_consent")
            val secure=dev.lip.auth.SecureStore(targetContext);val names=secure.names("history-");check(names.size==1);val encrypted=File(File(targetContext.noBackupFilesDir,"encrypted"),names.single()).readBytes()
            preview();dialogButton(AlertDialog.BUTTON_POSITIVE);waitFor("Identical confirmation unresolved"){field("historyImportOperation")==null};drain();check(secure.names("history-")==names&&File(File(targetContext.noBackupFilesDir,"encrypted"),names.single()).readBytes().contentEquals(encrypted));events.add("identical_no_write_no_duplicate")
            preview("conflicting-transcript.json");main{check(dialog()?.getButton(AlertDialog.BUTTON_POSITIVE)?.isShown!=true)};dialogButton(AlertDialog.BUTTON_NEGATIVE);drain();check(File(File(targetContext.noBackupFilesDir,"encrypted"),names.single()).readBytes().contentEquals(encrypted));events.add("conflict_blocked_original_ciphertext_unchanged")
            openPicker();chooseFile("malformed-transcript.json");waitFor("Malformed source worker unresolved"){field("historyImportOperation")==null};drain();check(dialog()==null&&File(File(targetContext.noBackupFilesDir,"encrypted"),names.single()).readBytes().contentEquals(encrypted));events.add("malformed_refused_original_unchanged")
            check(s.settings.all==originalSettings);check(changes.get()==0);report.put("passed",true)
            }
        }catch(e:Throwable){report.put("error",e.javaClass.simpleName+": "+(e.message?:"fixture failed"))}
        finally{
            clipboard.removePrimaryClipChangedListener(listener)
            if(owned&&store!=null)try{
                if(::ax.isInitialized&&latest.get()?.isDestroyed==false&&(pickerOutstanding||picker()||awaitingPicker()))cancelOwnedPicker()
                check(!::ax.isInitialized||!picker()){ "External picker still outstanding; preserve journal" }
                events.add("cleanup_finish_requested")
                main{for(a in actors)if(!a.isDestroyed)a.finish()};waitForIdleSync()
                waitFor("Fixture Activity destruction unresolved"){var destroyed=false;main{destroyed=actors.all{it.isDestroyed}};destroyed}
                events.add("cleanup_actors_destroyed");drain() // Confirm actual lease revocation BEFORE draining IO.
                val records=store.historyPage().entries
                check(records.isEmpty()||records.map{HistoryImport.portable(it)}==listOf(expected)){"Unexpected record; preserve recovery journal"}
                if(records.isNotEmpty())store.deleteTranscript(expected.id)
                check(store.historyPage().entries.isEmpty()){ "Owned record removal unconfirmed" };check(store.settings.all==originalSettings){ "Fixture preferences changed" }
                check(journal.delete()){ "Fixture recovery journal retirement failed" };syncDir()
            }catch(e:Throwable){cleanup=false;report.put("cleanup_error",e.javaClass.simpleName+": "+(e.message?:"unconfirmed"))}
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
        report.put("events",org.json.JSONArray(events)).put("cleanup_ok",cleanup).put("journal_retained",journal.exists()).put("clipboard_change_events",changes.get()).put("phone_tested",false)
        if(!cleanup)report.put("passed",false)
        finish(if(report.getBoolean("passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString(REPORT_KEY_STREAMRESULT,"\nHISTORY_IMPORT_UI_RESULT $report\n")})
    }
}
