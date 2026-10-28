package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class GifSearchTest{
    @Test fun queryIsBoundedEphemeralAndCannotBecomeEditorInput(){
        val state=GifSearch();val ordinary=state.token
        state.begin();val owned=state.token
        assertNotSame(ordinary,owned);state.append("laugh");assertEquals("laugh",state.query)
        state.finish();assertNotSame(owned,state.token);assertEquals("laugh",state.query)
        state.append("should-not-append");assertEquals("laugh",state.query)
        state.clear();assertEquals("",state.query)
    }
    @Test fun deleteKeepsUnicodeWellFormedAndLimitDoesNotSplitEmoji(){
        val state=GifSearch();state.begin();state.append("x".repeat(63));state.append("😀");assertEquals(63,state.query.length)
        state.clear();state.begin();state.append("😀");state.delete();assertEquals("",state.query)
    }
}
