package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class ClipboardActionsTest {
    @Test fun explicitPasteAndSelectedCopyOnly(){
        assertEquals(ClipboardAvailability(false,true),ClipboardActions.available(1,0,0,0,true))
        assertEquals(ClipboardAvailability(true,true),ClipboardActions.available(1,0,0,4,true))
        assertEquals(ClipboardAvailability(true,true),ClipboardActions.available(1,0,4,0,true))
        assertFalse(ClipboardActions.available(1,0,-1,4,true).copy)
    }
    @Test fun privateIncognitoNullAndDisconnectedNeverExposeActions(){
        for(type in listOf(0x81,0x91,0xe1,0x12,0,5))
            assertEquals(ClipboardAvailability(false,false),ClipboardActions.available(type,0,0,4,true))
        assertEquals(ClipboardAvailability(false,false),ClipboardActions.available(1,0x1000000,0,4,true))
        assertEquals(ClipboardAvailability(false,false),ClipboardActions.available(1,0,0,4,false))
    }
    @Test fun leaseCannotFollowAnotherEditorRestartOrHiddenWindow(){
        val session=ImeSession();val connection=Any();session.beginEditor()
        val lease=ImeRepeatLease(session,connection)
        assertTrue(lease.canRun(connection));assertFalse(lease.canRun(Any()))
        session.beginEditor();assertFalse(lease.canRun(connection))
        val closed=ImeRepeatLease(session,connection);closed.close();assertFalse(closed.canRun(connection))
        val finished=ImeRepeatLease(session,connection);session.finishEditor();assertFalse(finished.canRun(connection))
    }
}
