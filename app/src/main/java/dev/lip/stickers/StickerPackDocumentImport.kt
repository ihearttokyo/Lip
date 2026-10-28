package dev.lip.stickers

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.atomic.AtomicBoolean

internal class StickerDocumentImportException(val reason:Reason):IllegalStateException(
    "An earlier sticker file read is still finishing. Wait before choosing another file."
) { enum class Reason { BUSY } }

/** One bounded read-only snapshot, never extraction, URI persistence or path fallback. */
internal object StickerPackDocumentImport {
    private val admitted=AtomicBoolean(false)
    private fun busy():Nothing=throw StickerDocumentImportException(StickerDocumentImportException.Reason.BUSY)
    fun read(resolver:ContentResolver,uri:Uri,cancellation:CancellationSignal):ByteArray {
        require(uri.scheme=="content"){"Choose a content document."}
        // Admission covers provider open itself, which may ignore cancellation while blocked.
        if(!admitted.compareAndSet(false,true))busy()
        try {
            cancellation.throwIfCanceled()
            val descriptor=resolver.openFileDescriptor(uri,"r",cancellation)
                ?:error("Sticker source unavailable.")
            return consumeOwnedDescriptor(descriptor,cancellation)
        } finally { admitted.set(false) }
    }

    /** Consumes this exact descriptor, including BUSY/failure/cancel. */
    fun readDescriptor(descriptor:ParcelFileDescriptor,cancellation:CancellationSignal):ByteArray {
        if(!admitted.compareAndSet(false,true)){runCatching{descriptor.close()};busy()}
        try{return consumeOwnedDescriptor(descriptor,cancellation)}finally{admitted.set(false)}
    }

    /** Runs only inside the caller's admission. Pipes/cloud transports are unqualified. */
    private fun consumeOwnedDescriptor(descriptor:ParcelFileDescriptor,cancellation:CancellationSignal):ByteArray {
        // Cancellation owns only this FD; never closes a provider or another operation's handle.
        cancellation.setOnCancelListener{runCatching{descriptor.close()}}
        try {
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use{input->
                cancellation.throwIfCanceled()
                val before=Os.fstat(descriptor.fileDescriptor)
                require(OsConstants.S_ISREG(before.st_mode)&&before.st_size in 1..StickerArchiveReader.MAX_SOURCE_BYTES.toLong()){
                    "Choose a regular ZIP file within 16 MiB."
                }
                val channel=input.channel
                require(channel.position()==0L&&channel.size()==before.st_size){"Sticker source must be seekable and unchanged."}
                val bytes=ByteArray(before.st_size.toInt());var at=0
                while(at<bytes.size){
                    cancellation.throwIfCanceled()
                    val count=input.read(bytes,at,minOf(32*1024,bytes.size-at))
                    check(count>0){"Sticker source ended early."};at+=count
                }
                cancellation.throwIfCanceled()
                check(input.read()==-1&&channel.size()==before.st_size){"Sticker source changed while reading."}
                val after=Os.fstat(descriptor.fileDescriptor)
                check(OsConstants.S_ISREG(after.st_mode)&&after.st_dev==before.st_dev&&after.st_ino==before.st_ino&&after.st_size==before.st_size){"Sticker source changed while reading."}
                cancellation.throwIfCanceled();bytes
            }
        } finally {
            cancellation.setOnCancelListener(null)
            runCatching{descriptor.close()}
        }
    }
}
