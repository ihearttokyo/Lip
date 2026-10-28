package dev.lip.stickers

import android.content.Context
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class StickerPreparationException(val reason:Reason):IllegalStateException("Sticker preparation refused: ${reason.name}.") {
    enum class Reason { BUSY, CANCELED, LIMIT }
}

/** Local preparation only. No storage, activation, URI grants, UI or redistribution. */
internal class StickerPackPreparer(context:Context) {
    private val context=context.applicationContext
    fun prepare(archive:ByteArray,live:()->Boolean={true}):PreparedStickerPack {
        check(Looper.myLooper()!=Looper.getMainLooper()){ "Sticker preparation cannot block the main thread" }
        // Admission precedes archive copying/inspection, not merely the first decode.
        if(!admitted.compareAndSet(false,true))throw StickerPreparationException(StickerPreparationException.Reason.BUSY)
        fun current()= !Thread.currentThread().isInterrupted && try{live()}catch(_:Exception){false}
        fun checkLive(){if(!current())throw StickerPreparationException(StickerPreparationException.Reason.CANCELED)}
        try {
            checkLive()
            if(archive.size>StickerArchiveReader.MAX_SOURCE_BYTES)throw StickerPreparationException(StickerPreparationException.Reason.LIMIT)
            val snapshot=archive.copyOf()
            val inspected=StickerPackInspector.inspect(snapshot.inputStream(),::current)
            checkLive()
            val members=buildList {
                inspected.trayImage?.let{add(it.member)}
                inspected.stickers.forEach{add(it.member)}
            }
            val previews=ArrayList<SandboxedStickerPreview>(members.size)
            var rgbaBytes=0L
            StickerDecoderSession(context).use { decoder ->
                for(member in members) {
                    checkLive()
                    val preview=decoder.decode(member,::current)
                    rgbaBytes+=preview.byteCount
                    if(rgbaBytes>StickerPixelContract.MAX_PACK_RGBA_BYTES)throw StickerPreparationException(StickerPreparationException.Reason.LIMIT)
                    previews.add(preview)
                }
            }
            checkLive()
            // The constructor independently binds copied archive, inspection and pixels.
            val prepared=PreparedStickerPack(snapshot,inspected,previews)
            checkLive()
            return prepared
        } finally { admitted.set(false) }
    }
    companion object { private val admitted=AtomicBoolean(false) }
}
