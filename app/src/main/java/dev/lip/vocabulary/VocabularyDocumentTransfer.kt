package dev.lip.vocabulary

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit single-document local snapshot/export. No provider scans, URI retention,
 * automatic Drive upload, path fallback, overwrite or retry. Cloud pipes unqualified.
 */
object VocabularyDocumentTransfer {
    private val reading = AtomicBoolean(false)
    private val writing = AtomicBoolean(false)
    fun read(resolver: ContentResolver, uri: Uri, cancellation: CancellationSignal): ByteArray {
        check(uri.scheme == "content")
        check(reading.compareAndSet(false, true)) { "An earlier vocabulary document read is still finishing." }
        try {
            cancellation.throwIfCanceled()
            val descriptor = resolver.openFileDescriptor(uri, "r", cancellation) ?: error("Vocabulary source unavailable.")
            cancellation.setOnCancelListener { runCatching { descriptor.close() } }
            try {
                return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val before = Os.fstat(descriptor.fileDescriptor)
                    check(OsConstants.S_ISREG(before.st_mode) && before.st_size in 1..PortableVocabulary.MAX_BYTES.toLong())
                    check(input.channel.position() == 0L && input.channel.size() == before.st_size)
                    val bytes = ByteArray(before.st_size.toInt()); var offset = 0
                    while (offset < bytes.size) {
                        cancellation.throwIfCanceled()
                        val count = input.read(bytes, offset, minOf(64 * 1024, bytes.size - offset))
                        check(count > 0); offset += count
                    }
                    cancellation.throwIfCanceled()
                    check(input.read() == -1 && input.channel.size() == before.st_size)
                    val after = Os.fstat(descriptor.fileDescriptor)
                    check(after.st_dev == before.st_dev && after.st_ino == before.st_ino && after.st_size == before.st_size)
                    bytes
                }
            } finally { cancellation.setOnCancelListener(null); runCatching { descriptor.close() } }
        } finally { reading.set(false) }
    }
    fun write(resolver: ContentResolver, uri: Uri, bytes: ByteArray,
        cancellation: CancellationSignal, admit: () -> Unit) {
        check(uri.scheme == "content" && bytes.size <= PortableVocabulary.MAX_BYTES)
        check(writing.compareAndSet(false, true)) { "An earlier vocabulary export is still finishing." }
        try {
            cancellation.throwIfCanceled()
            val descriptor = resolver.openFileDescriptor(uri, "rw", cancellation) ?: error("Vocabulary destination unavailable.")
            cancellation.setOnCancelListener { runCatching { descriptor.close() } }
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    val stat = Os.fstat(descriptor.fileDescriptor)
                    check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size == 0L)
                    output.channel.tryLock()?.use {
                        cancellation.throwIfCanceled()
                        check(output.channel.position() == 0L && output.channel.size() == 0L)
                        admit() // Explicit authorization consumed immediately before the first plaintext write.
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) {
                            cancellation.throwIfCanceled()
                            check(output.channel.write(buffer) > 0)
                        }
                        output.channel.force(true)
                    } ?: error("Vocabulary destination busy.")
                }
            } finally { cancellation.setOnCancelListener(null); runCatching { descriptor.close() } }
        } finally { writing.set(false) }
    }
}
