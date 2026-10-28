package dev.lip.stickers

import android.app.Service
import android.content.Intent
import android.os.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** No UI, permissions, filesystem source or inherited app UID. Untrusted codecs live here only. */
class StickerDecodeService:Service(){
    private val main=Handler(Looper.getMainLooper())
    private val active=AtomicReference<String?>(null)
    private fun isolated()=Process.isIsolated()&&Process.myUid()!=applicationInfo.uid
    private fun terminateOwnWorker(){if(isolated())Process.killProcess(Process.myPid())}
    private fun result(reply:Parcel,code:Int,id:String,sha:String,native:NativeStickerCodecResult?=null){
        reply.writeInt(StickerDecodeWire.VERSION);reply.writeInt(code);reply.writeString(id);reply.writeString(sha)
        reply.writeInt(Process.myPid());reply.writeInt(Process.myUid());reply.writeInt(if(isolated())1 else 0)
        reply.writeInt(native?.sourceWidth?:0);reply.writeInt(native?.sourceHeight?:0);reply.writeInt(native?.width?:0);reply.writeInt(native?.height?:0)
        reply.writeInt(native?.frames?:0);reply.writeLong(native?.durationMs?:0);reply.writeLong(native?.peakRssKb?:0)
        val rgba=if(code==0)native?.rgba?:byteArrayOf() else byteArrayOf()
        reply.writeInt(rgba.size);reply.writeByteArray(rgba)
    }
    private val binder=object:Binder(){
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean{
            if(code==INTERFACE_TRANSACTION){reply?.writeString(StickerDecodeWire.TOKEN);return true}
            if(code !in setOf(StickerDecodeWire.HELLO,StickerDecodeWire.DECODE))return super.onTransact(code,data,reply,flags)
            if(reply==null||flags and IBinder.FLAG_ONEWAY!=0||!isolated()||Binder.getCallingUid()!=applicationInfo.uid)throw SecurityException("Private isolated decoder only")
            data.enforceInterface(StickerDecodeWire.TOKEN)
            if(code==StickerDecodeWire.HELLO){
                if(data.dataAvail()!=0)throw IllegalArgumentException("Decoder framing")
                reply.writeInt(StickerDecodeWire.VERSION);reply.writeInt(Process.myPid());reply.writeInt(Process.myUid());reply.writeInt(1);reply.writeInt(if(active.get()!=null)1 else 0);return true
            }
            var descriptor:ParcelFileDescriptor?=null;var id="";var sha="";var accepted=false
            var watchdog:Runnable?=null
            try{
                id=data.readString()?:"";sha=data.readString()?:""
                descriptor=ParcelFileDescriptor.CREATOR.createFromParcel(data)
                if(data.dataAvail()!=0||UUID.fromString(id).toString()!=id||!Regex("[a-f0-9]{64}").matches(sha)){result(reply,1,id,sha);return true}
                if(!active.compareAndSet(null,id)){result(reply,6,id,sha);return true};accepted=true
                val deadline=SystemClock.elapsedRealtime()+StickerDecodeWire.WORKER_DEADLINE_MS
                watchdog=Runnable{if(active.compareAndSet(id,null))terminateOwnWorker()};main.postDelayed(watchdog,StickerDecodeWire.WORKER_DEADLINE_MS)
                val source=ParcelFileDescriptor.AutoCloseInputStream(descriptor);descriptor=null
                val bytes=source.use{input->
                    val output=ByteArrayOutputStream();val buffer=ByteArray(32*1024)
                    while(true){
                        if(SystemClock.elapsedRealtime()>=deadline)throw StickerDecodeException(StickerDecodeException.Reason.DEADLINE)
                        val n=input.read(buffer,0,minOf(buffer.size,StickerImageProbe.MAX_SOURCE_BYTES-output.size()+1))
                        if(n<0)break
                        if(n==0||n>StickerImageProbe.MAX_SOURCE_BYTES-output.size())throw StickerDecodeException(StickerDecodeException.Reason.LIMIT)
                        output.write(buffer,0,n)
                    };output.toByteArray()
                }
                val observed=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
                if(observed!=sha){result(reply,7,id,sha);return true}
                val metadata=StickerImageProbe.inspect(bytes){SystemClock.elapsedRealtime()<deadline}
                val decoded=NativeStickerCodec.decode(bytes)
                if(decoded.code!=0){result(reply,decoded.code,id,sha);return true}
                StickerPixelContract.validate(metadata,decoded.sourceWidth,decoded.sourceHeight,decoded.width,decoded.height,decoded.frames,decoded.durationMs,decoded.rgba.size)
                if(SystemClock.elapsedRealtime()>=deadline){result(reply,8,id,sha);return true}
                result(reply,0,id,sha,decoded)
            }catch(e:StickerImageException){result(reply,if(e.reason==StickerImageException.Reason.LIMIT)2 else 1,id,sha)}
            catch(e:StickerDecodeException){result(reply,when(e.reason){StickerDecodeException.Reason.LIMIT->2;StickerDecodeException.Reason.INTEGRITY->7;StickerDecodeException.Reason.DEADLINE->8;else->5},id,sha)}
            catch(_:OutOfMemoryError){result(reply,4,id,sha)}
            catch(_:LinkageError){result(reply,5,id,sha)}
            catch(_:Exception){result(reply,1,id,sha)}
            finally{runCatching{descriptor?.close()};if(accepted){active.compareAndSet(id,null);watchdog?.let{main.removeCallbacks(it)}}}
            return true
        }
    }
    override fun onCreate(){super.onCreate();check(isolated()){ "Decoder must run in an isolated process" }}
    override fun onBind(intent:Intent):IBinder=binder
    override fun onUnbind(intent:Intent):Boolean{if(active.get()!=null)terminateOwnWorker();return false}
    override fun onDestroy(){super.onDestroy();terminateOwnWorker()}
}
