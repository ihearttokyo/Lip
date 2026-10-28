package dev.lip

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants

/** Bounded read-only source. No URI/path fallback, writes, permission persistence
 * or retry. Regular/seekable descriptor required; pipe/cloud transports unqualified. */
object HistoryDocumentImport {
    fun read(resolver: ContentResolver, uri: Uri, cancellation: CancellationSignal): PortableHistoryDocument {
        require(uri.scheme == "content") { "Unsupported history source." }
        cancellation.throwIfCanceled()
        val descriptor=resolver.openFileDescriptor(uri,"r",cancellation) ?: error("History source unavailable.")
        return readDescriptor(descriptor,cancellation)
    }
    fun readDescriptor(descriptor: ParcelFileDescriptor, cancellation: CancellationSignal): PortableHistoryDocument =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            cancellation.throwIfCanceled()
            val stat=Os.fstat(descriptor.fileDescriptor)
            require(OsConstants.S_ISREG(stat.st_mode)&&stat.st_size in 1..HistoryTransfer.MAX_BYTES.toLong()) { "Choose a regular JSON file within 4 MiB." }
            val channel=input.channel
            require(channel.position()==0L&&channel.size()==stat.st_size) { "History source changed or is not seekable." }
            val bytes=ByteArray(stat.st_size.toInt());var offset=0
            while(offset<bytes.size) {
                cancellation.throwIfCanceled()
                val count=input.read(bytes,offset,bytes.size-offset)
                check(count>0) { "History source ended before its declared length." };offset+=count
            }
            cancellation.throwIfCanceled()
            check(input.read()==-1&&channel.size()==stat.st_size) { "History source changed while reading." }
            HistoryImport.decode(bytes)
        }
}
