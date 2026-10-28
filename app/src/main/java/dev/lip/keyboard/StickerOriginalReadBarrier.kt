package dev.lip.keyboard

import android.content.Context
import android.os.Looper
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Internal, owned-emulator control only. No IPC, UI, payload, permission or dispatch endpoint. */
internal object StickerOriginalReadBarrier {
    private val policy=StickerOriginalReadBarrierPolicy()
    fun arm(context:Context,expected:StickerAssetIdentity,owner:String):StickerOriginalReadBarrierPolicy.Handle {
        check(StickerPrivateTransport.enabled(context.applicationContext)){"Read barrier unavailable."}
        return policy.arm(expected,owner)
    }
    fun snapshot(handle:StickerOriginalReadBarrierPolicy.Handle)=policy.snapshot(handle)
    /** Gated read-only witness: no handle, owner, asset, token, context or payload. */
    fun observation(context:Context):StickerOriginalReadBarrierPolicy.Snapshot?=
        if(StickerPrivateTransport.enabled(context.applicationContext))policy.activeSnapshot() else null
    fun cancel(handle:StickerOriginalReadBarrierPolicy.Handle)=policy.cancel(handle)
    fun release(handle:StickerOriginalReadBarrierPolicy.Handle)=policy.release(handle)
    fun forceReleaseFinally(handle:StickerOriginalReadBarrierPolicy.Handle)=policy.forceReleaseFinally(handle)
    /** Does not join/interrupt the controller-owned reusable executor thread or prove controller completion. */
    fun awaitLoaderDrained(handle:StickerOriginalReadBarrierPolicy.Handle,timeoutMs:Long):Boolean {
        check(Looper.myLooper()!=Looper.getMainLooper()){ "Read barrier wait unavailable." }
        return policy.awaitLoaderDrained(handle,timeoutMs)
    }
    fun retire(handle:StickerOriginalReadBarrierPolicy.Handle)=policy.retire(handle)
    internal fun claim(expected:StickerAssetIdentity)=policy.claim(expected)
    internal fun storeLive(handle:StickerOriginalReadBarrierPolicy.Handle,live:()->Boolean)=policy.storeLive(handle,live)
    internal fun loaderAdmissionReleased(handle:StickerOriginalReadBarrierPolicy.Handle)=policy.loaderAdmissionReleased(handle)
}

