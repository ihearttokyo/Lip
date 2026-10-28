package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class ClipboardHistoryReadTest {
    @Test fun deniedContextNeverFetchesPayload(){
        var reads=0;assertNull(ClipboardHistoryRead.read({false},{reads++;"secret"}));assertEquals(0,reads)
    }
    @Test fun lockOrPrivateTransitionDuringReadCannotReturnPayload(){
        var allowed=true
        assertNull(ClipboardHistoryRead.read({allowed},{allowed=false;"not-to-be-stored"}))
    }
    @Test fun eligibleBoundedTextOnly(){
        assertEquals("future copied text",ClipboardHistoryRead.read({true},{"future copied text"}))
        assertNull(ClipboardHistoryRead.read({true},{"x".repeat(4097)}))
    }
}
