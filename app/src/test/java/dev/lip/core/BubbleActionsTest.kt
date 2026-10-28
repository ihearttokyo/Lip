package dev.lip.core
import org.junit.Assert.*
import org.junit.Test
class BubbleActionsTest {
    @Test fun onlyReadyVerifiedTargetEnablesInsert(){
        for(ready in listOf(false,true))for(insertable in listOf(false,true))for(target in listOf(false,true))
            assertEquals(ready&&insertable&&target,BubbleActions.insertEnabled(ready,insertable,target))
    }
    @Test fun pressThenFocusLossRefusesInsertRatherThanChangingAction(){
        assertTrue(BubbleActions.insertEnabled(true,true,true))
        assertFalse(BubbleActions.insertEnabled(true,true,false))
        assertFalse(BubbleActions.insertEnabled(true,false,true))
    }
    @Test fun recordingAndIdleNeverEnableTranscriptInsertion(){
        assertFalse(BubbleActions.insertEnabled(false,true,true))
    }
}
