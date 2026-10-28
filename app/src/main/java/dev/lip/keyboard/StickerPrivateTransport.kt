package dev.lip.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileNotFoundException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Prototype transport ONLY: exact owned emulator receiver. No keyboard action or external route. */
internal object StickerPrivateTransport {
    const val APP="dev.lip.android.sizingfixture"
    const val RECEIVER="$APP.test"
    private val lock=Any()
    private val registry=StickerLeaseRegistry(clockMs={SystemClock.elapsedRealtime()})
    private val timers=ScheduledThreadPoolExecutor(1){r->Thread(r,"lip-sticker-lease-monitor").apply{isDaemon=true}}.apply{removeOnCancelPolicy=true}
    private var active:Issued?=null
    private var lastWriterExited=true
    private var revocationsHealthy=true
    internal class Issued internal constructor(internal val context:Context,internal val lease:StickerLeaseRegistry.Lease,val uri:Uri){
        @Volatile internal var expiry:ScheduledFuture<*>?=null
        @Volatile internal var monitor:ScheduledFuture<*>?=null
        @Volatile internal var writer:Thread?=null
        @Volatile internal var opened=false
        internal val monitorAdmitted=AtomicBoolean(false)
        internal val monitored=AtomicBoolean(false)
        internal val revocation=StickerGrantRevocation{
            context.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        internal val bytesWritten=AtomicLong(0)
        override fun toString()="StickerTransportIssued(redacted)"
    }
    internal data class Snapshot(val active:Boolean,val writerAlive:Boolean,val bytesWritten:Long,val queuedTasks:Int,val lastWriterExited:Boolean,val revocationCallSucceeded:Boolean)
    private fun refusal():Nothing=throw FileNotFoundException("Private sticker handoff unavailable.")
    internal fun enabled(context:Context):Boolean=runCatching{
        context.packageName==APP&&android.os.Build.HARDWARE=="ranchu"&&StickerBrowserFeature.enabled(context)&&
            context.packageManager.getProviderInfo(ComponentName(context,StickerContentProvider::class.java),
                PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong())).enabled
    }.getOrDefault(false)
    internal fun receiver(context:Context):StickerReceiverBinding {
        if(!enabled(context))refusal()
        val pm=context.packageManager
        val info=pm.getApplicationInfo(RECEIVER,PackageManager.ApplicationInfoFlags.of(0))
        if(info.uid==android.os.Process.myUid()||pm.checkSignatures(context.packageName,RECEIVER)!=PackageManager.SIGNATURE_MATCH||
            pm.getPackagesForUid(info.uid)?.toSet()!=setOf(RECEIVER))refusal()
        return StickerReceiverBinding(RECEIVER,info.uid)
    }
    fun issue(context:Context,asset:StickerAssetIdentity,target:StickerReceiverBinding,bytes:ByteArray):Issued {
        check(Looper.myLooper()!=Looper.getMainLooper()){"Sticker issue must be off main."}
        if(target!=receiver(context))refusal()
        return synchronized(lock){
            if(active!=null||!revocationsHealthy)refusal()
            val lease=registry.issue(asset,target,bytes)
            val issued=Issued(context.applicationContext,lease,Uri.Builder().scheme("content").authority(context.packageName+".sticker-handoff").appendPath("v1").appendPath(lease.token).build())
            active=issued;lastWriterExited=true
            try{issued.expiry=timers.schedule({revoke(issued)},120_000,TimeUnit.MILLISECONDS)}catch(t:Throwable){registry.revoke(lease);active=null;throw t}
            issued
        }
    }
    private fun token(context:Context,uri:Uri):String {
        if(!enabled(context)||uri.scheme!="content"||uri.encodedAuthority!=context.packageName+".sticker-handoff"||uri.query!=null||uri.fragment!=null||
            !Regex("/v1/[a-f0-9]{80}").matches(uri.encodedPath.orEmpty()))refusal()
        return uri.encodedPath!!.substring(4)
    }
    private fun caller(context:Context,uri:Uri):StickerReceiverBinding {
        val target=receiver(context)
        val uid=Binder.getCallingUid()
        if(uid!=target.uid||context.checkUriPermission(uri,Binder.getCallingPid(),uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED)refusal()
        return target
    }
    internal fun describe(context:Context,uri:Uri):StickerLeaseMetadata {
        val token=token(context,uri);val target=caller(context,uri)
        return try{registry.describe(token,target,true)}catch(_:Exception){refusal()}
    }
    internal fun open(context:Context,uri:Uri,mode:String,cancellation:CancellationSignal?):ParcelFileDescriptor {
        if(mode!="r")refusal()
        cancellation?.throwIfCanceled()
        val token=token(context,uri);val target=caller(context,uri)
        val issued:Issued;val handle:StickerLeaseRegistry.ReadHandle
        synchronized(lock){
            issued=active?.takeIf{it.lease.token==token}?:refusal()
            handle=try{registry.open(token,target,true)}catch(_:Exception){refusal()}
            issued.opened=true
        }
        var pipe:Array<ParcelFileDescriptor>?=null
        try{
            pipe=ParcelFileDescriptor.createReliablePipe()
            val read=pipe[0];val write=pipe[1]
            val flags=Os.fcntlInt(write.fileDescriptor,OsConstants.F_GETFL,0)
            Os.fcntlInt(write.fileDescriptor,OsConstants.F_SETFL,flags or OsConstants.O_NONBLOCK)
            val thread=Thread({writePipe(issued,handle,write)},"lip-sticker-private-pipe").apply{isDaemon=true}
            issued.writer=thread
            synchronized(lock){lastWriterExited=false}
            cancellation?.setOnCancelListener{revoke(issued)}
            thread.start()
            monitor(issued,handle,thread)
            return read
        }catch(_:Throwable){
            val writer=issued.writer
            if(writer==null||writer.state==Thread.State.NEW){
                pipe?.forEach{runCatching{it.close()}};registry.finish(handle);synchronized(lock){lastWriterExited=true};settled(issued)
            }else{
                pipe?.get(0)?.let{runCatching{it.close()}};registry.revoke(issued.lease)
                monitor(issued,handle,writer)
            }
            refusal()
        }
    }
    private fun writePipe(issued:Issued,handle:StickerLeaseRegistry.ReadHandle,out:ParcelFileDescriptor){
        val buffer=ByteArray(32*1024);var successful=false
        val started=SystemClock.elapsedRealtime()
        try{
            while(registry.isLive(handle)&&SystemClock.elapsedRealtime()-started<8_000){
                val size=registry.readNext(handle,buffer)
                if(size<0){successful=true;break}
                var offset=0
                while(offset<size){
                    if(!registry.isLive(handle)||SystemClock.elapsedRealtime()-started>=8_000)throw java.io.IOException("Read ended.")
                    try{
                        val count=Os.write(out.fileDescriptor,buffer,offset,size-offset)
                        if(count<=0)throw java.io.IOException("Read ended.")
                        offset+=count;issued.bytesWritten.addAndGet(count.toLong())
                    }catch(e:ErrnoException){
                        if(e.errno!=OsConstants.EAGAIN&&e.errno!=OsConstants.EINTR)throw e
                        val poll=StructPollfd().apply{fd=out.fileDescriptor;events=OsConstants.POLLOUT.toShort()}
                        try{Os.poll(arrayOf(poll),100)}catch(p:ErrnoException){if(p.errno!=OsConstants.EINTR)throw p}
                    }
                }
            }
        }catch(_:Throwable){}finally{
            buffer.fill(0)
            if(successful)runCatching{out.close()} else runCatching{out.closeWithError("Local sticker read ended.")}
            // Capacity is NOT released here: monitor must observe this actual thread dead.
        }
    }
    private fun monitor(issued:Issued,handle:StickerLeaseRegistry.ReadHandle,thread:Thread){
        if(!issued.monitorAdmitted.compareAndSet(false,true))return
        val future=timers.scheduleWithFixedDelay({
            if(!thread.isAlive&&issued.monitored.compareAndSet(false,true)){
                registry.finish(handle);synchronized(lock){lastWriterExited=true};settled(issued)
                issued.monitor?.cancel(false)
            }
        },25,25,TimeUnit.MILLISECONDS)
        issued.monitor=future
        if(issued.monitored.get())future.cancel(false)
    }
    private fun revokeGrant(issued:Issued){
        val state=issued.revocation.request()
        if(state==StickerGrantRevocation.State.UNCONFIRMED)synchronized(lock){revocationsHealthy=false}
        clearIfSettled(issued)
    }
    private fun clearIfSettled(issued:Issued){
        synchronized(lock){
            // Pending/failed OS revocation cannot become clean idle or admit another lease.
            if(active===issued&&issued.revocation.state==StickerGrantRevocation.State.SUCCEEDED&&
                !registry.isActive()&&issued.writer?.isAlive!=true){
                issued.expiry?.cancel(false);active=null
            }
        }
    }
    private fun settled(issued:Issued){revokeGrant(issued)}
    /** Explicit API refusal may retire only a capability that no receiver opened. */
    fun revokeUnopened(issued:Issued):Boolean {
        synchronized(lock){
            if(active!==issued||issued.opened)return false
            registry.revoke(issued.lease)
        }
        revokeGrant(issued);clearIfSettled(issued);return true
    }
    fun revoke(issued:Issued):Boolean {
        synchronized(lock){if(active!==issued)return false;registry.revoke(issued.lease)}
        revokeGrant(issued)
        clearIfSettled(issued)
        return true
    }
    fun snapshot():Snapshot=synchronized(lock){
        Snapshot(active!=null||registry.isActive(),active?.writer?.isAlive==true,active?.bytesWritten?.get()?:0,timers.queue.size,lastWriterExited,
            revocationsHealthy&&active?.revocation?.state!=StickerGrantRevocation.State.PENDING)
    }
}
