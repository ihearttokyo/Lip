package dev.lip.keyboard

import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Portable English neural candidate. No geometric fallback or unverified weight loading. */
object SwipeModel {
    const val BYTES = 2_115_260
    const val SHA256 = "941272a7017229a73e07d1c0bd766757e739174db3a42fb622c26256b8c46110"
    const val REVISION = "c12f9e049ff9fba47c8f489ee807b92c2563a75d"
    const val LETTERS = "qwertyuiopasdfghjklzxcvbnm"
    fun verifiedBytes(file: File): ByteArray {
        require(file.isFile && file.length() == BYTES.toLong()) { "Swipe model missing or wrong size" }
        val bytes=file.inputStream().use { it.readNBytes(BYTES+1) }
        require(bytes.size==BYTES && MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }==SHA256) { "Swipe model integrity failed" }
        return bytes
    }
}

/** Actual measured letter-area geometry, not an assumed drawn-key width. Values are copied. */
class SwipeGeometry(rectangles: FloatArray, val aspect: Float) {
    internal val rectangles=rectangles.copyOf()
    init {
        require(this.rectangles.size==26*4 && aspect.isFinite() && aspect in .01f..2f)
        for(i in 0 until 26) {
            val at=i*4
            require(this.rectangles[at].isFinite() && this.rectangles[at] in 0f..1f)
            require(this.rectangles[at+1].isFinite() && this.rectangles[at+1] in 0f..1f)
            require(this.rectangles[at+2].isFinite() && this.rectangles[at+2] > 0f && this.rectangles[at+2]<=.5f)
            require(this.rectangles[at+3].isFinite() && this.rectangles[at+3] > 0f && this.rectangles[at+3]<=.5f)
            for(j in 0 until i)require(this.rectangles[at]!=this.rectangles[j*4] || this.rectangles[at+1]!=this.rectangles[j*4+1])
        }
    }
}

data class SwipePoint(val x: Float,val y: Float,val milliseconds: Float)

object SwipeInputBounds {
    fun pack(points: List<SwipePoint>): FloatArray {
        require(points.size in 2..4096)
        var previous=-1f
        return FloatArray(points.size*3).also { out -> points.forEachIndexed { i,p ->
            require(p.x.isFinite() && p.y.isFinite() && p.x in -.25f..1.25f && p.y in -.25f..1.25f)
            require(p.milliseconds.isFinite() && p.milliseconds in 0f..30_000f && p.milliseconds>=previous)
            previous=p.milliseconds;out[i*3]=p.x;out[i*3+1]=p.y;out[i*3+2]=p.milliseconds
        };require(points.last().milliseconds>points.first().milliseconds) }
    }
    fun lexicon(words: List<String>, frequencies: IntArray) {
        require(words.size in 1..250_000 && words.size==frequencies.size)
        val seen=HashSet<String>(words.size*4/3+1)
        for(index in words.indices){
            val word=words[index]
            require(word.length in 1..48&&seen.add(word))
            var followsLetter=false;var previous='\u0000';var steps=0
            // Same strict ASCII/apostrophe grammar and apostrophe-stripped40-key path as before,
            // without one regex Matcher and boxed/filter character intermediates per lexeme.
            for(letter in word){
                if(letter in 'a'..'z'){
                    followsLetter=true
                    if(letter!=previous){steps++;previous=letter}
                }else{
                    require(letter=='\''&&followsLetter)
                    followsLetter=false
                }
            }
            require(followsLetter)
            require(steps<=40){"Swipe lexicon key path exceeds the native40-step limit"}
            require(frequencies[index] in 0..255)
        }
    }
}

/** Worker-thread engine; caller owns asynchronous generation/caret cancellation before insertion. */
class SwipeEngine private constructor(private var handle: Long) : Closeable {
    fun prepare(shape:SwipeGeometry){
        workerOnly();synchronized(this){
            check(handle!=0L){"Swipe engine closed"}
            val copy=SwipeGeometry(shape.rectangles,shape.aspect)
            SwipeNative.reconfigure(handle,copy.rectangles,copy.aspect)
        }
    }
    /** Native mutex spans layout replacement AND inference, including against raw JNI reconfiguration. */
    fun decodeForGeometry(shape:SwipeGeometry,points:List<SwipePoint>,top:Int=3):List<String>{
        workerOnly();return synchronized(this){
            check(handle!=0L){"Swipe engine closed"};require(top in 1..8)
            val packed=SwipeInputBounds.pack(points)
            val copy=SwipeGeometry(shape.rectangles,shape.aspect)
            SwipeNative.decodeForGeometry(handle,copy.rectangles,copy.aspect,packed,top).toList()
        }
    }
    private fun workerOnly() { check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Neural swipe requires a worker thread" } }
    fun decode(points: List<SwipePoint>, top: Int=3): List<String> {
        workerOnly()
        return synchronized(this) {
            check(handle!=0L) { "Swipe engine closed" };require(top in 1..8)
            SwipeNative.decode(handle,SwipeInputBounds.pack(points),top).toList()
        }
    }
    override fun close() { workerOnly();synchronized(this) { if(handle!=0L){SwipeNative.close(handle);handle=0} } }
    companion object {
        fun open(model: File,geometry: SwipeGeometry,words: List<String>,frequencies: IntArray): SwipeEngine {
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Neural swipe loading requires a worker thread" }
            SwipeInputBounds.lexicon(words,frequencies)
            val verified=SwipeModel.verifiedBytes(model)
            val snapshot=SwipeGeometry(geometry.rectangles,geometry.aspect)
            val handle=SwipeNative.open(verified,snapshot.rectangles,snapshot.aspect,words.toTypedArray(),frequencies.copyOf())
            check(handle!=0L) { "Cannot open verified neural swipe engine" }
            try{return SwipeEngine(handle)}catch(error:Throwable){SwipeNative.close(handle);throw error}
        }
    }
}
internal object SwipeNative {
    init { System.loadLibrary("lip_swipe") }
    external fun open(model: ByteArray,geometry: FloatArray,aspect: Float,words: Array<String>,frequencies: IntArray): Long
    external fun reconfigure(handle:Long,geometry:FloatArray,aspect:Float)
    external fun decodeForGeometry(handle:Long,geometry:FloatArray,aspect:Float,points:FloatArray,top:Int):Array<String>
    external fun decode(handle: Long,points: FloatArray,top: Int): Array<String>
    external fun close(handle: Long)
}
