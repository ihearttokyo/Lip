package dev.lip.stickers

class StickerDecodeException(val reason:Reason):IllegalStateException("Sticker decode refused: ${reason.name}."){
    enum class Reason{FORMAT,LIMIT,CODEC,RESOURCE,STATE,INTEGRITY,CANCELED,DEADLINE,WORKER,BUSY}
}
internal object StickerPixelContract{
    const val EDGE=256
    const val MAX_RGBA_BYTES=EDGE*EDGE*4
    const val MAX_PACK_RGBA_BYTES=32*1024*1024
    fun target(width:Int,height:Int):Pair<Int,Int>{
        require(width in 1..4096&&height in 1..4096)
        val edge=maxOf(width,height)
        if(edge<=EDGE)return width to height
        fun scaled(value:Int)=((value.toLong()*EDGE+edge/2)/edge).toInt().coerceIn(1,value)
        return scaled(width) to scaled(height)
    }
    fun byteCount(width:Int,height:Int):Int{
        if(width !in 1..EDGE||height !in 1..EDGE)throw StickerDecodeException(StickerDecodeException.Reason.LIMIT)
        return width*height*4
    }
    fun validate(expected:StickerImageMetadata,sourceWidth:Int,sourceHeight:Int,width:Int,height:Int,frames:Int,durationMs:Long,bytes:Int){
        if(expected.width!=sourceWidth||expected.height!=sourceHeight||frames!=expected.frames||durationMs!=expected.durationMs)throw StickerDecodeException(StickerDecodeException.Reason.INTEGRITY)
        if(target(sourceWidth,sourceHeight)!=(width to height)||byteCount(width,height)!=bytes)throw StickerDecodeException(StickerDecodeException.Reason.INTEGRITY)
    }
}
/** Only decoded pixels cross from the isolated codec; original encoded payload stays untrusted. */
class SandboxedStickerPreview internal constructor(val sourceSha256:String,val metadata:StickerImageMetadata,
    val width:Int,val height:Int,val workerPid:Int,val workerUid:Int,val observedPeakRssKb:Long,rgba:ByteArray){
    private val payload=rgba.copyOf()
    val byteCount:Int get()=payload.size
    val rights=InspectedStickerPack.Rights.UNVERIFIED
    fun premultipliedRgbaSrgbBytes():ByteArray=payload.copyOf()
}
internal object StickerDecodeWire{
    const val TOKEN="dev.lip.stickers.IIsolatedDecoder.v1"
    const val HELLO=1
    const val DECODE=2
    const val VERSION=1
    const val WORKER_DEADLINE_MS=8_000L
    const val CLIENT_DEADLINE_MS=10_000L
    const val BIND_DEADLINE_MS=3_000L
}
