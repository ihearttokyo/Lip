package dev.lip.keyboard

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

internal class StickerLeaseException(val reason:Reason):IllegalStateException("Private sticker lease unavailable.") {
    enum class Reason { BUSY, INVALID, INTEGRITY, UNAVAILABLE, CLOCK, LIMIT }
}
internal data class StickerLeaseMetadata(val mime:String,val byteLength:Int)

/** One selected original in private memory. No Android grant, pipe, timer, writer or dispatch is created here. */
internal class StickerLeaseRegistry(
    private val clockMs:()->Long={System.nanoTime()/1_000_000L},
    private val randomBytes:(ByteArray)->Unit={secureRandom.nextBytes(it)},
) {
    class Lease internal constructor(val token:String) { override fun toString()="StickerLease(redacted)" }
    class ReadHandle internal constructor() { override fun toString()="StickerReadHandle(redacted)" }
    private class Entry(val lease:Lease,val asset:StickerAssetIdentity,val receiver:StickerReceiverBinding,
        val payload:ByteArray,val expiresAt:Long) {
        var valid=true
        var handle:ReadHandle?=null
        var offset=0
    }
    private var entry:Entry?=null
    private var lastObserved:Long?=null
    private var clockFailed=false
    private var issuing=false
    companion object {
        const val TTL_MS=120_000L
        const val MAX_CHUNK_BYTES=32_768
        private val secureRandom=SecureRandom()
        // Process-wide, never reset with a registry. Randomness still supplies the secret256 bits.
        private val nonce=AtomicLong(0)
        private val tokenPattern=Regex("[a-f0-9]{80}")
        private const val HEX="0123456789abcdef"
    }
    private fun fail(reason:StickerLeaseException.Reason):Nothing=throw StickerLeaseException(reason)
    private fun invalidate(current:Entry){
        current.valid=false
        if(current.handle==null&&entry===current)entry=null
    }
    private fun poisonClock(){clockFailed=true;entry?.let(::invalidate)}
    /** Negative origins are valid; only elapsed ordering matters. Rollback/clock exception poisons permanently. */
    private fun refresh():Long? {
        if(clockFailed)return null
        val now=try{clockMs()}catch(_:Exception){poisonClock();return null}
        val prior=lastObserved
        if(prior!=null&&now<prior){poisonClock();return null}
        lastObserved=now
        entry?.let{if(now>=it.expiresAt)invalidate(it)}
        return now
    }
    private fun nextNonce():Long {
        while(true){val prior=nonce.get();if(prior==Long.MAX_VALUE)fail(StickerLeaseException.Reason.LIMIT)
            if(nonce.compareAndSet(prior,prior+1))return prior+1}
    }
    private fun nextToken():String {
        val random=ByteArray(32)
        try{randomBytes(random)}catch(_:Exception){fail(StickerLeaseException.Reason.UNAVAILABLE)}
        val number=nextNonce()
        val out=CharArray(80)
        for(i in random.indices){val value=random[i].toInt() and 255;out[2*i]=HEX[value ushr 4];out[2*i+1]=HEX[value and 15]}
        for(i in 0 until 16)out[64+i]=HEX[((number ushr ((15-i)*4)) and 15).toInt()]
        return String(out)
    }
    private fun sameToken(candidate:String,expected:String):Boolean=tokenPattern.matches(candidate)&&
        MessageDigest.isEqual(candidate.toByteArray(Charsets.US_ASCII),expected.toByteArray(Charsets.US_ASCII))
    private fun access(token:String,receiver:StickerReceiverBinding,hasReadGrant:Boolean):Entry {
        val current=entry?:fail(StickerLeaseException.Reason.UNAVAILABLE)
        // Invalid callers cannot consume or mutate the rightful lease, including its clock observations.
        if(!hasReadGrant||receiver!=current.receiver||!sameToken(token,current.lease.token))fail(StickerLeaseException.Reason.UNAVAILABLE)
        refresh()
        if(entry!==current||!current.valid)fail(StickerLeaseException.Reason.UNAVAILABLE)
        return current
    }
    private fun live(handle:ReadHandle):Entry {
        val current=entry?:fail(StickerLeaseException.Reason.UNAVAILABLE)
        if(current.handle!==handle)fail(StickerLeaseException.Reason.UNAVAILABLE)
        refresh()
        if(entry!==current||!current.valid)fail(StickerLeaseException.Reason.UNAVAILABLE)
        return current
    }
    @Synchronized fun issue(asset:StickerAssetIdentity,receiver:StickerReceiverBinding,bytes:ByteArray):Lease {
        if(issuing)fail(StickerLeaseException.Reason.BUSY)
        issuing=true
        try {
            val now=refresh()
            // An invalid opened entry is still resource capacity until the actual writer calls finish.
            if(entry!=null)fail(StickerLeaseException.Reason.BUSY)
            if(now==null)fail(StickerLeaseException.Reason.CLOCK)
            if(now>Long.MAX_VALUE-TTL_MS)fail(StickerLeaseException.Reason.LIMIT)
            if(bytes.size!=asset.byteLength||bytes.size !in 1..8*1024*1024)fail(StickerLeaseException.Reason.INVALID)
            val privateCopy=bytes.copyOf() // Validate the private snapshot, never the caller's mutable array.
            val digest=MessageDigest.getInstance("SHA-256").digest(privateCopy).joinToString(""){"%02x".format(it.toInt() and 255)}
            if(digest!=asset.memberSha256)fail(StickerLeaseException.Reason.INTEGRITY)
            val lease=Lease(nextToken())
            // Validation/randomness can take time. TTL begins only at final issue admission.
            val issuedAt=refresh()?:fail(StickerLeaseException.Reason.CLOCK)
            if(issuedAt>Long.MAX_VALUE-TTL_MS)fail(StickerLeaseException.Reason.LIMIT)
            entry=Entry(lease,asset,receiver,privateCopy,issuedAt+TTL_MS)
            return lease
        } finally { issuing=false }
    }
    @Synchronized fun describe(token:String,receiver:StickerReceiverBinding,hasReadGrant:Boolean):StickerLeaseMetadata =
        access(token,receiver,hasReadGrant).let{StickerLeaseMetadata(it.asset.mime,it.payload.size)}
    @Synchronized fun open(token:String,receiver:StickerReceiverBinding,hasReadGrant:Boolean):ReadHandle {
        val current=access(token,receiver,hasReadGrant)
        if(current.handle!=null)fail(StickerLeaseException.Reason.UNAVAILABLE)
        return ReadHandle().also{current.handle=it}
    }
    @Synchronized fun isLive(handle:ReadHandle):Boolean {
        val current=entry?:return false
        if(current.handle!==handle)return false
        refresh();return entry===current&&current.valid
    }
    @Synchronized fun readNext(handle:ReadHandle,buffer:ByteArray):Int {
        val current=live(handle)
        if(buffer.size !in 1..MAX_CHUNK_BYTES)fail(StickerLeaseException.Reason.INVALID)
        if(current.offset==current.payload.size)return -1 // EOF never releases actual-writer admission.
        val count=minOf(buffer.size,current.payload.size-current.offset)
        current.payload.copyInto(buffer,0,current.offset,current.offset+count);current.offset+=count
        return count
    }
    /** Exact writer completion, not cancellation/EOF, releases opened capacity. Clock independent. */
    @Synchronized fun finish(handle:ReadHandle):Boolean {
        val current=entry?:return false
        if(current.handle!==handle)return false
        current.valid=false;entry=null;return true
    }
    @Synchronized fun revoke(lease:Lease):Boolean {
        val current=entry?:return false
        if(current.lease!==lease)return false
        refresh()
        if(entry===current)invalidate(current)
        return true
    }
    /** Includes revoked/expired/clock-poisoned opened entries still draining. */
    @Synchronized fun isActive():Boolean {refresh();return issuing||entry!=null}
    override fun toString()="StickerLeaseRegistry(redacted)"
}
