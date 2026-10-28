package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class SwipeTextFormatTest {
    private val cursor=ImeCursor("synthetic.editor",1,0,0,"","",false)
    @Test fun spacesOnlyNewTextAndPreservesSurroundings() {
        assertEquals("hello ",SwipeTextFormat.insertion("hello",cursor,false,false,true))
        assertEquals(" hello ",SwipeTextFormat.insertion("hello",cursor.copy(before="word",after="next"),false,false,true))
        assertEquals("hello",SwipeTextFormat.insertion("hello",cursor.copy(before=" ",after="."),false,false,true))
        assertEquals("hello",SwipeTextFormat.insertion("hello",cursor,false,false,false))
    }
    @Test fun shiftCapsAndSourceProperSpellingAreDistinct() {
        assertEquals("iPhone ",SwipeTextFormat.insertion("iPhone",cursor,false,false,true))
        assertEquals("Hello ",SwipeTextFormat.insertion("hello",cursor,true,false,true))
        assertEquals("HELLO ",SwipeTextFormat.insertion("hello",cursor,false,true,true))
    }
}
