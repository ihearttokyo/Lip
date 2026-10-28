package dev.lip.keyboard

import android.content.Context
import android.os.Looper
import dev.lip.stickers.StickerLibraryRevision
import dev.lip.stickers.StickerPackTransactionStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** New production adapter. The old fixture-gated loader/read barrier is unchanged.
 * Validates the bounded stored pack, then retains only the exact selected original.
 * No encoded-image decoder, external path, network or new store file is involved.
 */
internal object ProductionStickerOriginalLoader {
    private fun refusal():Nothing=throw StickerOriginalException(StickerOriginalException.Reason.UNAVAILABLE)
    fun read(context:Context,expected:StickerAssetIdentity,epoch:Any,live:()->Boolean):StickerOriginalPayload {
        check(Looper.myLooper()!=Looper.getMainLooper()){"Sticker original read must be off main."}
        val app=context.applicationContext
        fun current()=try{!Thread.currentThread().isInterrupted&&live()&&StickerLibraryRevision.isCurrent(epoch)&&ProductionStickerTransport.enabled(app)}catch(_:Exception){false}
        fun checkLive(){if(!current())throw StickerOriginalException(StickerOriginalException.Reason.CANCELED)}
        try{
            checkLive()
            val parent=app.noBackupFilesDir.toPath().toRealPath();val root=parent.resolve("sticker-packs-v1")
            val attributes=Files.readAttributes(root,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            if(!attributes.isDirectory||attributes.isSymbolicLink||root.toRealPath().parent!=parent)refusal()
            checkLive()
            val pack=StickerPackTransactionStore(root).read(expected.commitId,::current)
            checkLive();if(pack.archiveSha256!=expected.archiveSha256)refusal()
            val sticker=pack.inspected.stickers.singleOrNull{it.id==expected.stickerId}?:refusal()
            val actual=StickerAssetIdentity(expected.commitId,pack.archiveSha256,sticker.id,sticker.member.sha256,sticker.metadata.mime,sticker.member.size)
            if(actual!=expected)refusal()
            checkLive();val bytes=sticker.member.bytes();checkLive()
            return StickerOriginalPayload(actual,bytes).also{checkLive()}
        }catch(e:StickerOriginalException){throw e}catch(_:Exception){refusal()}
    }
}
