package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SwipeEngineTest {
    private fun geometry()=FloatArray(104).also { out ->
        var i=0
        listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEachIndexed { row,letters ->
            val offset=when(row){0->0f;1->.5f;else->1.5f}
            letters.forEachIndexed { col,_ -> out[i++]=(col+offset+.5f)/10;out[i++]=(row+.5f)/3;out[i++]=.045f;out[i++]=.15f }
        }
    }
    @Test fun layoutIsDefensiveAndBounded() {
        val values=geometry();val layout=SwipeGeometry(values,.45f);values[0]=Float.NaN
        assertTrue(layout.rectangles[0].isFinite())
        assertThrows(IllegalArgumentException::class.java){SwipeGeometry(values,.45f)}
        assertThrows(IllegalArgumentException::class.java){SwipeGeometry(geometry(),0f)}
        assertThrows(IllegalArgumentException::class.java){SwipeGeometry(FloatArray(104),.45f)}
    }
    @Test fun traceRejectsBadCoordinatesTimeAndUnboundedWork() {
        val good=listOf(SwipePoint(.1f,.2f,0f),SwipePoint(.5f,.5f,40f))
        assertEquals(6,SwipeInputBounds.pack(good).size)
        for(points in listOf(emptyList(),good.take(1),good.reversed(),good.map{it.copy(milliseconds=0f)},good.map{it.copy(x=Float.NaN)},List(4097){SwipePoint(.1f,.1f,it.toFloat())}))
            assertThrows(IllegalArgumentException::class.java){SwipeInputBounds.pack(points)}
    }
    @Test fun lexiconDoesNotAcceptUnboundedOrForeignStrings() {
        SwipeInputBounds.lexicon(listOf("hello","don't"),intArrayOf(200,100))
        for(words in listOf(emptyList(),listOf("hello","hello"),listOf("bad\nword"),listOf("'word"),listOf("word'"),listOf("日本語")))
            assertThrows(IllegalArgumentException::class.java){SwipeInputBounds.lexicon(words,IntArray(words.size))}
        assertThrows(IllegalArgumentException::class.java){SwipeInputBounds.lexicon(listOf("hello"),intArrayOf(256))}
    }
    @Test fun collapsedNativePathBoundaryIsExplicitNotSilentlyDropped() {
        SwipeInputBounds.lexicon(listOf("ab".repeat(20),"a".repeat(48)),intArrayOf(200,100))
        assertThrows(IllegalArgumentException::class.java){SwipeInputBounds.lexicon(listOf("ab".repeat(20)+"a"),intArrayOf(200))}
    }
    @Test fun missingWeightsCannotFallBackToGeometricDecoder() {
        assertThrows(IllegalArgumentException::class.java){SwipeModel.verifiedBytes(File("/nonexistent-lip-swipe-model.bin"))}
    }
}
