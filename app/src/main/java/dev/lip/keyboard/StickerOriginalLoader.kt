package dev.lip.keyboard

import android.content.Context
import android.os.Looper
import dev.lip.stickers.StickerArchiveException
import dev.lip.stickers.StickerImageException
import dev.lip.stickers.StickerPackException
import dev.lip.stickers.StickerPackTransactionStore
import dev.lip.stickers.StickerStoreException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal class StickerOriginalException(val reason:Reason):IllegalStateException("Original sticker unavailable.") {
    enum class Reason { STATE, BUSY, CANCELED, INTEGRITY, UNAVAILABLE }
}

/** Immutable selected original only. No codec, activation, URI, permission or redistribution proof. */
internal class StickerOriginalPayload(val asset:StickerAssetIdentity,bytes:ByteArray) {
    private val payload:ByteArray
    init {
        // Array length is immutable: reject oversized/mismatched input before allocating.
        if(bytes.size !in 1..8*1024*1024||bytes.size!=asset.byteLength)throw StickerOriginalException(StickerOriginalException.Reason.INTEGRITY)
        payload=bytes.copyOf() // Content validation hashes only this private snapshot.
        val sha=MessageDigest.getInstance("SHA-256").digest(payload).joinToString(""){"%02x".format(it.toInt() and 255)}
        if(sha!=asset.memberSha256)throw StickerOriginalException(StickerOriginalException.Reason.INTEGRITY)
    }
    fun copyBytes():ByteArray=payload.copyOf()
    override fun toString()="StickerOriginalPayload(redacted)"
}

/** Off-main read-only adapter; admission is independent of browser reads and survives cancellation until drain. */
internal object StickerOriginalLoader {
    private val admission=StickerBrowserAdmission()
    private fun fail(reason:StickerOriginalException.Reason):Nothing=throw StickerOriginalException(reason)
    private fun existingRoot(context:Context):Path {
        val parent=context.noBackupFilesDir.toPath().toRealPath()
        val root=parent.resolve("sticker-packs-v1")
        val attrs=Files.readAttributes(root,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        if(!attrs.isDirectory||attrs.isSymbolicLink||root.toRealPath().parent!=parent)fail(StickerOriginalException.Reason.UNAVAILABLE)
        return root
    }
    fun read(context:Context,expected:StickerAssetIdentity,live:()->Boolean={true}):StickerOriginalPayload {
        if(Looper.myLooper()==Looper.getMainLooper())fail(StickerOriginalException.Reason.STATE)
        val app=context.applicationContext
        if(!StickerPrivateTransport.enabled(app))fail(StickerOriginalException.Reason.STATE)
        val ticket=admission.begin()?:fail(StickerOriginalException.Reason.BUSY)
        val barrier=StickerOriginalReadBarrier.claim(expected)
        fun current():Boolean {
            if(!ticket.isLive())return false
            val callerLive=try{live()}catch(_:Throwable){false}
            if(Thread.currentThread().isInterrupted||!callerLive)ticket.cancel()
            return ticket.isLive()
        }
        fun checkLive(){if(!current())fail(StickerOriginalException.Reason.CANCELED)}
        try {
            checkLive()
            val root=existingRoot(app)
            checkLive()
            // Unarmed reads keep the ordinary predicate. The controlled owned-fixture barrier
            // runs only INSIDE the callback of the real store read, never as a substitute read.
            val pack=StickerPackTransactionStore(root).read(expected.commitId){
                if(barrier==null)current() else StickerOriginalReadBarrier.storeLive(barrier,::current)
            }
            checkLive()
            if(pack.archiveSha256!=expected.archiveSha256)fail(StickerOriginalException.Reason.INTEGRITY)
            val sticker=pack.inspected.stickers.singleOrNull{it.id==expected.stickerId}
                ?:fail(StickerOriginalException.Reason.INTEGRITY)
            val actual=StickerAssetIdentity(expected.commitId,pack.archiveSha256,sticker.id,sticker.member.sha256,
                sticker.metadata.mime,sticker.member.size)
            if(actual!=expected)fail(StickerOriginalException.Reason.INTEGRITY)
            checkLive()
            val bytes=sticker.member.bytes()
            checkLive()
            val payload=StickerOriginalPayload(actual,bytes)
            checkLive()
            if(!StickerPrivateTransport.enabled(app))fail(StickerOriginalException.Reason.STATE)
            checkLive();return payload
        }catch(e:StickerOriginalException){throw e}
        catch(e:Exception){
            val canceled=!current()||when(e){
                is StickerStoreException->e.reason==StickerStoreException.Reason.CANCELED
                is StickerArchiveException->e.reason==StickerArchiveException.Reason.CANCELED
                is StickerPackException->e.reason==StickerPackException.Reason.CANCELED
                is StickerImageException->e.reason==StickerImageException.Reason.CANCELED
                else->false
            }
            fail(if(canceled)StickerOriginalException.Reason.CANCELED else StickerOriginalException.Reason.UNAVAILABLE)
        }finally{
            ticket.cancel();val admissionReleased=admission.finish(ticket)
            // Observed only after the actual admission release, not merely on cancellation.
            if(admissionReleased)barrier?.let{StickerOriginalReadBarrier.loaderAdmissionReleased(it)}
        }
    }
}
