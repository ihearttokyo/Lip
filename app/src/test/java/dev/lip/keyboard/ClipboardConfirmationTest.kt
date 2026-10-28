package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class ClipboardConfirmationTest {
    @Test fun oldAgreementCannotReenableAfterRevocation(){
        val state=ClipboardConfirmation();val enable=state.begin("enable")
        assertTrue(state.claim(enable));val off=state.begin("off");assertTrue(state.claim(off))
        assertFalse(state.claim(enable))
    }
    @Test fun oldClearCannotEraseCopiesCreatedAfterItCompleted(){
        val state=ClipboardConfirmation();val clear=state.begin("clear")
        assertTrue(state.claim(clear));assertFalse(state.claim(clear))
        val next=state.begin("clear");assertFalse(state.claim(clear));assertTrue(state.claim(next))
    }
    @Test fun cancelAndNewPromptInvalidateOldNodes(){
        val state=ClipboardConfirmation();val old=state.begin("enable");state.cancel()
        val next=state.begin("enable");assertFalse(state.claim(old));assertTrue(state.claim(next));assertFalse(state.claim(null))
    }
}
