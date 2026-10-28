package dev.lip.keyboard

import android.content.Context
import android.os.Looper
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object SwipeAssetCatalog {
    const val DICTIONARY_BYTES=5_852_290
    const val DICTIONARY_SHA256="658112c11991a779917e6e0b1e28a89aeaa27f24abde1f058c8224101f565dfd"
    const val ENTRIES=246_650
    const val MAX_ENTRIES=250_000
    fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
}
data class SwipeLexeme(val word:String,val frequency:Int,val display:String,val offensive:Boolean)
class SwipeLexiconData private constructor(val entries:List<SwipeLexeme>) {
    private val displayByWord=entries.associateBy{it.word}
    fun display(word:String):String?=displayByWord[word]?.display
    fun admitted(includeOffensive:Boolean=false)=if(includeOffensive)entries else entries.filterNot{it.offensive}
    companion object {
        private val spelling=Regex("[a-z]+(?:'[a-z]+)*")
        private val knownFlags=setOf("abbreviation","medical","offensive","hand-added","babytalk","technical","australian","drugs")
        fun verified(bytes:ByteArray):SwipeLexiconData {
            require(bytes.size==SwipeAssetCatalog.DICTIONARY_BYTES && SwipeAssetCatalog.sha256(bytes)==SwipeAssetCatalog.DICTIONARY_SHA256){"English dictionary integrity failed"}
            return fromVerifiedText(bytes.toString(Charsets.US_ASCII)).also{require(it.entries.size==SwipeAssetCatalog.ENTRIES)}
        }
        /** Exact pinned bytes have already authenticated all source/importer invariants. No untrusted input reaches this path. */
        private fun fromVerifiedText(text:String):SwipeLexiconData {
            check(text.startsWith("#lip-swipe-english-v1\n"))
            val rows=ArrayList<SwipeLexeme>(SwipeAssetCatalog.ENTRIES);var at=text.indexOf('\n')+1
            while(at<text.length){
                val first=text.indexOf('\t',at);val second=text.indexOf('\t',first+1);val third=text.indexOf('\t',second+1);val end=text.indexOf('\n',third+1)
                check(first>at&&second>first&&third>second&&end>third)
                val flags=text.substring(third+1,end)
                val offensive=flags=="offensive"||flags.startsWith("offensive:")||flags.endsWith(":offensive")||flags.contains(":offensive:")
                rows.add(SwipeLexeme(text.substring(at,first),text.substring(first+1,second).toInt(),text.substring(second+1,third),offensive))
                at=end+1
            }
            return SwipeLexiconData(rows.toList())
        }
        internal fun parse(text:String):SwipeLexiconData {
            val lines=text.lineSequence().iterator();require(lines.hasNext()&&lines.next()=="#lip-swipe-english-v1")
            val rows=ArrayList<SwipeLexeme>();var previous=""
            while(lines.hasNext()) {
                val line=lines.next();if(line.isEmpty()){require(!lines.hasNext());break}
                require(line.length<=256)
                val columns=line.split('\t');require(columns.size==4)
                val word=columns[0];val frequency=columns[1].toIntOrNull();val display=columns[2]
                require(word.length in 1..48 && spelling.matches(word) && word>previous && display.lowercase(java.util.Locale.ROOT)==word && display.all{it.code in 0..127})
                require(frequency!=null&&frequency in 0..255)
                val flags=if(columns[3]=="-")emptySet() else columns[3].split(':').toSet()
                require(flags.all{it in knownFlags})
                rows.add(SwipeLexeme(word,frequency,display,"offensive" in flags));require(rows.size<=SwipeAssetCatalog.MAX_ENTRIES);previous=word
            }
            require(rows.isNotEmpty());return SwipeLexiconData(rows.toList())
        }
    }
}
/** APK-bundled public artifacts only; no network, private history, clipboard or plaintext input cache. */
object SwipeAssets {
    private val lock=Any()
    @Volatile private var dictionary:SwipeLexiconData?=null
    private fun workerOnly(){check(Looper.myLooper()!=Looper.getMainLooper()){"Swipe asset setup requires a worker thread"}}
    fun dictionary(context:Context):SwipeLexiconData {
        workerOnly();dictionary?.let{return it}
        return synchronized(lock) {
            dictionary ?: context.assets.open("swipe/en.tsv").use{stream->SwipeLexiconData.verified(stream.readNBytes(SwipeAssetCatalog.DICTIONARY_BYTES+1))}.also{dictionary=it}
        }
    }
    fun model(context:Context):File {
        workerOnly()
        return synchronized(lock) {
            val directory=File(context.noBackupFilesDir,"swipe-engine");check(directory.isDirectory||directory.mkdirs())
            val target=File(directory,"swipe-model.bin")
            if(runCatching{SwipeModel.verifiedBytes(target)}.isSuccess)return@synchronized target
            val bytes=context.assets.open("swipe/swipe-model.bin").use{it.readNBytes(SwipeModel.BYTES+1)}
            require(bytes.size==SwipeModel.BYTES&&SwipeAssetCatalog.sha256(bytes)==SwipeModel.SHA256){"Bundled swipe model integrity failed"}
            val temporary=File.createTempFile("model-",".partial",directory)
            try {
                java.io.FileOutputStream(temporary).use{it.write(bytes);it.fd.sync()}
                Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            }finally{if(temporary.exists())temporary.delete()}
            target
        }
    }
    fun open(context:Context,geometry:SwipeGeometry,includeOffensive:Boolean=false):SwipeEngine {
        workerOnly();val entries=dictionary(context).admitted(includeOffensive)
        return SwipeEngine.open(model(context),geometry,entries.map{it.word},IntArray(entries.size){entries[it].frequency})
    }
}
