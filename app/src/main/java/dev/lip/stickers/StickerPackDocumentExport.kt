package dev.lip.stickers

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit selected-pack plaintext ZIP export. Only an empty regular, seekable,
 * read/write descriptor with successful readback is supported. No overwrite,
 * path fallback, retained URI, cloud sync or automatic retry. A failed write may
 * leave a partial destination; it is preserved for the user to inspect.
 */
internal object StickerPackDocumentExport {
    private val writing=AtomicBoolean(false)
    fun write(resolver:ContentResolver,uri:Uri,bytes:ByteArray,expectedSha:String,cancellation:CancellationSignal,admit:()->Boolean){
        check(uri.scheme=="content"&&bytes.size in 1..StickerArchiveReader.MAX_SOURCE_BYTES)
        check(writing.compareAndSet(false,true)){"A previous sticker export is still draining."}
        try{
            cancellation.throwIfCanceled()
            val descriptor=resolver.openFileDescriptor(uri,"rw",cancellation)?:error("Destination unavailable.")
            consume(descriptor,bytes,expectedSha,cancellation,admit)
        }finally{writing.set(false)}
    }
    internal fun consume(descriptor:ParcelFileDescriptor,bytes:ByteArray,expectedSha:String,cancellation:CancellationSignal,admit:()->Boolean){
        cancellation.setOnCancelListener{runCatching{descriptor.close()}}
        try{
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use{output->
                check(bytes.size in 1..StickerArchiveReader.MAX_SOURCE_BYTES&&Regex("[a-f0-9]{64}").matches(expectedSha))
                val fd=descriptor.fileDescriptor
                val before=Os.fstat(fd)
                check(OsConstants.S_ISREG(before.st_mode)&&before.st_size==0L)
                output.channel.tryLock()?.use{
                    cancellation.throwIfCanceled()
                    check(output.channel.position()==0L&&output.channel.size()==0L)
                    // Test read permission BEFORE admitting any write, even on the empty FD.
                    check(Os.pread(fd,ByteArray(1),0,1,0)==0)
                    check(admit()) // One-use live operation authorization, after every external preflight.
                    var offset=0
                    while(offset<bytes.size){
                        cancellation.throwIfCanceled()
                        val count=minOf(64*1024,bytes.size-offset);output.write(bytes,offset,count);offset+=count
                    }
                    output.channel.force(true)
                    val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var at=0
                    while(at<bytes.size){
                        cancellation.throwIfCanceled()
                        val count=Os.pread(fd,buffer,0,minOf(buffer.size,bytes.size-at),at.toLong())
                        check(count>0);digest.update(buffer,0,count);at+=count
                    }
                    check(Os.pread(fd,buffer,0,1,bytes.size.toLong())==0)
                    val after=Os.fstat(fd)
                    check(after.st_dev==before.st_dev&&after.st_ino==before.st_ino&&after.st_size==bytes.size.toLong())
                    val actual=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
                    check(actual==expectedSha)
                    cancellation.throwIfCanceled()
                }?:error("Destination busy.")
            }
        }finally{cancellation.setOnCancelListener(null);runCatching{descriptor.close()}}
    }
}
