package dev.lip.stickers

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.lip.keyboard.GlassKeyButton
import dev.lip.keyboard.GlassMaterial
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Later-major local management only. Not linked from the parity release; no activation or delivery. */
class StickerPacksActivity:Activity() {
    private enum class Phase { SCAN, PICKER, PREPARING, REVIEW, COMMIT, READ, RECONCILE }
    private class Operation(var phase:Phase) {
        val live=AtomicBoolean(true)
        val cancellation=CancellationSignal()
        val control=StickerCommitControl()
        var generation=0L
        var awaitingPicker=false
        var prepared:PreparedStickerPack?=null
        var confirmed=false
        fun cancel(){live.set(false);cancellation.cancel();control.cancel();prepared=null}
    }
    private data class Storage(val store:StickerPackTransactionStore,val journal:StickerImportJournal,val root:Path)
    private data class Library(val entries:List<StickerStoredEntry>,val pending:List<String>,val draining:Boolean)
    private data class CommitOutcome(val id:String,val state:StickerImportJournal.Outcome?)
    private val worker=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),{r->Thread(r,"lip-sticker-packs").apply{isDaemon=true}})
    private var operation:Operation?=null
    private var generation=0L
    private var destroyed=false
    private var stopped=false
    private var refreshOnStart=false
    private val bitmaps=ArrayList<Bitmap>()
    private val imageViews=ArrayList<ImageView>()

    override fun onCreate(state:Bundle?){super.onCreate(state);GlassMaterial.refresh();window.statusBarColor=Color.BLACK;window.navigationBarColor=Color.BLACK;loadLibrary()}
    override fun onStart(){
        super.onStart();stopped=false
        if(refreshOnStart){refreshOnStart=false;loadLibrary("Previous screen closed. Check the local state before continuing.")}
    }
    override fun onStop(){
        stopped=true
        // The external picker is the only authorized continuation across a stopped screen.
        if(operation?.awaitingPicker!=true){cancelOperation();refreshOnStart=true}
        super.onStop()
    }
    override fun onDestroy(){
        destroyed=true;cancelOperation();worker.shutdownNow();recyclePreviews();super.onDestroy()
    }
    @Deprecated("Activity back callback") override fun onBackPressed(){cancelOperation();super.onBackPressed()}
    private fun cancelOperation(){val old=operation;operation=null;old?.cancel();generation++}
    private fun owns(op:Operation)=operation===op&&op.live.get()&&!destroyed&&!isFinishing&&!isDestroyed&&op.generation==generation
    private fun px(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun recyclePreviews(){imageViews.forEach{it.setImageDrawable(null)};imageViews.clear();bitmaps.forEach{if(!it.isRecycled)it.recycle()};bitmaps.clear()}
    private fun screen(title:String,subtitle:String):LinearLayout {
        generation++;recyclePreviews()
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.BLACK);isFillViewport=true}
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(px(20),px(12),px(20),px(24))}
        fun plain(text:String,size:Float)=label(text,size)
        column.addView(action("Back to keyboard settings"){cancelOperation();finish()})
        column.addView(plain(title,34f).apply{typeface=Typeface.create("sans-serif-light",Typeface.NORMAL);setPadding(0,px(24),0,px(8))})
        column.addView(plain(subtitle,16f))
        scroll.addView(column);setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener{view,insets->val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        scroll.requestApplyInsets();return column
    }
    private fun label(value:String,size:Float=16f)=TextView(this).apply{
        text=value;textSize=size;setTextColor(Color.WHITE);includeFontPadding=true
        setPadding(0,px(8),0,px(10));setLineSpacing(px(2).toFloat(),1f)
        layoutParams=LinearLayout.LayoutParams(-1,-2)
    }
    private fun action(value:String,enabled:Boolean=true,block:()->Unit):GlassKeyButton {
        val token=generation
        return GlassKeyButton(this,capsule=true).apply{
            text=value;contentDescription=value;isEnabled=enabled;setTextColor(Color.WHITE)
            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);textSize=17f
            setSingleLine(false);includeFontPadding=true;minHeight=px(48);minimumHeight=px(48)
            setPadding(px(16),px(12),px(16),px(12))
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=px(8);bottomMargin=px(4)}
            interactionAllowed={token==generation&&!destroyed&&!isFinishing&&!stopped&&isAttachedToWindow}
            setOnClickListener{if(interactionAllowed())block()}
        }
    }
    private fun busy(op:Operation,title:String,message:String){
        val column=screen(title,message);op.generation=generation
        column.addView(action("Cancel this operation"){if(owns(op)){cancelOperation();loadLibrary("Canceled before any further admission. If a commit was already admitted, reconcile its exact ID.")}})
    }
    private fun <T> submit(op:Operation,work:()->T,success:(T)->Unit,failure:(Throwable)->Unit){
        val token=op.generation
        try{worker.execute{
            val result=runCatching{work()}
            runOnUiThread{if(owns(op)&&token==generation)result.fold(success,{failure(it)})}
        }}catch(e:java.util.concurrent.RejectedExecutionException){if(owns(op))failure(e)}
    }
    private fun ownedDirectory(name:String):Path {
        check(name in setOf("sticker-packs-v1","sticker-import-journal-v1"))
        val parent=noBackupFilesDir.toPath().toRealPath()
        val before=Files.readAttributes(parent,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        check(before.isDirectory&&!before.isSymbolicLink)
        val path=parent.resolve(name)
        try{Files.createDirectory(path)}catch(_:FileAlreadyExistsException){}
        val child=Files.readAttributes(path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        check(child.isDirectory&&!child.isSymbolicLink&&path.toRealPath().parent==parent)
        val after=Files.readAttributes(parent,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        check(before.fileKey()!=null&&before.fileKey()==after.fileKey())
        FileChannel.open(parent,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use{it.force(true)}
        return path
    }
    private fun storage()=Storage(StickerPackTransactionStore(ownedDirectory("sticker-packs-v1")),StickerImportJournal(ownedDirectory("sticker-import-journal-v1")),ownedDirectory("sticker-packs-v1"))
    private fun loadLibrary(message:String?=null){
        if(destroyed||isFinishing)return
        cancelOperation();val op=Operation(Phase.SCAN);operation=op
        busy(op,"Sticker packs","Reading this device's local pack inventory…")
        submit(op,{
            val storage=storage();val entries=storage.store.scan{op.live.get()}
            Library(entries,storage.journal.unresolved(),StickerImportAdmission.isActive())
        },{library->operation=null;drawLibrary(library,message)}, {
            operation=null;val column=screen("Local packs unavailable","Existing originals and recovery records were preserved. Import is blocked until storage can be checked.")
            column.addView(action("Check local storage again"){loadLibrary()})
        })
    }
    private fun drawLibrary(library:Library,message:String?){
        val column=screen("Sticker packs","Original ZIPs and bounded static previews stay on this device. Nothing here activates, sends, exports or syncs a sticker.")
        message?.let{column.addView(label(it))}
        if(library.draining)column.addView(label("An earlier import is still finishing or draining. No new commit or absence decision is allowed yet."))
        if(library.pending.isNotEmpty()){
            column.addView(label("An import needs reconciliation",22f))
            library.pending.forEach{id->column.addView(label("Transaction $id"));column.addView(action("Check this exact transaction",!library.draining){reconcile(id)})}
        }
        column.addView(action("Choose a sticker pack ZIP…",library.pending.isEmpty()&&!library.draining){chooseImport()})
        column.addView(action("Refresh local packs"){loadLibrary()})
        column.addView(label("Saved packs",22f))
        if(library.entries.isEmpty())column.addView(label("No committed packs yet. Choose a canonical Lip sticker-pack ZIP to validate it and review before saving."))
        library.entries.forEach{entry->
            column.addView(label(entry.identity?.name?:"Unavailable pack",20f))
            column.addView(label("Commit ${entry.commitId}\n"+if(entry.available)"Available for local inspection. Rights remain unverified." else "Integrity or storage check failed (${entry.reason?.name?:"UNAVAILABLE"}). Original files are retained."))
            if(entry.available)column.addView(action("Inspect ${entry.identity!!.name}"){openPack(entry.commitId)})
        }
        column.addView(label("Imports and interrupted stages share a 256 MiB / 128-directory safety limit. Recovery journals are also bounded; no automatic deletion or eviction.",14f))
    }
    private fun chooseImport(){
        if(operation!=null)return
        val op=Operation(Phase.PICKER);operation=op
        val column=screen("Choose a pack","Lip reads only the ZIP you select. The source is not changed. Validation and isolated decoding happen before you can confirm a local save.")
        op.generation=generation
        column.addView(label("Canonical Lip sticker-pack ZIP only, up to 16 MiB. No URI permission is retained. Cloud/pipe sources and other sticker formats are not supported in this stage."))
        column.addView(action("Choose ZIP…"){
            if(owns(op)&&!op.awaitingPicker){
                op.awaitingPicker=true
                try{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),PICK_PACK)}
                catch(_:Exception){op.awaitingPicker=false;cancelOperation();loadLibrary("No file picker available. Nothing imported.")}
            }
        })
        column.addView(action("Cancel"){if(owns(op)){cancelOperation();loadLibrary("Nothing imported.")}})
    }
    @Deprecated("Activity result bridge") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=PICK_PACK)return
        val op=operation?:return // Recreation never restores picker authorization or a URI.
        if(!op.awaitingPicker)return
        op.awaitingPicker=false
        if(!owns(op)||resultCode!=RESULT_OK||data?.data==null){cancelOperation();loadLibrary("File selection canceled. Nothing imported.");return}
        val uri:Uri=data.data!!;op.phase=Phase.PREPARING
        busy(op,"Validate your pack","Reading the selected ZIP and validating every image in the isolated codec. No pack has been committed.")
        submit(op,{
            val archive=StickerPackDocumentImport.read(contentResolver,uri,op.cancellation)
            StickerPackPreparer(applicationContext).prepare(archive){op.live.get()}
        },{prepared->op.prepared=prepared;op.phase=Phase.REVIEW;showPrepared(op,prepared)}, {failure->
            cancelOperation();loadLibrary(if(failure is StickerDocumentImportException)
                "An earlier file read is still finishing. Wait, refresh, then choose the ZIP again. Nothing was committed by this attempt."
                else "The selected file could not be validated or preparation was canceled. Nothing committed; source unchanged.")
        })
    }
    private fun showPrepared(op:Operation,prepared:PreparedStickerPack){
        if(!owns(op))return
        val column=screen("Review your pack","Validation completed. Confirm only if you want the original ZIP and static previews saved privately on this device.")
        op.generation=generation
        describe(column,prepared.inspected,prepared.archiveSha256)
        column.addView(action("Confirm local import"){
            if(owns(op)&&!op.confirmed&&op.prepared===prepared){op.confirmed=true;op.prepared=null;commit(op,prepared)}
        })
        column.addView(action("Cancel import"){if(owns(op)){cancelOperation();loadLibrary("Nothing imported. The selected source was not changed.")}})
        showPreviews(column,prepared.inspected){i->
            val p=prepared.previewDescriptors[i];Preview(p.width,p.height,prepared.previewBytes(i))
        }
    }
    private fun describe(column:LinearLayout,pack:InspectedStickerPack,archiveSha:String){
        val p=pack.provenance
        column.addView(label(pack.identity.name,26f))
        column.addView(label("Pack ID: ${pack.identity.id}\nAuthor: ${pack.identity.author}\nStickers: ${pack.stickers.size}\nRights: UNVERIFIED"))
        column.addView(label("Source declarations",22f))
        column.addView(label("Source: ${p.sourceName}\nRevision: ${p.sourceRevision.ifEmpty{"Not declared"}}\nClaimed rightsholder: ${p.rightsholder}\nDeclared license: ${p.license}\nSource format: ${p.sourceFormat}"))
        column.addView(label("These are the file's assertions, not verified ownership or permission. Successful decoding grants no rights. No sticker is activated or sent."))
        column.addView(label("Archive SHA-256\n$archiveSha",14f))
        p.sourceManifest?.let{column.addView(label("Original source metadata: ${it.path}\n${it.size} bytes\nSHA-256 ${it.sha256}",14f))}
        p.notices.forEach{column.addView(label("Original notice: ${it.path}\n${it.size} bytes\nSHA-256 ${it.sha256}",14f))}
        column.addView(label("Original declarations, source metadata and notice bytes are preserved in the ZIP. Previews below are bounded first-frame pixels, not animated playback.",14f))
    }
    private data class Preview(val width:Int,val height:Int,val rgba:ByteArray)
    private fun showPreviews(column:LinearLayout,pack:InspectedStickerPack,read:(Int)->Preview){
        val names=listOfNotNull(pack.trayImage?.let{"Pack tray"})+pack.stickers.map{it.name}
        val gallery=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        column.addView(gallery);var offset=0
        fun draw(){
            recyclePreviews();gallery.removeAllViews()
            val end=minOf(offset+8,names.size)
            gallery.addView(label("Static previews ${offset+1}–$end of ${names.size}",22f))
            gallery.addView(label("All images were validated. Only this page of up to eight raw previews is rendered at once.",14f))
            for(i in offset until end){
                val p=read(i)
                check(p.rgba.size==StickerPixelContract.byteCount(p.width,p.height))
                val colors=IntArray(p.width*p.height)
                // setPixels accepts straight ARGB colors; undo premultiplication explicitly.
                // This creates a display bitmap from raw pixels only, never an encoded decoder.
                for(n in colors.indices){val at=n*4;val a=p.rgba[at+3].toInt() and 255
                    fun channel(channelOffset:Int):Int=if(a==0)0 else (((p.rgba[at+channelOffset].toInt() and 255)*255+a/2)/a).coerceIn(0,255)
                    colors[n]=(a shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                }
                val bitmap=try{Bitmap.createBitmap(p.width,p.height,Bitmap.Config.ARGB_8888)}catch(_:OutOfMemoryError){
                    gallery.addView(label("More previews cannot be displayed with the available memory. All pack files remain preserved."));return
                }
                try{bitmap.setPixels(colors,0,p.width,0,0,p.width,p.height)}catch(_:RuntimeException){
                    bitmap.recycle();gallery.addView(label("A raw preview could not be displayed. No encoded image fallback was used."));return
                }
                bitmaps+=bitmap;gallery.addView(label(names[i],18f))
                val image=ImageView(this).apply{
                    contentDescription="Static preview: ${names[i]}";setImageBitmap(bitmap);scaleType=ImageView.ScaleType.FIT_CENTER;adjustViewBounds=true
                    layoutParams=LinearLayout.LayoutParams(-1,px(144)).apply{bottomMargin=px(12)}
                }
                imageViews+=image;gallery.addView(image)
            }
            gallery.addView(action("Previous eight previews",offset>0){offset=(offset-8).coerceAtLeast(0);draw()})
            gallery.addView(action("Next eight previews",end<names.size){offset=end;draw()})
        }
        draw()
    }
    private fun commit(op:Operation,prepared:PreparedStickerPack){
        op.phase=Phase.COMMIT
        busy(op,"Save locally","Transaction ${op.control.commitId}\nCancel prevents only an unclaimed commit. An admitted commit may finish after this screen closes; its exact ID will be reconciled before another attempt.")
        submit(op,{
            if(!StickerImportAdmission.claim())error("An earlier import is draining.")
            try {
                val storage=storage()
                if(!op.live.get())return@submit CommitOutcome(op.control.commitId,StickerImportJournal.Outcome.ABSENT)
                storage.journal.begin(op.control.commitId) // Forced before any commit, UUID only.
                try {
                    storage.store.commit(prepared,op.control){op.live.get()}
                    storage.journal.settle(op.control.commitId,StickerImportJournal.Outcome.PUBLISHED)
                    CommitOutcome(op.control.commitId,StickerImportJournal.Outcome.PUBLISHED)
                }catch(e:StickerStoreException){
                    if(e.publication==StickerStoreException.Publication.NOT_PUBLISHED){
                        try{storage.journal.settle(op.control.commitId,StickerImportJournal.Outcome.ABSENT);CommitOutcome(op.control.commitId,StickerImportJournal.Outcome.ABSENT)}
                        catch(_:Exception){CommitOutcome(op.control.commitId,null)}
                    }else CommitOutcome(op.control.commitId,null)
                }catch(_:Exception){CommitOutcome(op.control.commitId,null)}
            }finally{StickerImportAdmission.release()}
        },{outcome->operation=null
            loadLibrary(when(outcome.state){StickerImportJournal.Outcome.PUBLISHED->"Saved locally. Commit ${outcome.id}. Nothing activated or sent."
                StickerImportJournal.Outcome.ABSENT->"Commit ${outcome.id} did not publish. Any interrupted staging remains preserved."
                null->"Import unconfirmed. Reconcile exact transaction ${outcome.id} before retrying."})
        },{operation=null;loadLibrary("Import unconfirmed. Check transaction ${op.control.commitId} and local recovery records before retrying.")})
    }
    private fun reconcile(id:String){
        if(operation!=null)return
        val op=Operation(Phase.RECONCILE);operation=op;busy(op,"Check exact transaction","Transaction $id\nThis only reads and reconciles an existing attempt. It never replays an import.")
        submit(op,{
            if(!StickerImportAdmission.claim())error("An import is still draining.")
            try {
                val storage=storage();check(id in storage.journal.unresolved())
                val outcome=try{storage.store.read(id){op.live.get()};StickerImportJournal.Outcome.PUBLISHED}
                catch(e:StickerStoreException){
                    if(e.reason!=StickerStoreException.Reason.UNAVAILABLE)throw e
                    // Missing file in an existing commit is NOT proof of absence.
                    storage.store.scan{op.live.get()}
                    check(Files.notExists(storage.root.resolve("commit-$id"),LinkOption.NOFOLLOW_LINKS)){"Existing commit remains unconfirmed."}
                    StickerImportJournal.Outcome.ABSENT
                }
                check(op.live.get());storage.journal.settle(id,outcome);outcome
            }finally{StickerImportAdmission.release()}
        },{outcome->operation=null;loadLibrary(if(outcome==StickerImportJournal.Outcome.PUBLISHED)"Transaction $id is present and integrity-checked. Rights remain unverified." else "Transaction $id is absent after the previous worker finished. Interrupted staging remains preserved.")}, {
            operation=null;loadLibrary("Transaction $id remains unconfirmed. No import was replayed and no retry was made.")
        })
    }
    private fun openPack(id:String){
        if(operation!=null)return
        val op=Operation(Phase.READ);operation=op;busy(op,"Inspect saved pack","Rechecking the original archive and every raw preview…")
        submit(op,{storage().store.read(id){op.live.get()}},{pack->
            if(!owns(op))return@submit
            val column=screen("Saved pack","Local inspection only. No original encoded image is decoded here.");op.generation=generation
            describe(column,pack.inspected,pack.archiveSha256);column.addView(label("Commit $id",14f))
            column.addView(action("Back to saved packs"){if(owns(op)){cancelOperation();loadLibrary()}})
            showPreviews(column,pack.inspected){i->pack.previews[i].let{Preview(it.width,it.height,it.premultipliedRgbaSrgbBytes())}}
        },{cancelOperation();loadLibrary("Pack unavailable after integrity checking. Its originals were preserved.")})
    }
    companion object { private const val PICK_PACK=4201 }
}
