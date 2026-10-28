package dev.lip.stickers

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** No hidden FD APIs or absolute child rename. One stat snapshot binds the public
 * Unix dev/ino to the store's original opaque fileKey; native code opens and
 * verifies that root before same-dirfd NOREPLACE. No filesystem fallback.
 */
internal object RootedStickerRename {
    internal class Identity internal constructor(internal val path:String,internal val dev:Long,internal val ino:Long){
        override fun toString()="StickerRenameRoot(redacted)"
    }
    fun capture(root:Path,originalFileKey:Any):Identity {
        val a=Files.readAttributes(root,"unix:dev,ino,fileKey,isDirectory,isSymbolicLink",LinkOption.NOFOLLOW_LINKS)
        check(a["fileKey"]==originalFileKey&&a["isDirectory"]==true&&a["isSymbolicLink"]==false){"Sticker root changed."}
        val dev=(a["dev"] as? Number)?.toLong()?:error("Sticker root identity unavailable.")
        val ino=(a["ino"] as? Number)?.toLong()?:error("Sticker root identity unavailable.")
        return Identity(root.toAbsolutePath().toString(),dev,ino)
    }
    fun move(root:Path,originalFileKey:Any,from:String,to:String)=moveCaptured(capture(root,originalFileKey),from,to)
    internal fun moveCaptured(root:Identity,from:String,to:String){
        val name=Regex("(commit|bin)-([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})")
        val a=name.matchEntire(from)?:error("Invalid sticker source.")
        val b=name.matchEntire(to)?:error("Invalid sticker destination.")
        check(a.groupValues[1]!=b.groupValues[1]&&a.groupValues[2]==b.groupValues[2])
        System.loadLibrary("lip_bark")
        val error=renameAt(root.path.toByteArray(Charsets.UTF_8),root.dev,root.ino,from.toByteArray(Charsets.US_ASCII),to.toByteArray(Charsets.US_ASCII))
        if(error==17)throw FileAlreadyExistsException("Sticker destination already exists.")
        if(error!=0)throw IOException("Root-bound sticker rename unavailable; no retry or fallback.")
    }
    private external fun renameAt(root:ByteArray,dev:Long,ino:Long,from:ByteArray,to:ByteArray):Int
}
