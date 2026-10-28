package dev.lip

import android.os.Process
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Owned app_process helper. No paths other than an existing controlled lock inode. */
object StickerStoreAndroidLockChild {
    @JvmStatic fun main(args:Array<String>) {
        check(args.size==1)
        val path=Path.of(args[0])
        check(path.fileName.toString()=="writer.lock"&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path))
        FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use{channel->channel.lock().use{
            println("LOCKED ${Process.myPid()} ${Process.myUid()}");System.out.flush()
            System.`in`.read()
        }}
    }
}
