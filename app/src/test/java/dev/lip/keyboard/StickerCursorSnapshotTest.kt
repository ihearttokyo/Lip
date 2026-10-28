package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class StickerCursorSnapshotTest {
    private fun capture(text:CharSequence="x".repeat(64),start:Int=32,end:Int=start,offset:Int=68,
        reportedStart:Int=offset+start,reportedEnd:Int=reportedStart)=
        StickerCursorSnapshot.capture("owned.fixture",7,text,start,end,offset,reportedStart,reportedEnd)
    @Test fun boundedFreshCoordinatesAndContext(){
        val cursor=capture()!!
        assertEquals(100,cursor.start);assertEquals(100,cursor.end)
        assertEquals("x".repeat(32),cursor.before);assertEquals(cursor.before,cursor.after)
        assertEquals(0,capture("",0,0,0)!!.start)
    }
    @Test fun repetitiveContextCannotHideDelayedSelectionCallback(){
        val first=capture()!!
        assertNull(capture(offset=69,reportedStart=100))
        val moved=capture(offset=69)!!
        assertEquals(first.before,moved.before);assertEquals(first.after,moved.after)
        assertNotEquals(first,moved)
    }
    @Test fun unknownMalformedAndOversizedWindowsRefused(){
        assertNull(capture(offset=-1));assertNull(capture(start=-1));assertNull(capture(end=33))
        assertNull(capture(text="x".repeat(65)));assertNull(capture(start=33));assertNull(capture(start=31))
        assertNull(capture(text="short",start=6));assertNull(capture(reportedEnd=101))
        assertNull(capture(offset=Int.MAX_VALUE))
    }
    @Test fun mutableEditorTextIsCopiedOnlyInBoundedSnapshot(){
        val source=StringBuilder("x".repeat(64));val cursor=capture(source)!!
        source.setCharAt(0,'y');assertEquals("x".repeat(32),cursor.before)
    }
}
