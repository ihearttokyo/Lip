package dev.lip.keyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.lip.auth.SecureStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Main-owned bounded adapter. Only the encrypted repository runs on its serialized worker. */
class ClipboardHistoryController(context:Context,private val changed:()->Unit):AutoCloseable {
    private val app=context.applicationContext
    private val preferences=app.getSharedPreferences("preferences",Context.MODE_PRIVATE)
    private val consent=ClipboardHistoryConsent(
        {ClipboardConsent(preferences.getBoolean(ENABLED,false),preferences.getBoolean(ERASE,false),preferences.getString(TOKEN,null))},
        {preferences.edit().putBoolean(ENABLED,it.enabled).putBoolean(ERASE,it.erasing).putString(TOKEN,it.token).commit()})
    private val secure=SecureStore(app)
    private val repository=ClipboardHistoryRepository({secure.read(FILE)},{secure.write(FILE,it)},{secure.delete(FILE)})
    private val worker=Executors.newSingleThreadExecutor{r->Thread(r,"lip-clipboard-history").apply{isDaemon=true}}
    private val main=Handler(Looper.getMainLooper())
    private val generation=AtomicLong()
    @Volatile private var closed=false
    @Volatile private var eligible=false
    @Volatile private var blocked=consent.persistenceFailed
    @Volatile var enabled=preferences.getBoolean(ENABLED,false);private set
    var items:List<SavedClip> = emptyList()
        get(){field=if(closed||!eligible||!consent.capturePermitted())emptyList() else ClipboardHistory.prune(field,System.currentTimeMillis());return field}
        private set
    var message:String?=null;private set
    private data class Capture(val generation:Long,val text:String,val time:Long)
    private val pending=ArrayDeque<Capture>() // At most the same20 copies as the ledger's total capacity.
    private var token=0L
    private var busyToken:Long?=null
    val busy get()=busyToken!=null
    val paused get()=blocked||erasePending
    private var erasePending=preferences.getBoolean(ERASE,false)
    private var eraseRestoreEnabled=preferences.getBoolean(ENABLED,false)
    private val expiry=Runnable{
        if(!closed){items=ClipboardHistory.prune(items,System.currentTimeMillis());changed();if(enabled&&eligible&&!busy)refresh();armExpiry()}
    }
    init{
        if(consent.persistenceFailed)message="History paused: a setting was not saved. Retry or clear before relying on it."
        else if(erasePending){enabled=false;blocked=true;erase(eraseRestoreEnabled)}
    }
    private fun armExpiry(){
        main.removeCallbacks(expiry)
        if(closed||!enabled||!eligible||blocked)return
        val now=System.currentTimeMillis()
        val delay=items.filter{!it.pinned}.minOfOrNull{if(now<it.time)1L else (ClipboardHistory.TTL_MS-(now-it.time)).coerceAtLeast(1L)}
        if(delay!=null)main.postDelayed(expiry,delay)
    }
    fun revalidateTime(){onMain();items=ClipboardHistory.prune(items,System.currentTimeMillis());changed();if(enabled&&eligible&&!busy)refresh();armExpiry()}
    private fun onMain(){check(Looper.myLooper()==Looper.getMainLooper())}
    private fun authorized(g:Long)=!closed&&enabled&&!blocked&&eligible&&generation.get()==g&&consent.capturePermitted()
    fun canCapture():Boolean{onMain();return authorized(generation.get())&&!erasePending}
    fun setEligible(value:Boolean){
        onMain();if(eligible==value)return;eligible=value
        if(!value){generation.incrementAndGet();pending.clear();items=emptyList()}
        else if(enabled&&!blocked&&!busy)refresh()
        changed();armExpiry()
    }
    fun enable(){
        onMain();if(closed||erasePending||busy||enabled)return
        if(!consent.enable()){
            blocked=true;message="History setting could not be saved; history is off.";changed();return
        }
        enabled=true;blocked=false;message=null;generation.incrementAndGet();refresh();changed()
        // No retroactive capture. Saving the current clipboard is a separate explicit button.
    }
    fun retry(){
        onMain();if(busy)return
        if(consent.persistenceFailed&&!consent.retryFailed()){message="History setting is still unresolved. Capture stays paused.";changed();return}
        val state=consent.current();generation.incrementAndGet();erasePending=state.erasing;eraseRestoreEnabled=state.enabled
        if(state.erasing){erase(state.enabled);return}
        enabled=state.enabled;blocked=false;message=null;refresh();changed()
    }
    fun clear(disable:Boolean){onMain();if(closed||erasePending&&busy)return;erase(if(disable)false else enabled)}
    private fun erase(restoreEnabled:Boolean){
        eraseRestoreEnabled=restoreEnabled;erasePending=true
        generation.incrementAndGet();enabled=false;blocked=true;pending.clear();items=emptyList();main.removeCallbacks(expiry)
        val eraseToken=consent.requestErase(restoreEnabled)
        if(eraseToken==null){
            message="History paused. Erasure request was not saved; retry before restarting Lip.";changed();return
        }
        erasePending=true;message="Clearing saved clipboard items…"
        val job=++token;busyToken=job
        worker.execute{
            val result=runCatching{
                consent.finishErase(repository,eraseToken)
            }
            main.post{
                if(closed||busyToken!=job)return@post
                busyToken=null
                val state=consent.current()
                if(result.getOrNull()==ClipboardEraseResult.APPLIED&&state.token==eraseToken&&!state.erasing&&!consent.persistenceFailed){
                    erasePending=false;enabled=state.enabled;blocked=false;message=null;if(eligible&&enabled)refresh()
                }else{enabled=false;blocked=true;message="Saved items or settings remain unresolved. History stays paused; retry."}
                changed();armExpiry()
            }
        }
        changed()
    }
    /** Caller must recheck context/default-IME/lock after fetching the exact ClipData. */
    fun capture(text:String){
        onMain();if(!canCapture()||!ClipboardHistory.admissible(text))return
        if(pending.size==ClipboardHistory.MAX_ITEMS)pending.removeFirst()
        pending.addLast(Capture(generation.get(),text,System.currentTimeMillis()))
        drain()
    }
    private fun drain(){
        if(busy||closed)return
        if(pending.isEmpty())return
        val batch=pending.toList();pending.clear()
        if(batch.any{!authorized(it.generation)})return
        schedule{entries->batch.fold(entries){list,item->ClipboardHistory.add(list,item.text,item.time)}}
    }
    fun refresh(){onMain();if(!busy&&canCapture())schedule{it}}
    fun pin(id:String,pinned:Boolean){
        onMain();if(busy||!canCapture())return
        val item=items.firstOrNull{it.id==id}?:return
        if(item.pinned==pinned||pinned&&items.count{it.pinned}>=ClipboardHistory.MAX_PINS)return
        val time=System.currentTimeMillis() // Capture before queuing; never stamp an unpin after the repository's prune time.
        schedule{ClipboardHistory.setPinned(it,id,pinned,time)}
    }
    fun delete(id:String){onMain();if(!busy&&canCapture())schedule{entries->entries.filter{it.id!=id}}}
    fun item(id:String):SavedClip?{
        onMain();if(!canCapture()||busy)return null
        return items.firstOrNull{it.id==id&&ClipboardHistory.live(it,System.currentTimeMillis())}
    }
    private fun schedule(transform:(List<SavedClip>)->List<SavedClip>){
        val g=generation.get();if(!authorized(g)||busy)return
        val job=++token;busyToken=job
        worker.execute{
            val result=runCatching{repository.update(System.currentTimeMillis(),{authorized(g)},transform)}
            main.post{
                if(closed||busyToken!=job)return@post
                busyToken=null
                if(authorized(g))result.fold({items=ClipboardHistory.prune(it,System.currentTimeMillis());message=null},{blocked=true;pending.clear();items=emptyList();message="History unavailable. Existing encrypted data was preserved; retry or clear."})
                if(authorized(generation.get())){if(pending.isNotEmpty())drain()else if(g!=generation.get())refresh()}
                changed();armExpiry()
            }
        }
        changed()
    }
    override fun close(){onMain();closed=true;generation.incrementAndGet();pending.clear();items=emptyList();main.removeCallbacks(expiry);worker.shutdown()}
    companion object{
        private const val ENABLED="clipboardHistoryEnabled"
        private const val ERASE="clipboardHistoryErasePending"
        private const val TOKEN="clipboardHistoryConsentToken"
        private const val FILE="clipboard-history-v1"
        fun sensitive(description:ClipDescription?)=description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE,false)==true
        fun plainText(clipboard:ClipboardManager,notBefore:Long=Long.MIN_VALUE):String?{
            val description=clipboard.primaryClipDescription?:return null
            if(sensitive(description)||description.timestamp<notBefore||!description.hasMimeType("text/plain"))return null
            val clip=clipboard.primaryClip?:return null
            if(clip.itemCount!=1||sensitive(clip.description)||clip.description.timestamp<notBefore||!clip.description.hasMimeType("text/plain"))return null
            val item=clip.getItemAt(0)
            if(item.uri!=null||item.intent!=null)return null
            val text=item.text?:return null
            if(text.length>ClipboardHistory.MAX_CHARS)return null
            return text.toString().takeIf(ClipboardHistory::admissible)
        }
    }
}
