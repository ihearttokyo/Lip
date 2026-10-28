package dev.lip

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Actual selected History row + system picker, only synthetic owned-emulator data. */
class HistorySafRunner:Instrumentation(){
    private var probe=true
    private var restoreOnly=false
    private val events=java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun trace(value:String){if(events.size<160)events.add("${SystemClock.uptimeMillis()} $value")}
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);probe=arguments?.getString("probe_picker")!="false";restoreOnly=arguments?.getString("restore_only")=="true";start()}
    private lateinit var ax:UiAutomation
    private val latest=AtomicReference<Activity?>()
    private val activities=java.util.concurrent.CopyOnWriteArrayList<Activity>()
    private val callbacks=object:Application.ActivityLifecycleCallbacks{
        override fun onActivityCreated(a:Activity,b:Bundle?){if(a is MainActivity){activities.add(a);latest.set(a);trace("main_created ${System.identityHashCode(a)}")}}
        override fun onActivityResumed(a:Activity){if(a is MainActivity){latest.set(a);trace("main_resumed ${System.identityHashCode(a)}")}}
        override fun onActivityStarted(a:Activity){};override fun onActivityPaused(a:Activity){};override fun onActivityStopped(a:Activity){}
        override fun onActivitySaveInstanceState(a:Activity,b:Bundle){};override fun onActivityDestroyed(a:Activity){if(a is MainActivity)trace("main_destroyed ${System.identityHashCode(a)}")}
    }
    private fun main(action:()->Unit){if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()){action();return};val error=AtomicReference<Throwable?>();runOnMainSync{try{action()}catch(e:Throwable){error.set(e)}};error.get()?.let{throw it}}
    private fun waitFor(label:String,condition:()->Boolean){val until=SystemClock.uptimeMillis()+8000;var done=condition();while(!done&&SystemClock.uptimeMillis()<until){SystemClock.sleep(50);done=condition()};check(done){label}}
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    private fun app()=checkNotNull(latest.get()).also{check(!it.isDestroyed)}
    private fun field(name:String,a:Activity=app()):Any? {var value:Any?=null;main{value=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)};return value}
    private fun status()=field("lastHistoryExportStatus") as String
    private fun busy(a:Activity)=field("historyExportBusy",a) as Boolean
    private fun docPackage(p:String?)=p in listOf("com.google.android.documentsui","com.android.documentsui")
    private fun clickText(text:String,documents:Boolean=false):Boolean{
        val windows=ax.windows
        try{for(w in windows){if(!w.isActive&&!w.isFocused)continue;val root=w.root?:continue
            try{val p=root.packageName?.toString();if(if(documents)!docPackage(p) else p!="dev.lip.android")continue
                val nodes=root.findAccessibilityNodeInfosByText(text)
                try{val n=nodes.firstOrNull{it.text?.toString()?.equals(text,true)==true&&it.isVisibleToUser&&it.isClickable}?:continue
                    check(n.performAction(Node.ACTION_CLICK));return true
                }finally{nodes.forEach{it.recycle()}}
            }finally{root.recycle()}
        }}finally{windows.forEach{it.recycle()}}
        return false
    }
    private fun pickerVisible():Boolean{val windows=ax.windows;try{return windows.any{val root=it.root;try{docPackage(root?.packageName?.toString())&&(it.isActive||it.isFocused)}finally{root?.recycle()}}}finally{windows.forEach{it.recycle()}}}
    private fun imeVisible():Boolean{val windows=ax.windows;try{return windows.any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}}finally{windows.forEach{it.recycle()}}}
    private fun backOutPicker(){
        if(pickerVisible()&&status()!="canceled"){
            val ime=imeVisible();trace("back_to_picker ime=$ime")
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync()
            if(ime){waitFor("IME did not hide/cancel"){!imeVisible()||status()=="canceled"};ax.waitForIdle(200,2000)}
            if(ime&&pickerVisible()&&status()!="canceled"){
                trace("back_cancel_after_ime_hidden");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync()
            }
        }
        waitFor("Picker did not cancel"){!pickerVisible()&&status()=="canceled"}
        check(!busy(app())&&field("selectedHistoryExport")==null);trace("picker_canceled_callback_drained")
    }
    private fun openExport(nonce:String){
        waitFor("Owned history row not loaded"){
            var button:Button?=null
            main{button=views(app().window.decorView).filterIsInstance<Button>().firstOrNull{b->b.text.toString()=="Export selected JSON…"&&views(b.parent as View).filterIsInstance<TextView>().any{it.text.toString().contains(nonce)}}}
            button!=null
        }
        main{val b=views(app().window.decorView).filterIsInstance<Button>().first{b->b.text.toString()=="Export selected JSON…"&&views(b.parent as View).filterIsInstance<TextView>().any{it.text.toString().contains(nonce)}};b.requestRectangleOnScreen(android.graphics.Rect(0,0,b.width,b.height),true);check(b.performClick())}
        waitForIdleSync();trace("export_warning_open_requested")
    }
    private fun pickerTree():org.json.JSONArray{
        val result=org.json.JSONArray();var count=0
        val generated=latest.get()?.takeUnless { it.isDestroyed }?.let { field("lastHistoryExportFilename",it) as? String }
        val controls=setOf("Save","SAVE","Cancel","Downloads","Recent","Documents","Show roots","Show navigation menu","New folder","More options","Search","List view","Grid view")
        fun safe(value:String)=if(value.isEmpty()||value in controls||value==generated||docs.any{value==it.name||value==it.name?.removeSuffix(".json")})value else "<redacted>"
        fun visit(n:Node,depth:Int){if(depth>16||count++>400)return
            val text=n.text?.toString().orEmpty()
            result.put(JSONObject().put("class",n.className).put("id",n.viewIdResourceName).put("text",safe(text))
                .put("desc",safe(n.contentDescription?.toString().orEmpty())).put("clickable",n.isClickable).put("visible",n.isVisibleToUser))
            for(i in 0 until n.childCount){val child=n.getChild(i)?:continue;try{visit(child,depth+1)}finally{child.recycle()}}
        }
        val windows=ax.windows;try{for(w in windows){val root=w.root?:continue;try{if(docPackage(root.packageName?.toString()))visit(root,0)}finally{root.recycle()}}}finally{windows.forEach{it.recycle()}}
        return result
    }
    private data class Doc(val requested:String,val empty:Boolean,val rename:Boolean=false,var uri:Uri?=null,var name:String?=null,var pressed:Boolean=false,var baseline:ByteArray?=null,var deleted:Boolean=false)
    private val docs=ArrayList<Doc>()
    private val grants=ArrayList<dev.lip.testing.HistoryGrantActivity>()
    private var manifest:JSONObject?=null
    private fun flushJournal(){
        val state=checkNotNull(manifest);state.put("documents",org.json.JSONArray(docs.map{d->JSONObject().put("requested",d.requested).put("empty",d.empty).put("rename",d.rename).put("pressed",d.pressed).put("uri",d.uri?.toString()?:JSONObject.NULL).put("name",d.name?:JSONObject.NULL).put("deleted",d.deleted).put("baseline_sha256",d.baseline?.let{bytes->java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}}?:JSONObject.NULL)}))
        val atomic=android.util.AtomicFile(journal());val out=atomic.startWrite()
        try{out.write(state.toString().toByteArray());atomic.finishWrite(out);syncDir()}catch(e:Throwable){atomic.failWrite(out);throw e}
    }
    private fun pickerNode(id:String,action:(Node)->Unit):Boolean{
        val windows=ax.windows
        try{for(w in windows){if(!w.isActive&&!w.isFocused)continue;val root=w.root?:continue
            try{if(!docPackage(root.packageName?.toString()))continue
                val nodes=root.findAccessibilityNodeInfosByViewId(id)
                try{val node=nodes.firstOrNull{it.isVisibleToUser&&(id!="android:id/title"||it.isEditable||it.className?.toString()=="android.widget.EditText")}?:continue;action(node);return true}finally{nodes.forEach{it.recycle()}}
            }finally{root.recycle()}
        }}finally{windows.forEach{it.recycle()}}
        return false
    }
    private fun clickOwnedFilename(name:String):Boolean{
        val windows=ax.windows
        try{for(w in windows){if(!w.isActive&&!w.isFocused)continue;val root=w.root?:continue
            try{if(!docPackage(root.packageName?.toString()))continue
                val nodes=root.findAccessibilityNodeInfosByText(name.removeSuffix(".json"))
                try{val label=nodes.firstOrNull{it.isVisibleToUser&&(it.text?.toString()==name||it.text?.toString()==name.removeSuffix(".json"))}?:continue
                    var node=Node.obtain(label)
                    try{
                        // Select only the actual document row containing the exact
                        // journaled label, not an arbitrary clickable ancestor.
                        var hops=0
                        while(node.viewIdResourceName!="${root.packageName}:id/item_root"&&hops<16){
                            val parent=node.parent?:break;node.recycle();node=parent;hops++
                        }
                        val samePackage=node.packageName?.toString()==root.packageName?.toString()
                        val documentRow=node.viewIdResourceName=="${root.packageName}:id/item_root"
                        val supportsClick=node.actionList.any { it.id==Node.ACTION_CLICK }
                        trace("regrant_target hops=$hops document_row=$documentRow clickable=${node.isClickable} same_package=$samePackage supports_click=$supportsClick class=${node.className} id=${node.viewIdResourceName}")
                        check(documentRow&&node.isClickable&&samePackage){"Recovery exact-label ancestor is not an eligible local-provider document row"}
                        check(node.performAction(Node.ACTION_CLICK)){"Recovery exact-label ACTION_CLICK was not accepted"};return true
                    }finally{node.recycle()}
                }finally{nodes.forEach{it.recycle()}}
            }finally{root.recycle()}
        }}finally{windows.forEach{it.recycle()}}
        return false
    }
    private fun reacquireOriginal(doc:Doc){
        val original=checkNotNull(doc.uri);val name=checkNotNull(doc.name){"No exact journaled display name; preserve target"}
        val helper=startActivitySync(Intent(targetContext,dev.lip.testing.HistoryGrantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as dev.lip.testing.HistoryGrantActivity
        grants.add(helper);main{helper.request(original)}
        waitFor("Recovery system OPEN picker not visible"){pickerVisible()}
        waitFor("Exact journaled fixture filename not available in picker"){clickOwnedFilename(name)}
        waitFor("Recovery picker result not returned"){var done=false;main{done=helper.completed};done&&!pickerVisible()}
        var actual:Uri?=null;main{actual=helper.selected}
        val knownAuthorities=setOf("com.android.providers.downloads.documents","com.android.externalstorage.documents")
        val returned=actual
        val returnedAuthority=returned?.authority?.takeIf { it in knownAuthorities } ?: "other_or_null"
        val sameDocumentId=returned!=null&&android.provider.DocumentsContract.isDocumentUri(targetContext,returned)&&
            android.provider.DocumentsContract.getDocumentId(returned)==android.provider.DocumentsContract.getDocumentId(original)
        val sameDisplayName=if(returned!=null&&returnedAuthority!="other_or_null")runCatching { metadata(returned).first==name }.getOrNull() else null
        trace("regrant_identity original_authority=${original.authority} returned_authority=$returnedAuthority same_uri=${returned==original} same_document_id=$sameDocumentId same_display_name=$sameDisplayName")
        check(actual==original){"Returned document URI differs from journaled original; no alias assumption or deletion"}
        check(metadata(original).first==name){"Regranted document metadata differs"}
        trace("exact_original_uri_regranted_by_real_open_picker")
    }
    private fun metadata(uri:Uri):Pair<String,Long>{
        check(uri.scheme=="content"&&uri.authority in listOf("com.android.externalstorage.documents","com.android.providers.downloads.documents")){"Not an approved local provider; preserve target"}
        return targetContext.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE),null,null,null).use{c->
            check(c!=null){"Provider unavailable; ownership unconfirmed"};if(!c.moveToFirst())throw java.io.FileNotFoundException("Owned document not present");val ni=c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME);val si=c.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)
            check(!c.isNull(ni)&&!c.isNull(si));c.getString(ni) to c.getLong(si)
        }
    }
    private fun readDocument(uri:Uri):ByteArray{
        val size=metadata(uri).second;check(size in 0..HistoryTransfer.MAX_BYTES.toLong()){"Unexpected file size; no unbounded read"}
        return checkNotNull(targetContext.contentResolver.openInputStream(uri)).use{it.readNBytes(HistoryTransfer.MAX_BYTES+1)}.also{check(it.size<=HistoryTransfer.MAX_BYTES)}
    }
    private fun validateDoc(doc:Doc,entry:Transcript,persist:Boolean=true):ByteArray{
        val uri=checkNotNull(doc.uri);val meta=metadata(uri)
        check(meta.first==doc.requested||(doc.rename&&meta.first.startsWith(doc.requested.removeSuffix(".json")))){"Unexpected document identity; no delete"}
        doc.name=meta.first;if(persist)flushJournal()
        val bytes=readDocument(uri)
        if(doc.empty)check(bytes.isEmpty()){"Recreated Activity exported stale selection"}
        else{
            val value=JSONObject(String(bytes,Charsets.UTF_8));val record=value.getJSONObject("record")
            check(value.getString("schema")==HistoryTransfer.SCHEMA&&value.getInt("version")==1&&value.getString("sourcePlatform")=="android")
            check(record.getString("id")==entry.id&&record.getString("original")==entry.raw&&record.getString("output")==entry.clean&&record.getLong("createdAtUnixMs")==entry.time&&record.getString("model")==entry.model&&!record.getBoolean("legacyCloudCleanup"))
            check(bytes.contentEquals(doc.baseline?:HistoryTransfer.encode(entry)))
        }
        return bytes
    }
    private fun openPicker(nonce:String):String{
        openExport(nonce);waitFor("Choose new file absent"){clickText("Choose new file…")};waitFor("Picker absent"){pickerVisible()}
        val filename=field("lastHistoryExportFilename") as? String
        check(filename!=null&&filename.matches(Regex("Lip-transcript-[0-9a-f-]{36}\\.json"))){"Unexpected generated filename"}
        var observed:String?=null
        waitFor("Real filename field not ready"){pickerNode("android:id/title"){observed=it.text?.toString()}&&observed==filename}
        check(observed==filename){"Picker changed generated filename unexpectedly"}
        return filename
    }
    private fun savePicker(entry:Transcript,requested:String,empty:Boolean=false,rename:Boolean=false):Doc{
        val doc=Doc(requested,empty,rename);docs.add(doc);flushJournal() // Exact target BEFORE Save.
        waitFor("Local Downloads root not observed"){
            val nodes=pickerTree();(0 until nodes.length()).any{nodes.getJSONObject(it).optString("id").endsWith("/breadcrumb_text")&&nodes.getJSONObject(it).optString("text")=="Downloads"}
        }
        waitFor("Save control not available"){
            pickerNode("android:id/button1"){check(it.text?.toString()?.equals("Save",true)==true);doc.pressed=true;flushJournal();check(it.performAction(Node.ACTION_CLICK))}
        }
        waitFor("Actual result URI not returned"){
            val uri=field("lastHistoryExportUri") as? Uri
            if(uri!=null){doc.uri=uri;flushJournal();true}else false
        }
        waitFor("Actual provider write not finished"){activities.none{busy(it)}&&status() in listOf("saved","unconfirmed","selection_lost")}
        check(status()==if(empty)"selection_lost" else "saved"){"Provider export not successful: "+status()}
        validateDoc(doc,entry);return doc
    }
    private fun preference(store:AppStore,value:Boolean){val done=CountDownLatch(1);val failure=AtomicReference<Throwable?>();store.setHistoryEnabled(value){if(it.failed)failure.set(IllegalStateException("Preference not persisted"));done.countDown()};check(done.await(10,TimeUnit.SECONDS));failure.get()?.let{throw it}}
    private fun journal()=File(targetContext.noBackupFilesDir,"hx-saf-recovery.json")
    private fun syncDir(){val fd=android.system.Os.open(targetContext.noBackupFilesDir.absolutePath,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_CLOEXEC or android.system.OsConstants.O_NOFOLLOW,0);try{check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode));android.system.Os.fsync(fd)}finally{android.system.Os.close(fd)}}
    override fun onStart(){
        val report=JSONObject().put("passed",false).put("scope",if(restoreOnly) "owned synthetic document recovery attempt; no export or recovery acceptance" else "actual warning/picker probe and cancellation only; no saved document claim")
        val application=targetContext.applicationContext as Application
        val changes=AtomicInteger();val clipboard=targetContext.getSystemService(ClipboardManager::class.java);val listener=ClipboardManager.OnPrimaryClipChangedListener{changes.incrementAndGet()}
        var store:AppStore?=null;var original:Boolean?=null;var owned=false;var seeded=false;var cleanup=true;var recoveryValidated=false
        var id=UUID.randomUUID().toString();var nonce="SAF-FIXTURE-$id"
        try{
            check(Build.HARDWARE in listOf("ranchu","goldfish")&&Build.PRODUCT.contains("sdk")&&targetContext.packageName=="dev.lip.android")
            check(!targetContext.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
            val d=Dictation.get(targetContext);check(!d.busy);store=d.store;check(!store.historyReadiness().preparing)
            if(restoreOnly){
                val atomic=android.util.AtomicFile(journal());val raw=atomic.openRead().use{it.readNBytes(32769)};check(raw.size<=32768)
                val state=JSONObject(String(raw,Charsets.UTF_8));check(state.getInt("version")==1)
                id=state.getString("id");check(UUID.fromString(id).toString()==id);nonce="SAF-FIXTURE-$id"
                val present=state.getBoolean("present");original=if(present){check(state.get("value") is Boolean);state.getBoolean("value")}else{check(state.isNull("value"));null}
                val savedDocs=state.getJSONArray("documents")
                check(savedDocs.length()<=4){"Unexpected recovery document count"}
                val recoveryEntry=Transcript(id=id,time=1700000000125L,raw="$nonce か\u3099 你好\n🎙️",clean="$nonce が 你好\n🎙️",language="en-US",usedChatGpt=false,model="synthetic-fixture",modelDetails="not a speech quality test")
                for(i in 0 until savedDocs.length()){
                    val saved=savedDocs.getJSONObject(i);val requested=saved.getString("requested")
                    check(requested.matches(Regex("Lip-transcript-[0-9a-f-]{36}\\.json")))
                    val doc=Doc(requested,saved.getBoolean("empty"),saved.getBoolean("rename"),pressed=saved.getBoolean("pressed"),deleted=saved.optBoolean("deleted",false))
                    if(!saved.isNull("uri"))doc.uri=Uri.parse(saved.getString("uri"))
                    if(!saved.isNull("name"))doc.name=saved.getString("name")
                    if(!saved.isNull("baseline_sha256")){
                        val planned=JSONObject(String(HistoryTransfer.encode(recoveryEntry),Charsets.UTF_8)).put("fixtureOccupiedBaseline",nonce).toString(2).toByteArray()
                        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(planned).joinToString(""){"%02x".format(it)}
                        check(digest==saved.getString("baseline_sha256")){"Recovery baseline not fixture-derived"};doc.baseline=planned
                    }
                    check(!doc.pressed||doc.uri!=null){"Save target URI unresolved; preserve manifest"};docs.add(doc)
                }
                manifest=state
                ax=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);ax.serviceInfo=ax.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
                check(!pickerVisible()){"Picker outstanding; no blind recovery"}
                val rows=ArrayList<Transcript>();var cursor:String?=null;val cursors=HashSet<String?>()
                do{check(cursors.add(cursor)&&cursors.size<=128){"Recovery scan ambiguous/over limit; preserve journal"}
                    val page=store.historyPage(after=cursor);rows.addAll(page.entries.filter{it.id==id});cursor=page.nextCursor
                }while(cursor!=null)
                check(rows.size<=1&&rows.all{it.raw=="$nonce か\u3099 你好\n🎙️"&&it.clean=="$nonce が 你好\n🎙️"&&it.model=="synthetic-fixture"}){"Unexpected recovery record; preserved"}
                for(doc in docs)if(!doc.deleted&&doc.uri!=null){
                    try{validateDoc(doc,recoveryEntry,persist=false)}catch(e:SecurityException){
                        reacquireOriginal(doc);validateDoc(doc,recoveryEntry,persist=false)
                    } // Missing/open/read failures are UNCONFIRMED, never proof of deletion.
                }
                seeded=rows.isNotEmpty();recoveryValidated=true;owned=true;flushJournal()
                report.put("recovery_only",true).put("scope","exact validated fixture journal/owned-URI recovery; no export acceptance").put("passed",true)
            }else{
            check(!journal().exists()){"Unresolved SAF fixture journal; preserved"}
            val prior=store.settings.all["history.consent.v1"];check(prior==null||prior is Boolean);original=prior as Boolean?
            check(journal().createNewFile());owned=true
            manifest=JSONObject().put("version",1).put("id",id).put("present",original!=null).put("value",original?:JSONObject.NULL)
            flushJournal()
            check(store.historyPage(nonce).entries.isEmpty())
            preference(store,true)
            val entry=Transcript(id=id,time=1700000000125L,raw="$nonce か\u3099 你好\n🎙️",clean="$nonce が 你好\n🎙️",language="en-US",usedChatGpt=false,model="synthetic-fixture",modelDetails="not a speech quality test")
            val ticket=checkNotNull(store.historyTicket());seeded=true;check(store.save(entry,ticket));preference(store,false)
            ax=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ax.serviceInfo=ax.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
            clipboard.addPrimaryClipChangedListener(listener)
            application.registerActivityLifecycleCallbacks(callbacks)
            val initial=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));latest.set(initial);if(!activities.contains(initial))activities.add(initial)
            waitForIdleSync();main{check(views(app().window.decorView).filterIsInstance<Button>().single{it.text.toString()=="History"}.performClick())}
            openExport(nonce);waitFor("Export warning Cancel absent"){clickText("Cancel")}
            check(field("selectedHistoryExport")==null&&!busy(app())&&!pickerVisible());report.put("warning_cancel_no_export",true)
            openExport(nonce);waitFor("Choose new file action absent"){clickText("Choose new file…")}
            waitFor("System picker not visible"){pickerVisible()};report.put("picker_controls",pickerTree())
            backOutPicker();report.put("picker_cancel_clears_selection",true)
            if(!probe){
                report.put("scope","actual History→system Downloads picker→returned URI grant→production writer, cancellation/recreation/occupied-name cases; no cloud/provider-fault/crash proof")
                val firstName=openPicker(nonce);val first=savePicker(entry,firstName)
                report.put("real_downloads_exact_export",true)
                // This is fixture setup on a just-created, verified own document,
                // NOT a production overwrite fallback. Make its bytes DIFFERENT so
                // an accidental clobber cannot hide behind identical export content.
                val firstBytes=JSONObject(String(readDocument(checkNotNull(first.uri)),Charsets.UTF_8)).put("fixtureOccupiedBaseline",nonce).toString(2).toByteArray()
                first.baseline=firstBytes;flushJournal()
                val descriptor=checkNotNull(targetContext.contentResolver.openFileDescriptor(checkNotNull(first.uri),"rw"))
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use{out->out.channel.truncate(0);out.write(firstBytes);out.channel.force(true)}
                check(readDocument(checkNotNull(first.uri)).contentEquals(firstBytes))
                openPicker(nonce)
                waitFor("Filename edit unavailable"){pickerNode("android:id/title"){check(it.performAction(Node.ACTION_SET_TEXT,Bundle().apply{putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,firstName)}))}}
                waitFor("Occupied filename edit not reflected"){var same=false;pickerNode("android:id/title"){same=it.text?.toString()==firstName};same}
                val copy=savePicker(entry,firstName,rename=true)
                check(copy.uri!=first.uri&&readDocument(checkNotNull(first.uri)).contentEquals(firstBytes)){"Occupied original changed"}
                report.put("occupied_name_distinct_document_original_unchanged",true)
                val recreateName=openPicker(nonce);val before=app();main{before.recreate()}
                waitFor("Main Activity not actually recreated behind picker"){latest.get()!=before&&before.isDestroyed}
                check(pickerVisible())
                savePicker(entry,recreateName,empty=true)
                report.put("recreated_before_save_empty_no_stale_write",true)
            }
            check(changes.get()==0){"Clipboard changed; no contents read or replacement attempted"}
            report.put("clipboard_change_events",changes.get()).put("passed",true)
            }
        }catch(e:Throwable){report.put("error",e.javaClass.simpleName+": "+(e.message?:"fixture failed"));if(::ax.isInitialized&&pickerVisible())report.put("failure_picker_controls",pickerTree())}
        finally{
            if(restoreOnly&&!recoveryValidated)cleanup=false // No retirement or preference changes after ANY failed ownership precondition.
            clipboard.removePrimaryClipChangedListener(listener)
            if(::ax.isInitialized&&activities.isNotEmpty())try{
                if(pickerVisible())backOutPicker()
                check(!pickerVisible()){"Picker flow still outstanding; journal preserved"}
                if(latest.get()?.isDestroyed==false)check(field("selectedHistoryExport")==null){"Pending picker selection not cleared"}
            }catch(_:Throwable){cleanup=false}
            if(::ax.isInitialized&&grants.isNotEmpty())try{
                if(pickerVisible()){
                    val ime=imeVisible();sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync()
                    if(ime){waitFor("Grant keyboard did not hide"){!imeVisible()};ax.waitForIdle(200,2000);if(pickerVisible())sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)}
                }
                waitFor("Recovery picker still outstanding"){!pickerVisible()}
            }catch(_:Throwable){cleanup=false}
            if(owned&&store!=null){
                try{
                    check(activities.none{busy(it)}){"Export worker unresolved; no race cleanup"}
                    for(doc in docs){
                        if(doc.deleted)continue
                        if(doc.uri==null){check(!doc.pressed){"Save may have created unresolved document; recovery journal retained"};continue}
                        validateDoc(doc,Transcript(id=id,time=1700000000125L,raw="$nonce か\u3099 你好\n🎙️",clean="$nonce が 你好\n🎙️",language="en-US",usedChatGpt=false,model="synthetic-fixture",modelDetails="not a speech quality test"))
                        check(android.provider.DocumentsContract.deleteDocument(targetContext.contentResolver,checkNotNull(doc.uri))){"Owned document cleanup unconfirmed"}
                        doc.deleted=true;flushJournal();trace("owned_document_deleted")
                    }
                }catch(e:Throwable){cleanup=false;report.put("document_cleanup_error",e.javaClass.simpleName+": "+(e.message?:"unconfirmed"))}
                try{if(seeded)store.deleteTranscript(id)}catch(_:Throwable){cleanup=false}
                // Preference restoration MUST happen even if record cleanup/readback fails.
                try{preference(store,original==true);if(original==null)check(store.settings.edit().remove("history.consent.v1").commit());check(store.settings.all["history.consent.v1"]==original)}catch(_:Throwable){cleanup=false}
            }
            try{main{for(a in activities)if(!a.isDestroyed)a.finish()};waitForIdleSync()}catch(_:Throwable){cleanup=false}
            try{main{for(a in grants)if(!a.isDestroyed)a.finish()};waitForIdleSync()}catch(_:Throwable){cleanup=false}
            application.unregisterActivityLifecycleCallbacks(callbacks)
            if(owned&&cleanup)try{check(journal().delete());syncDir()}catch(_:Throwable){cleanup=false}
            report.put("events",org.json.JSONArray(events)).put("cleanup_ok",cleanup).put("recovery_journal_exists",journal().exists()).put("phone_tested",false)
            if(!cleanup)report.put("passed",false)
        }
        finish(if(report.getBoolean("passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString(REPORT_KEY_STREAMRESULT,"\nHISTORY_SAF_RESULT $report\n")})
    }
}
