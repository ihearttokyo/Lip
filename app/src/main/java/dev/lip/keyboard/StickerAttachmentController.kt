package dev.lip.keyboard

import android.content.ClipDescription
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Main-thread snapshot. Context exists only in memory and is never rendered/logged. */
internal class StickerAttachContext(val connection:InputConnection,val cursor:ImeCursor,val window:Any,
    val receiver:StickerReceiverBinding,mimeTypes:Array<String>?,val composing:Boolean){
    private val types=mimeTypes?.copyOf()
    fun mimeTypes()=types?.copyOf()
    override fun toString()="StickerAttachContext(redacted)"
}

/** One explicit original-image request; fixture-only. A true API return is NOT editor ingress or sending. */
internal class StickerAttachmentController(context:Context,private val session:ImeSession,
    private val current:()->StickerAttachContext?,private val changed:()->Unit,private val feedback:(Outcome)->Unit):AutoCloseable {
    enum class Outcome { REQUESTED, NOT_CONFIRMED, UNAVAILABLE }
    private class Operation(val request:StickerHandoffRequest){
        val live=AtomicBoolean(true)
        val fence=StickerDispatchFence()
        val decided=CountDownLatch(1)
        @Volatile var refused=false
        @Volatile var outcome=Outcome.UNAVAILABLE
        @Volatile var mayReport=false
    }
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val worker=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),{r->Thread(r,"lip-sticker-original-request").apply{isDaemon=true}})
    private val owner=StickerHandoffOwner(session)
    private var operation:Operation?=null
    private var closed=false
    private val reads=AtomicInteger(0)
    private var dispatches=0
    val busy get()=operation!=null
    internal fun debugState()=Triple(busy,reads.get(),dispatches) // No asset, context, token, text or paths.
    private fun mainThread(){check(Looper.myLooper()==Looper.getMainLooper())}
    fun cancel(){
        mainThread();val op=operation?:return
        op.live.set(false);op.mayReport=false;owner.cancel(op.request)
        if(op.fence.cancel())op.decided.countDown()
        if(!closed)changed()
    }
    fun request(asset:StickerAssetIdentity){
        mainThread()
        if(closed||operation!=null||!StickerPrivateTransport.enabled(app))return
        val target=current()?:return
        if(target.receiver!=runCatching{StickerPrivateTransport.receiver(app)}.getOrNull())return
        val request=owner.begin(asset,target.cursor,target.connection,target.window,target.receiver,target.mimeTypes(),target.composing)?:return
        val op=Operation(request).apply{mayReport=true};operation=op;changed()
        try{worker.execute{prepare(op)}}catch(_:java.util.concurrent.RejectedExecutionException){
            op.live.set(false);op.fence.cancel();owner.cancel(request);operation=null
            changed();feedback(Outcome.UNAVAILABLE)
        }
    }
    private fun prepare(op:Operation){
        reads.incrementAndGet()
        var issued:StickerPrivateTransport.Issued?=null
        try{
            if(!op.live.get())return
            val payload=StickerOriginalLoader.read(app,op.request.asset){op.live.get()}
            if(!op.live.get())return
            issued=StickerPrivateTransport.issue(app,payload.asset,op.request.receiver,payload.copyBytes())
            val selected=issued
            if(!main.post{dispatch(op,selected)}){op.live.set(false);op.fence.cancel();op.decided.countDown()}
            if(!op.decided.await(10,TimeUnit.SECONDS)){
                op.live.set(false)
                if(op.fence.cancel())op.decided.countDown()
                // A started API call must really return; timeout cannot pretend it drained.
                if(op.fence.state==StickerDispatchFence.State.DISPATCHING)op.decided.await()
            }
        }catch(_:Throwable){
            op.outcome=Outcome.UNAVAILABLE
        }finally{
            try{
                issued?.let{
                    if(op.fence.state!=StickerDispatchFence.State.DISPATCHING)StickerPrivateTransport.revoke(it)
                    else if(op.refused)StickerPrivateTransport.revokeUnopened(it)
                }
            }finally{
                reads.decrementAndGet();op.live.set(false)
                main.post{
                    owner.cancel(op.request)
                    if(operation===op){
                        operation=null
                        if(!closed){
                            changed()
                            val stillHere=session.editorGeneration()==op.request.ticket.epoch&&current()?.let{it.connection===op.request.connection&&it.window===op.request.window}==true
                            if(op.mayReport&&stillHere)feedback(op.outcome)
                        }
                    }
                }
            }
        }
    }
    private fun dispatch(op:Operation,issued:StickerPrivateTransport.Issued){
        mainThread()
        try{
            if(closed||operation!==op||!op.live.get()||!StickerPrivateTransport.enabled(app))return
            val now=current()?:return
            if(now.receiver!=runCatching{StickerPrivateTransport.receiver(app)}.getOrNull())return
            if(!owner.claim(op.request,op.request.asset,now.cursor,now.connection,now.window,now.receiver,now.mimeTypes(),now.composing))return
            if(!op.fence.beginDispatch())return
            op.mayReport=true;dispatches++
            // No finishComposingText: any active composition is refused, never mutated as preparation.
            val content=InputContentInfo(issued.uri,ClipDescription("Local sticker original",arrayOf(op.request.asset.mime)),null)
            val accepted=runCatching{now.connection.commitContent(content,InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null)}.getOrDefault(false)
            op.refused=!accepted;op.outcome=if(accepted)Outcome.REQUESTED else Outcome.NOT_CONFIRMED
            // Reentrant field/window replacement must never revise the new editor.
            val after=current()
            if(after!=null)StickerDispatchRevision.finish(session,op.request,after.cursor,after.connection,after.window)
        }finally{op.decided.countDown()}
    }
    override fun close(){mainThread();if(closed)return;closed=true;cancel();worker.shutdown()}
}
