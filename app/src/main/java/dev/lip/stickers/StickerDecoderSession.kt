package dev.lip.stickers

import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*

/** Serial, off-main session. Cancel closes the session; no retry, retarget or fallback. */
class StickerDecoderSession(context:Context):AutoCloseable{
    private val context=context.applicationContext
    private val lifecycleLock=Any()
    private val closed=AtomicBoolean(false)
    private val reserved=AtomicBoolean(false)
    private val draining=AtomicInteger(0)
    private val connected=CountDownLatch(1)
    private val remote=AtomicReference<IBinder?>(null)
    private val current=AtomicReference<Call?>(null)
    private val bound=AtomicBoolean(false)
    private class Call(val read:ParcelFileDescriptor,val write:ParcelFileDescriptor){
        val done=CountDownLatch(1);val result=AtomicReference<SandboxedStickerPreview?>(null);val failure=AtomicReference<StickerDecodeException?>(null)
        fun closeFds(){runCatching{read.close()};runCatching{write.close()}}
    }
    private fun fail(reason:StickerDecodeException.Reason):Nothing=throw StickerDecodeException(reason)
    private val connection=object:ServiceConnection{
        override fun onServiceConnected(name:ComponentName,binder:IBinder){
            if(name!=ComponentName(context,StickerDecodeService::class.java)||closed.get())return
            remote.set(binder);connected.countDown()
        }
        override fun onServiceDisconnected(name:ComponentName){remote.set(null);current.get()?.let{it.failure.compareAndSet(null,StickerDecodeException(StickerDecodeException.Reason.WORKER));it.done.countDown()};close();connected.countDown()}
        override fun onBindingDied(name:ComponentName)=onServiceDisconnected(name)
        override fun onNullBinding(name:ComponentName)=onServiceDisconnected(name)
    }
    init{
        check(Looper.myLooper()!=Looper.getMainLooper()){ "Sticker decode cannot block the main thread" }
        if(!capacity.compareAndSet(false,true))fail(StickerDecodeException.Reason.BUSY)
        reserved.set(true);draining.incrementAndGet()
        try{
            val component=ComponentName(this.context,StickerDecodeService::class.java)
            @Suppress("DEPRECATION") val info=this.context.packageManager.getServiceInfo(component,0)
            check(!info.exported&&info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS!=0&&info.applicationInfo.uid==Process.myUid())
            val didBind=this.context.bindService(Intent().setComponent(component),connection,Context.BIND_AUTO_CREATE)
            val lateUnbind=synchronized(lifecycleLock){if(closed.get())didBind else{bound.set(didBind);false}}
            if(lateUnbind)runCatching{this.context.unbindService(connection)}
            if(!didBind||closed.get()||!connected.await(StickerDecodeWire.BIND_DEADLINE_MS,TimeUnit.MILLISECONDS)||remote.get()==null)fail(StickerDecodeException.Reason.WORKER)
        }catch(e:StickerDecodeException){close();throw e}catch(_:Exception){runCatching{this.context.unbindService(connection)};close();fail(StickerDecodeException.Reason.WORKER)}
        finally{draining.decrementAndGet();releaseIfDrained()}
    }
    private fun releaseIfDrained(){synchronized(lifecycleLock){if(closed.get()&&draining.get()==0&&reserved.compareAndSet(true,false))capacity.set(false)}}
    private fun runTracked(name:String,action:()->Unit){
        draining.incrementAndGet()
        try{Thread({try{action()}finally{draining.decrementAndGet();releaseIfDrained()}},name).apply{isDaemon=true;start()}}
        catch(_:Throwable){draining.decrementAndGet();releaseIfDrained();throw StickerDecodeException(StickerDecodeException.Reason.RESOURCE)}
    }
    fun decode(member:StickerArchiveMember,live:()->Boolean={true}):SandboxedStickerPreview{
        check(Looper.myLooper()!=Looper.getMainLooper()){ "Sticker decode cannot block the main thread" }
        fun liveNow()=try{live()}catch(_:Exception){false}
        if(closed.get())fail(StickerDecodeException.Reason.STATE)
        val pair=ParcelFileDescriptor.createReliablePipe();val call=Call(pair[0],pair[1])
        synchronized(lifecycleLock){
            if(closed.get()){call.closeFds();fail(StickerDecodeException.Reason.STATE)}
            if(!current.compareAndSet(null,call)){call.closeFds();fail(StickerDecodeException.Reason.BUSY)}
            // Reserve launch admission before close can release global capacity.
            draining.incrementAndGet()
        }
        val id=UUID.randomUUID().toString();val deadline=SystemClock.elapsedRealtime()+StickerDecodeWire.CLIENT_DEADLINE_MS
        try{
            if(!liveNow()||closed.get())fail(StickerDecodeException.Reason.CANCELED)
            val bytes=member.bytes()
            val metadata=try{StickerImageProbe.inspect(bytes){!closed.get()&&liveNow()}}catch(e:StickerImageException){
                fail(when(e.reason){StickerImageException.Reason.CANCELED->StickerDecodeException.Reason.CANCELED;StickerImageException.Reason.LIMIT->StickerDecodeException.Reason.LIMIT;else->StickerDecodeException.Reason.FORMAT})
            }
            if(closed.get())fail(StickerDecodeException.Reason.CANCELED)
            val binder=remote.get()?:fail(StickerDecodeException.Reason.WORKER)
            runTracked("lip-sticker-writer"){
                try{ParcelFileDescriptor.AutoCloseOutputStream(call.write).use{out->
                    var at=0;while(at<bytes.size){if(closed.get()||!liveNow())break;val n=minOf(32*1024,bytes.size-at);out.write(bytes,at,n);at+=n}
                }}catch(_:Exception){}
            }
            runTracked("lip-sticker-ipc"){
                val data=Parcel.obtain();val reply=Parcel.obtain()
                try{
                    data.writeInterfaceToken(StickerDecodeWire.TOKEN);data.writeString(id);data.writeString(member.sha256);call.read.writeToParcel(data,0)
                    if(!binder.transact(StickerDecodeWire.DECODE,data,reply,0))throw StickerDecodeException(StickerDecodeException.Reason.WORKER)
                    call.result.set(readResult(reply,id,member.sha256,metadata))
                }catch(e:StickerDecodeException){call.failure.set(e)}catch(_:Exception){call.failure.set(StickerDecodeException(StickerDecodeException.Reason.WORKER))}
                finally{data.recycle();reply.recycle();call.closeFds();current.compareAndSet(call,null);call.done.countDown()}
            }
            while(!call.done.await(50,TimeUnit.MILLISECONDS)){
                if(closed.get()||!liveNow()){close();fail(StickerDecodeException.Reason.CANCELED)}
                if(SystemClock.elapsedRealtime()>=deadline){close();fail(StickerDecodeException.Reason.DEADLINE)}
            }
            call.failure.get()?.let{throw it}
            if(closed.get()||!liveNow()){close();fail(StickerDecodeException.Reason.CANCELED)}
            return call.result.get()?:fail(StickerDecodeException.Reason.WORKER)
        }catch(e:StickerDecodeException){close();throw e}catch(_:Exception){close();fail(StickerDecodeException.Reason.WORKER)}
        finally{call.closeFds();current.compareAndSet(call,null);draining.decrementAndGet();releaseIfDrained()}
    }
    private fun readResult(reply:Parcel,id:String,sha:String,metadata:StickerImageMetadata):SandboxedStickerPreview{
        if(reply.readInt()!=StickerDecodeWire.VERSION)fail(StickerDecodeException.Reason.STATE)
        val code=reply.readInt();if(reply.readString()!=id||reply.readString()!=sha)fail(StickerDecodeException.Reason.INTEGRITY)
        val pid=reply.readInt();val uid=reply.readInt();val isolated=reply.readInt()==1
        if(pid<=0||pid==Process.myPid()||uid==Process.myUid()||!isolated||(Build.VERSION.SDK_INT>=34&&!Process.isIsolatedUid(uid)))fail(StickerDecodeException.Reason.STATE)
        val sw=reply.readInt();val sh=reply.readInt();val w=reply.readInt();val h=reply.readInt();val frames=reply.readInt();val duration=reply.readLong();val rss=reply.readLong();val length=reply.readInt()
        if(length !in 0..StickerPixelContract.MAX_RGBA_BYTES||reply.dataAvail()<length+4)fail(StickerDecodeException.Reason.LIMIT)
        val rgba=ByteArray(length);reply.readByteArray(rgba);if(reply.dataAvail()!=0)fail(StickerDecodeException.Reason.STATE)
        if(code!=0)fail(when(code){1->StickerDecodeException.Reason.FORMAT;2->StickerDecodeException.Reason.LIMIT;3->StickerDecodeException.Reason.CODEC;4->StickerDecodeException.Reason.RESOURCE;5->StickerDecodeException.Reason.STATE;6->StickerDecodeException.Reason.BUSY;7->StickerDecodeException.Reason.INTEGRITY;8->StickerDecodeException.Reason.DEADLINE;else->StickerDecodeException.Reason.WORKER})
        StickerPixelContract.validate(metadata,sw,sh,w,h,frames,duration,length)
        if(rss<0)fail(StickerDecodeException.Reason.STATE)
        return SandboxedStickerPreview(sha,metadata,w,h,pid,uid,rss,rgba)
    }
    override fun close(){
        val unbind=synchronized(lifecycleLock){
            if(closed.compareAndSet(false,true)){
                current.get()?.let{it.closeFds();it.done.countDown()};remote.set(null)
                bound.compareAndSet(true,false)
            }else false
        }
        if(unbind)runCatching{context.unbindService(connection)}
        releaseIfDrained()
    }
    companion object{private val capacity=AtomicBoolean(false)}
}
