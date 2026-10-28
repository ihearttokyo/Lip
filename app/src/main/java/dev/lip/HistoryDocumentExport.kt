package dev.lip

import android.content.ContentResolver
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer

/** Only the explicit CREATE_DOCUMENT result. No truncate/delete/retry/provider URL fetch. */
object HistoryDocumentExport {
    fun write(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
        require(uri.scheme == "content" && bytes.size <= HistoryTransfer.MAX_BYTES) { "Unsupported export destination" }
        val descriptor = resolver.openFileDescriptor(uri, "rw") ?: error("Export destination unavailable")
        writeDescriptor(descriptor, bytes)
    }
    /** Same publication seam; native fixtures supply only uniquely owned local FDs. */
    fun writeDescriptor(descriptor: ParcelFileDescriptor, bytes: ByteArray) {
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            require(bytes.size <= HistoryTransfer.MAX_BYTES) { "History export too large" }
            val stat = Os.fstat(descriptor.fileDescriptor)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size == 0L) { "Choose a NEW empty, seekable file; existing contents preserved" }
            val channel = output.channel
            val lock = channel.tryLock() ?: error("Export destination is busy")
            lock.use {
                check(channel.size() == 0L && channel.position() == 0L) { "Export destination changed; existing contents preserved" }
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) check(channel.write(buffer) > 0) { "Export did not complete" }
                channel.force(true)
            }
        }
    }
}