/** Pure ownership/wait policy; only the object's gated instance is wired into the real loader. */
internal class StickerOriginalReadBarrierPolicy(private val maximumWaitMs:Long=60_000) {
    internal class Handle internal constructor(internal val asset:StickerAssetIdentity) {
        internal var claimed=false
        internal var entered=false
        internal var callbackExited=false
        internal var admissionReleased=false
        internal var canceledPredicate=false
        internal var cancelRequested=false
        internal var released=false
        internal var timedOut=false
        internal var interrupted=false
        internal var invocations=0
        internal var worker:Thread?=null
        override fun toString()="StickerOriginalReadBarrierHandle(redacted)"
    }
    internal data class Snapshot(val active:Boolean,val claimed:Boolean,val entered:Boolean,
        val workerThreadAlive:Boolean,val canceledPredicateObserved:Boolean,val cancelRequested:Boolean,
        val released:Boolean,val callbackExited:Boolean,val loaderAdmissionReleased:Boolean,
        val storeLiveInvocations:Int,val timedOut:Boolean,val interrupted:Boolean)
    private val lock=Object()
    private var active:Handle?=null
    init {require(maximumWaitMs in 1..60_000){"Read barrier unavailable."}}
    private fun refusal():Nothing=throw IllegalStateException("Read barrier unavailable.")
    private fun exact(handle:Handle){if(active!==handle)refusal()}
    private fun known(asset:StickerAssetIdentity)=
        asset.commitId=="8cc8db1d-6255-40df-90f4-815329caa03d"&&
        asset.archiveSha256=="fbb2e463bb624120710566fd8877c49a3c0c045500e562b680739ef3f10cf1ec"&&
        asset.stickerId=="red"&&asset.mime=="image/png"&&asset.byteLength==78&&
        asset.memberSha256=="df75ecf5f620d125e80ae77bf639cecad76878216cdafb71afef124b04bbf5dc"
    fun arm(expected:StickerAssetIdentity,owner:String):Handle=synchronized(lock){
        if(active!=null||!known(expected)||!runCatching{UUID.fromString(owner).toString()==owner}.getOrDefault(false))refusal()
        Handle(expected).also{active=it}
    }
    internal fun claim(expected:StickerAssetIdentity):Handle?=synchronized(lock){
        active?.takeIf{!it.claimed&&!it.released&&it.asset==expected}?.also{
            it.claimed=true;it.worker=Thread.currentThread()
        }
    }
    fun snapshot(handle:Handle):Snapshot=synchronized(lock){
        Snapshot(active===handle,handle.claimed,handle.entered,handle.worker?.isAlive==true,
            handle.canceledPredicate,handle.cancelRequested,handle.released,handle.callbackExited,
            handle.admissionReleased,handle.invocations,handle.timedOut,handle.interrupted)
    }
    fun activeSnapshot():Snapshot?=synchronized(lock){active?.let{snapshot(it)}}
    fun cancel(handle:Handle)=synchronized(lock){exact(handle);handle.cancelRequested=true;lock.notifyAll()}
    fun release(handle:Handle)=synchronized(lock){exact(handle);handle.released=true;lock.notifyAll()}
    fun forceReleaseFinally(handle:Handle)=synchronized(lock){
        exact(handle);handle.cancelRequested=true;handle.released=true;lock.notifyAll()
    }
    /** Called ONLY inside the live callback passed to the actual transaction store read. */
    internal fun storeLive(handle:Handle,live:()->Boolean):Boolean {
        val first=synchronized(lock){
            exact(handle)
            if(!handle.claimed||handle.worker!==Thread.currentThread()||handle.admissionReleased)refusal()
            handle.invocations++
            if(handle.entered)false else {handle.entered=true;lock.notifyAll();true}
        }
        if(!first)return evaluate(handle,live)
        val started=System.nanoTime()
        var restoreInterrupt=false
        try {
            while(true){
                // Do not hold our state lock while invoking the real caller's liveness predicate.
                val current=runCatching{live()}.getOrDefault(false)
                synchronized(lock){
                    exact(handle)
                    if(!current)handle.canceledPredicate=true
                    if(System.nanoTime()-started>=TimeUnit.MILLISECONDS.toNanos(maximumWaitMs)){
                        handle.timedOut=true;handle.cancelRequested=true;handle.released=true
                    }
                    if(handle.released)return current&&!handle.cancelRequested&&!handle.canceledPredicate
                    try{lock.wait(25)}catch(_:InterruptedException){
                        restoreInterrupt=true;handle.interrupted=true;handle.cancelRequested=true;handle.released=true
                    }
                }
            }
        }finally{
            synchronized(lock){handle.callbackExited=true;lock.notifyAll()}
            if(restoreInterrupt)Thread.currentThread().interrupt()
        }
    }
    private fun evaluate(handle:Handle,live:()->Boolean):Boolean {
        val current=runCatching{live()}.getOrDefault(false)
        return synchronized(lock){
            exact(handle)
            if(!current)handle.canceledPredicate=true
            current&&!handle.cancelRequested&&!handle.canceledPredicate
        }
    }
    internal fun loaderAdmissionReleased(handle:Handle)=synchronized(lock){
        exact(handle)
        if(!handle.claimed||handle.worker!==Thread.currentThread())refusal()
        handle.admissionReleased=true;lock.notifyAll()
    }
    private fun drained(handle:Handle)=if(handle.claimed)handle.admissionReleased&&(!handle.entered||handle.callbackExited) else handle.released
    fun awaitLoaderDrained(handle:Handle,timeoutMs:Long):Boolean {
        require(timeoutMs in 0..125_000){"Read barrier wait unavailable."}
        val start=System.nanoTime();val budget=TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(lock){
            exact(handle)
            while(!drained(handle)){
                val remaining=budget-(System.nanoTime()-start)
                if(remaining<=0)return false
                try{TimeUnit.NANOSECONDS.timedWait(lock,remaining)}catch(_:InterruptedException){Thread.currentThread().interrupt();return false}
            }
            return true
        }
    }
    /** Caller must also establish controller task/transport drain; this only retires the loader barrier. */
    fun retire(handle:Handle):Boolean=synchronized(lock){
        if(active!==handle||!drained(handle))return false
        active=null;true
    }
    override fun toString()="StickerOriginalReadBarrierPolicy(redacted)"
}
