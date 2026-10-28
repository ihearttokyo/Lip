package dev.lip.keyboard
import android.content.Context
import android.os.Looper

object NextWordAssets {
    const val BYTES=1_093_594
    const val SHA256="299cc1ba59c1bcc67b9151fc2f7360a6f5b9077e3e2d2ece3533aa5c603c31b7"
    @Volatile private var model:NextWordSuggestions?=null
    @Synchronized fun load(context:Context):NextWordSuggestions {
        check(Looper.myLooper()!=Looper.getMainLooper())
        model?.let{return it}
        val bytes=context.assets.open("nextword/en.tsv").use{it.readNBytes(BYTES+1)}
        val dictionary=SwipeAssets.dictionary(context)
        return decodeVerified(bytes){dictionary.display(it)?:it}.also{model=it}
    }
    internal fun decodeVerified(bytes:ByteArray,display:(String)->String):NextWordSuggestions {
        require(bytes.size==BYTES&&SwipeAssetCatalog.sha256(bytes)==SHA256){"Next-word asset integrity failed"}
        return NextWordSuggestions.fromVerifiedText(bytes.toString(Charsets.US_ASCII),display)
    }
}
