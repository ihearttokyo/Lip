package dev.lip.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.CancellationSignal
import android.os.Looper
import dev.lip.stickers.StickerLibraryRevision
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

/** One selected original capability, not a library/path endpoint. Caller grants
 * and package identity are rechecked on every metadata/read entry. Existing remote
 * FDs/copied bytes cannot be recalled. Registry/permissions are independent fences.
 */
internal object ProductionStickerTransport {
    private val lock=Any()
    private val registry=StickerLeaseRegistry(clockMs={SystemClock.elapsedRealtime()})
    private val timers=ScheduledThreadPoolExecutor(1){r->Thread(r,"lip-sticker-lease-monitor").apply{isDaemon=true}}.apply{removeOnCancelPolicy=true}
    private var active:Issued?=null
    private var lastWriterExited=true
    private var revocationsHealthy=true
    internal class Issued internal constructor(internal val context:Context,internal val lease:StickerLeaseRegistry.Lease,val receiver:ProductionStickerReceiver,val uri:Uri,internal val libraryEpoch:Any,internal val accessAllowed:()->Boolean){
        @Volatile internal var expiry:ScheduledFuture<*>?=null
        @Volatile internal var monitor:ScheduledFuture<*>?=null
        @Volatile internal var writer:Thread?=null
        @Volatile internal var opened=false
        @Volatile internal var writerClosed=true
        @Volatile internal var quarantined=false
        @Volatile internal var retainedRead:ParcelFileDescriptor?=null
        @Volatile internal var retainedWrite:ParcelFileDescriptor?=null
        internal val publication=ProductionStickerGrantPublication()
        internal val monitorAdmitted=AtomicBoolean(false)
        internal val monitored=AtomicBoolean(false)
        internal val revocation=StickerGrantRevocation{
            ownerCall{
                context.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                check(context.checkUriPermission(uri,-1,receiver.facts.uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED){"Sticker grant retirement unconfirmed."}
            }
        }
        internal val bytesWritten=AtomicLong(0)
        override fun toString()="StickerTransportIssued(redacted)"
    }
    internal data class Snapshot(val active:Boolean,val writerAlive:Boolean,val bytesWritten:Long,val queuedTasks:Int,val lastWriterExited:Boolean,val revocationCallSucceeded:Boolean)
    private fun refusal():Nothing=throw FileNotFoundException("Local sticker attachment unavailable.")
    private inline fun <T> ownerCall(block:()->T):T {
        val identity=Binder.clearCallingIdentity()
        try{return block()}finally{Binder.restoreCallingIdentity(identity)}
    }
    private fun environmentAllowed(context:Context):Boolean=runCatching{
        context.getSystemService(android.os.PowerManager::class.java).isInteractive&&
            !context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
    }.getOrDefault(false)
    private fun accessible(issued:Issued):Boolean=runCatching{
        issued.accessAllowed()&&StickerLibraryRevision.isCurrent(issued.libraryEpoch)&&environmentAllowed(issued.context)
    }.getOrDefault(false)
    internal fun enabled(context:Context):Boolean=runCatching{ownerCall{
        val app=context.applicationContext
        val provider=app.packageManager.getProviderInfo(ComponentName(app,ProductionStickerContentProvider::class.java),
            PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
        provider.enabled&&!provider.exported&&provider.grantUriPermissions&&provider.authority==app.packageName+".sticker-originals"&&
            provider.applicationInfo.uid==android.os.Process.myUid()&&provider.processName==app.applicationInfo.processName
    }}.getOrDefault(false)
    fun issue(context:Context,asset:StickerAssetIdentity,target:ProductionStickerReceiver,bytes:ByteArray,libraryEpoch:Any,accessAllowed:()->Boolean,published:(Issued)->Unit):Issued {
        check(Looper.myLooper()!=Looper.getMainLooper()){"Sticker issue must be off main."}
        val app=context.applicationContext
        if(!enabled(app)||!target.isCurrent(app)||!environmentAllowed(app)||!StickerLibraryRevision.isCurrent(libraryEpoch)||!accessAllowed())refusal()
        val issued=synchronized(lock){
            if(active!=null||!revocationsHealthy)refusal()
            val lease=registry.issue(asset,target.binding,bytes)
            Issued(app,lease,target,Uri.Builder().scheme("content").authority(app.packageName+".sticker-originals").appendPath("v1").appendPath(lease.token).build(),libraryEpoch,accessAllowed).also{active=it;lastWriterExited=true}
        }
        // Capability is published before the external grant. Android/PM callbacks
        // never run under the registry monitor; failures retire or quarantine it.
        try{
            published(issued) // Retained cancellation owner exists BEFORE any external grant.
            issued.expiry=timers.schedule({revoke(issued)},120_000,TimeUnit.MILLISECONDS)
            if(issued.publication.retirementRequested()||!accessible(issued))refusal()
            app.grantUriPermission(target.facts.packageName,issued.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if(!accessible(issued)||!target.isCurrent(app)||app.checkUriPermission(issued.uri,-1,target.facts.uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED)refusal()
        }catch(t:Throwable){revoke(issued);throw t}finally{
            // A cancel/expiry while grantUriPermission is in flight first revokes
            // the byte registry, then waits here to retire the possible late grant.
            if(issued.publication.finishGrant())revokeGrant(issued)
        }
        if(issued.publication.retirementRequested())refusal()
        notifyChanged();return issued
    }
    private fun token(context:Context,uri:Uri):String {
        if(!enabled(context)||uri.scheme!="content"||uri.encodedAuthority!=context.packageName+".sticker-originals"||uri.query!=null||uri.fragment!=null||
            !Regex("/v1/[a-f0-9]{80}").matches(uri.encodedPath.orEmpty()))refusal()
        return uri.encodedPath!!.substring(4)
    }
    private fun caller(context:Context,uri:Uri):Pair<Issued,String> {
        val token=token(context,uri)
        val issued=synchronized(lock){active?.takeIf{it.lease.token==token&&!it.quarantined}?:refusal()}
        val uid=Binder.getCallingUid();val pid=Binder.getCallingPid()
        if(uid!=issued.receiver.facts.uid)refusal()
        val allowed=ownerCall {
            accessible(issued)&&issued.receiver.isCurrent(context)&&
                context.checkUriPermission(uri,pid,uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED&&
                issued.receiver.isCurrent(context)&&accessible(issued)
        }
        if(!allowed){revoke(issued);refusal()}
        return issued to token
    }
    internal fun describe(context:Context,uri:Uri):StickerLeaseMetadata {
        val(issued,token)=caller(context,uri)
        return try{registry.describe(token,issued.receiver.binding,true)}catch(_:Exception){refusal()}
    }
    internal fun open(context:Context,uri:Uri,mode:String,cancellation:CancellationSignal?):ParcelFileDescriptor {
        if(mode!="r")refusal()
        cancellation?.throwIfCanceled()
        val(issued,token)=caller(context,uri)
        val handle:StickerLeaseRegistry.ReadHandle
        synchronized(lock){
            if(active!==issued||issued.quarantined)refusal()
            handle=try{registry.open(token,issued.receiver.binding,true)}catch(_:Exception){refusal()}
            issued.opened=true
        }
        var pipe:Array<ParcelFileDescriptor>?=null
        try{
            pipe=ParcelFileDescriptor.createReliablePipe()
            val read=pipe[0];val write=pipe[1];issued.retainedRead=read;issued.retainedWrite=write;issued.writerClosed=false
            val flags=Os.fcntlInt(write.fileDescriptor,OsConstants.F_GETFL,0)
            Os.fcntlInt(write.fileDescriptor,OsConstants.F_SETFL,flags or OsConstants.O_NONBLOCK)
            // Task/closures are allocated under THIS try before thread start. Any
            // setup failure here closes both pipe ends in the catch below. Once
            // started, even the first buffer/clock failure is inside task cleanup.
            val task=ProductionStickerWriterTask({writePipe(issued,handle,write)},{successful->
                if(successful)write.close() else write.closeWithError("Local sticker read ended.")
                issued.writerClosed=true;issued.retainedWrite=null
            },{issued.writerClosed=false;issued.quarantined=true})
            val thread=Thread(task,"lip-sticker-private-pipe").apply{isDaemon=true}
            issued.writer=thread
            synchronized(lock){lastWriterExited=false}
            cancellation?.setOnCancelListener{revoke(issued)}
            thread.start()
            monitor(issued,handle,thread)
            issued.retainedRead=null // Binder/return caller now owns the read side.
            return read
        }catch(_:Throwable){
            val writer=issued.writer
            if(writer==null||writer.state==Thread.State.NEW){
                var closed=true
                pipe?.forEach{try{it.close()}catch(_:Throwable){closed=false}}
                issued.writerClosed=closed
                if(!closed)issued.quarantined=true
                registry.finish(handle);synchronized(lock){lastWriterExited=true};settled(issued)
            }else{
                try{pipe?.get(0)?.close();issued.retainedRead=null}catch(_:Throwable){issued.quarantined=true}
                registry.revoke(issued.lease)
                try{monitor(issued,handle,writer)}catch(_:Throwable){issued.quarantined=true}
                revokeGrant(issued)
            }
            refusal()
        }
    }
    private fun writePipe(issued:Issued,handle:StickerLeaseRegistry.ReadHandle,out:ParcelFileDescriptor):Boolean {
        // Called only by ProductionStickerWriterTask: even this first allocation
        // is already protected by its reliable-FD close/error finally.
        val buffer=ByteArray(32*1024);var successful=false
        try{
            val started=SystemClock.elapsedRealtime()
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
            return successful
        }finally{buffer.fill(0)}
        // Capacity is NOT released here: monitor must observe the actual thread dead.
    }
    private fun monitor(issued:Issued,handle:StickerLeaseRegistry.ReadHandle,thread:Thread){
        if(!issued.monitorAdmitted.compareAndSet(false,true))return
        val future=try{timers.scheduleWithFixedDelay({
            if(thread.state!=Thread.State.NEW&&!thread.isAlive&&issued.monitored.compareAndSet(false,true)){
                registry.finish(handle);synchronized(lock){lastWriterExited=true};settled(issued)
                issued.monitor?.cancel(false)
            }
        },25,25,TimeUnit.MILLISECONDS)}catch(t:Throwable){issued.quarantined=true;registry.revoke(issued.lease);throw t}
        issued.monitor=future
        if(issued.monitored.get())future.cancel(false)
    }
    private fun revokeGrant(issued:Issued){
        if(!issued.publication.requestRetirement())return
        val state=issued.revocation.request()
        if(state==StickerGrantRevocation.State.UNCONFIRMED)synchronized(lock){revocationsHealthy=false}
        clearIfSettled(issued)
    }
    private fun clearIfSettled(issued:Issued){
        synchronized(lock){
            // Pending/failed OS revocation cannot become clean idle or admit another lease.
            if(active===issued&&issued.revocation.state==StickerGrantRevocation.State.SUCCEEDED&&
                !registry.isActive()&&issued.writer?.isAlive!=true&&issued.writerClosed&&!issued.quarantined){
                issued.expiry?.cancel(false);active=null
            }
        }
    }
    private fun settled(issued:Issued){revokeGrant(issued);notifyChanged()}
    /** Explicit API refusal may retire only a capability that no receiver opened. */
    fun revokeUnopened(issued:Issued):Boolean {
        synchronized(lock){
            if(active!==issued||issued.opened)return false
            registry.revoke(issued.lease)
        }
        revokeGrant(issued);clearIfSettled(issued);notifyChanged();return true
    }
    fun revoke(issued:Issued):Boolean {
        synchronized(lock){if(active!==issued)return false;registry.revoke(issued.lease)}
        revokeGrant(issued)
        clearIfSettled(issued)
        notifyChanged();return true
    }
    fun isActive():Boolean=synchronized(lock){active!=null||registry.isActive()}
    private val listeners=java.util.concurrent.CopyOnWriteArraySet<()->Unit>()
    fun observe(listener:()->Unit){listeners.add(listener)}
    fun unobserve(listener:()->Unit){listeners.remove(listener)}
    private fun notifyChanged(){listeners.forEach{runCatching{it()}}}
    fun snapshot():Snapshot=synchronized(lock){
        Snapshot(active!=null||registry.isActive(),active?.writer?.isAlive==true,active?.bytesWritten?.get()?:0,timers.queue.size,lastWriterExited,
            revocationsHealthy&&(active==null||active?.revocation?.state==StickerGrantRevocation.State.SUCCEEDED))
    }
}
