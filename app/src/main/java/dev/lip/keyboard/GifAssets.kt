package dev.lip.keyboard

import android.content.Context
import java.security.MessageDigest

object GifAssets {
    fun read(context:Context,item:GifItem):ByteArray {
        require(GifCatalog.find(item.id)==item&&item.bytes in 1..2_100_000)
        val bytes=context.assets.open("gif/${item.id}.gif").use{it.readNBytes(item.bytes+1)}
        check(bytes.size==item.bytes&&MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}==item.sha256){"Local GIF integrity failed"}
        check(bytes.copyOfRange(0,6).contentEquals("GIF89a".toByteArray())&&bytes.last()==0x3b.toByte())
        return bytes
    }
}
