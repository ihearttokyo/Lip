package dev.lip.keyboard

import android.content.ClipDescription
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import dev.lip.stickers.StickerLibraryRevision
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One explicit request for the selected original. REQUESTED means the local API
 * returned true, not ingress, insertion or sending. The receiver controls those.
 * Cancellation revokes future access; it cannot recall admitted FDs/copied bytes.
 */
internal class ProductionStickerAttachmentController(context:Context,private val session:ImeSession,
    private val current:()->ProductionStickerAttachContext?,private val changed:()->Unit,
    private val feedback:(Outcome)->Unit):AutoCloseable {
    enum class Outcome { REQUESTED, NOT_CONFIRMED, UNAVAILABLE }
    private class Operation(val request:StickerHandoffRequest,val initial:ProductionStickerAttachContext,
        val admission:ProductionStickerOperationAdmission.Ticket) {
        val live=AtomicBoolean(true)
        val leaseAccess=AtomicBoolean(true)
        val fence=StickerDispatchFence()
        val decided=CountDownLatch(1)
        @Volatile var issued:ProductionStickerTransport.Issued?=null
        @Volatile var outcome=Outcome.UNAVAILABLE
        @Volatile var mayReport=true
    }
    companion object {
        // Process-wide admission spans actual store return AND actual commitContent
        // return. Destroying an IME/controller never frees an in-flight operation.
        private val admission=ProductionStickerOperationAdmission()
        private val worker=ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,ArrayBlockingQueue(1),
            {r->Thread(r,"lip-sticker-original-request").apply{isDaemon=true}}).apply{allowCoreThreadTimeOut(true)}
    }
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val owner=StickerHandoffOwner(session)
    private var operation:Operation?=null
    @Volatile private var retainedIssued:ProductionStickerTransport.Issued?=null
    private var closed=false
    private val notificationPending=AtomicBoolean(false)
    private val leaseChanged:()->Unit={
        if(notificationPending.compareAndSet(false,true)){
            if(!main.post{notificationPending.set(false);if(!closed)changed()})notificationPending.set(false)
        }
        Unit
    }
    init { ProductionStickerTransport.observe(leaseChanged) }
    val busy:Boolean get()=operation!=null||admission.busy()||ProductionStickerTransport.isActive()
    private fun mainThread(){check(Looper.myLooper()==Looper.getMainLooper())}
    fun cancel() {
        mainThread()
        val op=operation
        if(op!=null){
            op.live.set(false);op.leaseAccess.set(false);op.admission.cancel();op.mayReport=false;owner.cancel(op.request)
            if(op.fence.cancel())op.decided.countDown()
            op.issued?.let{ProductionStickerTransport.revoke(it)}
        }
        retainedIssued?.let{ProductionStickerTransport.revoke(it)}
        if(!closed)changed()
    }
    fun request(asset:StickerAssetIdentity,expected:ProductionStickerAttachContext?=null,contactCurrent:()->Boolean={true}) {
        mainThread()
        if(closed||busy||!ProductionStickerTransport.enabled(app))return
        val initial=current()?:return
        if(!initial.safety.eligible||!StickerLibraryRevision.isCurrent(initial.libraryEpoch)||
            (expected!=null&&!initial.matches(expected))||!runCatching{contactCurrent()}.getOrDefault(false))return
        val request=owner.begin(asset,initial.cursor,initial.connection,initial.window,initial.receiver.binding,initial.mimeTypes(),initial.safety.composing)?:return
        val slot=admission.begin()
        if(slot==null){owner.cancel(request);feedback(Outcome.UNAVAILABLE);return}
        val op=Operation(request,initial,slot);operation=op;changed()
        try{worker.execute{prepare(op)}}catch(_:java.util.concurrent.RejectedExecutionException){
            op.live.set(false);slot.cancel();op.fence.cancel();owner.cancel(request);admission.finish(slot);operation=null
            changed();feedback(Outcome.UNAVAILABLE)
        }
    }
    private fun live(op:Operation):Boolean=op.live.get()&&op.admission.isLive()&&StickerLibraryRevision.isCurrent(op.initial.libraryEpoch)
    /** Never turn interruption/timeout into proof that an admitted external call returned. */
    private fun awaitActualDispatch(op:Operation) {
        var interrupted=false
        while(op.decided.count!=0L){try{op.decided.await()}catch(_:InterruptedException){interrupted=true;op.live.set(false);op.admission.cancel()}}
        if(interrupted)Thread.currentThread().interrupt()
    }
    private fun prepare(op:Operation) {
        try {
            if(!live(op))return
            val payload=ProductionStickerOriginalLoader.read(app,op.request.asset,op.initial.libraryEpoch){live(op)}
            if(!live(op)||!op.initial.receiver.isCurrent(app))return
            val access=op.leaseAccess
            val issued=ProductionStickerTransport.issue(app,payload.asset,op.initial.receiver,payload.copyBytes(),op.initial.libraryEpoch,{access.get()}){published->
                op.issued=published;retainedIssued=published
                if(!live(op))ProductionStickerTransport.revoke(published)
            }
            if(!live(op))return
            if(!main.post{dispatch(op,issued)}){
                op.live.set(false);op.admission.cancel();op.fence.cancel();op.decided.countDown()
            }
            try {
                if(!op.decided.await(10,TimeUnit.SECONDS)){
                    op.live.set(false);op.admission.cancel()
                    if(op.fence.cancel())op.decided.countDown()
                }
            }catch(_:InterruptedException){
                op.live.set(false);op.admission.cancel()
                if(op.fence.cancel())op.decided.countDown()
                // Actual-dispatch wait below is deliberately uninterruptible.
            }
        }catch(_:Throwable){
            // An invocation already admitted is ambiguous even if a local bridge
            // throws. Never convert this into "nothing sent" or retry/fallback.
            if(op.fence.state!=StickerDispatchFence.State.DISPATCHING)op.outcome=Outcome.UNAVAILABLE
        }finally {
            if(op.fence.state==StickerDispatchFence.State.DISPATCHING)awaitActualDispatch(op)
            op.issued?.let { issued->
                if(!live(op)||op.fence.state!=StickerDispatchFence.State.DISPATCHING){op.leaseAccess.set(false);ProductionStickerTransport.revoke(issued)}
                else if(op.outcome!=Outcome.REQUESTED)ProductionStickerTransport.revokeUnopened(issued)
            }
            op.live.set(false);op.admission.cancel();admission.finish(op.admission)
            // Pending UI cleanup is not an in-flight store/IPC; owner admission
            // still refuses another request until this exact main completion.
            main.post {
                owner.cancel(op.request)
                if(operation===op){
                    operation=null
                    if(!closed){
                        changed()
                        val here=runCatching{current()}.getOrNull()
                        if(op.mayReport&&session.editorGeneration()==op.request.ticket.epoch&&
                            here!=null&&here.connection===op.request.connection&&here.window===op.request.window)feedback(op.outcome)
                    }
                }
            }
        }
    }
    private fun dispatch(op:Operation,issued:ProductionStickerTransport.Issued) {
        mainThread()
        try {
            if(closed||operation!==op||!live(op)||!ProductionStickerTransport.enabled(app))return
            if(!op.initial.receiver.isCurrent(app))return // External PM BEFORE the final service snapshot.
            val now=current()?:return
            if(!now.safety.eligible||now.safety!=op.initial.safety||now.libraryEpoch!==op.initial.libraryEpoch||
                now.receiver!=op.initial.receiver||!StickerLibraryRevision.isCurrent(now.libraryEpoch))return
            val content=InputContentInfo(issued.uri,ClipDescription("Local sticker original",arrayOf(op.request.asset.mime)),null)
            if(!owner.claim(op.request,op.request.asset,now.cursor,now.connection,now.window,now.receiver.binding,now.mimeTypes(),now.safety.composing))return
            if(!live(op)||!op.fence.beginDispatch())return
            op.outcome=Outcome.NOT_CONFIRMED // Published BEFORE the reentrant external invocation.
            val accepted=try{now.connection.commitContent(content,InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null)}catch(_:Throwable){false}
            op.outcome=if(accepted)Outcome.REQUESTED else Outcome.NOT_CONFIRMED
            val after=runCatching{current()}.getOrNull()
            if(after!=null)StickerDispatchRevision.finish(session,op.request,after.cursor,after.connection,after.window)
        }catch(_:Throwable){
            if(op.fence.state==StickerDispatchFence.State.DISPATCHING)op.outcome=Outcome.NOT_CONFIRMED
        }finally{op.decided.countDown()}
    }
    override fun close() {
        mainThread();if(closed)return
        closed=true;ProductionStickerTransport.unobserve(leaseChanged);cancel()
        // Process executor/admission is intentionally not shut down by a view owner.
    }
}
