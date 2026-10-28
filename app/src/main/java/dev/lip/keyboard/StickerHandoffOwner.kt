package dev.lip.keyboard

import java.util.Collections
import java.util.Locale
import java.util.UUID

/** Exact immutable original-member identity, never an archive/filesystem path or rights grant. */
internal class StickerAssetIdentity(val commitId:String,val archiveSha256:String,val stickerId:String,
    val memberSha256:String,mime:String,val byteLength:Int) {
    val mime:String=mime.lowercase(Locale.ROOT)
    init {
        require(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(commitId)&&
            runCatching{UUID.fromString(commitId).toString()==commitId}.getOrDefault(false)){"Invalid sticker asset identity."}
        require(Regex("[a-f0-9]{64}").matches(archiveSha256)&&Regex("[a-f0-9]{64}").matches(memberSha256)&&
            Regex("[a-z0-9](?:[a-z0-9_-]{0,62}[a-z0-9])?").matches(stickerId)&&
            this.mime in setOf("image/png","image/webp")&&byteLength in 1..8*1024*1024){"Invalid sticker asset identity."}
    }
    override fun equals(other:Any?):Boolean=other is StickerAssetIdentity&&commitId==other.commitId&&
        archiveSha256==other.archiveSha256&&stickerId==other.stickerId&&memberSha256==other.memberSha256&&mime==other.mime&&byteLength==other.byteLength
    override fun hashCode():Int = listOf(commitId,archiveSha256,stickerId,memberSha256,mime,byteLength).hashCode()
    override fun toString()="StickerAssetIdentity(redacted)"
}

/** Caller must obtain the actual receiver UID/package independently; this type is not authorization. */
internal class StickerReceiverBinding(val packageName:String,val uid:Int) {
    init {
        require(packageName.length<=255&&Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName)&&uid>0){"Invalid sticker receiver binding."}
    }
    override fun equals(other:Any?):Boolean=other is StickerReceiverBinding&&packageName==other.packageName&&uid==other.uid
    override fun hashCode():Int=31*packageName.hashCode()+uid
    override fun toString()="StickerReceiverBinding(redacted)"
}

internal object StickerHandoffMime {
    private const val TOKEN="[a-z0-9][a-z0-9!#\u0024&^_.+-]*"
    private val advertisement=Regex("(?:$TOKEN/$TOKEN|$TOKEN/\\*|\\*/\\*)")
    /** Case only is equivalent. Whitespace, parameters and encoded aliases are never repaired. */
    internal fun snapshot(types:Array<String>?):Set<String>? {
        if(types.isNullOrEmpty())return null
        val normalized=LinkedHashSet<String>()
        for(type in types){
            val value=type.lowercase(Locale.ROOT)
            if(!advertisement.matches(value))return null
            normalized.add(value)
        }
        return Collections.unmodifiableSet(normalized)
    }
    internal fun matches(original:String,types:Set<String>):Boolean = original in setOf("image/png","image/webp")&&
        types.any{it==original||it=="image/*"||it=="*/*"}
    fun accepts(originalMime:String,types:Array<String>?):Boolean = snapshot(types)?.let{matches(originalMime.lowercase(Locale.ROOT),it)}==true
}

internal class StickerHandoffRequest internal constructor(val asset:StickerAssetIdentity,val receiver:StickerReceiverBinding,
    internal val ticket:ImeTicket,internal val connection:Any,internal val window:Any,internal val advertised:Set<String>) {
    override fun toString()="StickerHandoffRequest(redacted)"
}

/** Pure pending-request policy only: no URI, resource lease, Android permission or dispatch authority.
 * Changed MIME capabilities conservatively require a fresh user gesture, even when
 * both old/new capability sets would separately accept the original MIME.
 */
internal class StickerHandoffOwner(private val session:ImeSession) {
    @Volatile var current:StickerHandoffRequest?=null
        private set
    @Synchronized fun begin(asset:StickerAssetIdentity,cursor:ImeCursor?,connection:Any?,window:Any?,
        receiver:StickerReceiverBinding?,mimeTypes:Array<String>?,composing:Boolean):StickerHandoffRequest? {
        if(current!=null||composing||connection==null||window==null||receiver==null||cursor?.packageName!=receiver.packageName)return null
        val advertised=StickerHandoffMime.snapshot(mimeTypes)?:return null
        if(!StickerHandoffMime.matches(asset.mime,advertised))return null
        val ticket=session.capture(cursor)?:return null
        return StickerHandoffRequest(asset,receiver,ticket,connection,window,advertised).also{current=it}
    }
    @Synchronized fun cancel():Boolean {
        if(current==null)return false
        current=null;return true
    }
    @Synchronized fun cancel(request:StickerHandoffRequest):Boolean {
        if(current!==request)return false
        current=null;return true
    }
    @Synchronized fun claim(request:StickerHandoffRequest,asset:StickerAssetIdentity,cursor:ImeCursor?,connection:Any?,window:Any?,
        receiver:StickerReceiverBinding?,mimeTypes:Array<String>?,composing:Boolean):Boolean {
        if(current!==request)return false // Stale completion cannot consume a newer request.
        current=null // Exact request is consumed even on any later validation failure.
        if(composing||asset!=request.asset||connection!==request.connection||window!==request.window||
            receiver!=request.receiver||cursor?.packageName!=receiver?.packageName)return false
        val advertised=StickerHandoffMime.snapshot(mimeTypes)?:return false
        if(advertised!=request.advertised||!StickerHandoffMime.matches(asset.mime,advertised))return false
        return session.claim(request.ticket,cursor)
    }
    override fun toString()="StickerHandoffOwner(redacted)"
}
