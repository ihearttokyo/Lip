package dev.lip.keyboard

/** Generated from the pinned public manifest; no runtime catalog/network discovery. */
data class GifItem(val id:String,val title:String,val tags:List<String>,val bytes:Int,val sha256:String,val source:String)
object GifCatalog {
    const val CREDIT="Animated Noto Emoji by Google · CC BY4.0 · attribution comment added; frames unchanged"
    val items=listOf(
        GifItem("happy","Grinning face",listOf("smile","happy","hello"),836367,"0dcf3da900808e7a3a6d5c28ab2ce7f1f0a82cef4c5a19cdcab928ea1e871d22","https://fonts.gstatic.com/s/e/notoemoji/latest/1f600/512.gif"),
        GifItem("laugh","Face with tears of joy",listOf("laugh","lol","funny"),1176751,"94b1c8608263c9f6b918c19cd87862aff973e6e789a878a59c21d2317120ca1d","https://fonts.gstatic.com/s/e/notoemoji/latest/1f602/512.gif"),
        GifItem("love","Smiling face with hearts",listOf("love","thanks","heart"),1197518,"93f428a1f5b6fcf44a84e80c9d5f56e270e4b02f5e330a29d5f158def0d8f0ff","https://fonts.gstatic.com/s/e/notoemoji/latest/1f970/512.gif"),
        GifItem("thanks","Folded hands",listOf("thanks","please","pray"),208727,"e34354595aa13d3b12a70c08a6f1ddd4414f8c30a9649323d24e9109d0659f23","https://fonts.gstatic.com/s/e/notoemoji/latest/1f64f/512.gif"),
        GifItem("party","Party popper",listOf("party","celebrate","congratulations"),395991,"86ff32215f7a268640b715d0a40dd140789b5da3035b0214d8983d39b49f5fed","https://fonts.gstatic.com/s/e/notoemoji/latest/1f389/512.gif"),
        GifItem("hello","Waving hand",listOf("hello","hi","bye"),378554,"d1572405a48aef0ce631dcbcfebdf449a351ec3bd0511109fe1e9fd19097d0c9","https://fonts.gstatic.com/s/e/notoemoji/latest/1f44b/512.gif"),
        GifItem("yes","Thumbs up",listOf("yes","ok","good"),195054,"85e66daaab456750a8da77cb9705c475f1746b29df209441875121886121c517","https://fonts.gstatic.com/s/e/notoemoji/latest/1f44d/512.gif"),
        GifItem("sad","Crying face",listOf("sad","cry","sorry"),1929084,"ad3bf1f62bb71cdfe9acddf893038fd27f988617ca70b75b44b2f37e8267d902","https://fonts.gstatic.com/s/e/notoemoji/latest/1f622/512.gif"),
        GifItem("think","Thinking face",listOf("think","hmm","question"),1389169,"9fc8d6df1119fca20c07e10501bb362562a4c53e96e816095122521c23d79b6d","https://fonts.gstatic.com/s/e/notoemoji/latest/1f914/512.gif"),
        GifItem("celebrate","Partying face",listOf("party","birthday","celebrate"),958220,"7c959c658c0070bc9ad4b743357147bcbaf46ca2c89e1c47f2cd21b1eecf1701","https://fonts.gstatic.com/s/e/notoemoji/latest/1f973/512.gif"),
        GifItem("fire","Fire",listOf("fire","great","awesome"),475467,"5152f1ec45ad4c28576402ecf1e02e33e00494ad683b78d5a286dcd72ec38958","https://fonts.gstatic.com/s/e/notoemoji/latest/1f525/512.gif"),
        GifItem("clap","Clapping hands",listOf("clap","well done","bravo"),194638,"e0312bc8c9b7a0812b8d63c62b3cf526429e106d206a9434c5f8f215379bb485","https://fonts.gstatic.com/s/e/notoemoji/latest/1f44f/512.gif")
    )
    fun find(id:String)=items.firstOrNull{it.id==id}
    fun search(query:String):List<GifItem>{
        require(query.length<=64)
        val tokens=query.lowercase(java.util.Locale.ROOT).trim().split(Regex("\\s+")).filter{it.isNotEmpty()}
        return items.filter{item->val text=(item.title+" "+item.tags.joinToString(" ")).lowercase(java.util.Locale.ROOT);tokens.all{text.contains(it)}}
    }
    fun accepts(mimeTypes:Array<String>?)=mimeTypes?.any{it.lowercase(java.util.Locale.ROOT) in setOf("image/gif","image/*","*/*")}==true
}
