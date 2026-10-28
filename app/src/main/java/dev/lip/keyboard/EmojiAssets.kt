package dev.lip.keyboard

import android.content.Context
import java.security.MessageDigest

object EmojiAssets {
    const val BYTES=1314162
    const val SHA256="696057e9cb8e4e9aa5f85f9581f90648a4cee2e094860c38914c5a5e7c6b836b"
    fun load(context:Context):EmojiCatalog {
        val data=context.assets.open("emoji/english-indexed.tsv").use{it.readNBytes(BYTES+1)}
        return verify(data)
    }
    fun verify(data:ByteArray):EmojiCatalog {
        check(data.size==BYTES&&MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it.toInt() and 255)}==SHA256){"Emoji catalog integrity failed"}
        return EmojiCatalog.readAuthenticatedIndex(data.toString(Charsets.UTF_8)).also{check(it.items.size==3944)}
    }
}
