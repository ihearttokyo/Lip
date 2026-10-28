package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class ImeSessionTest {
    private fun cursor(start: Int = 5, end: Int = start, secure: Boolean = false) =
        ImeCursor("fixture.editor", 17, start, end, "left ", "right", secure)
    @Test fun sameCaretCanBeClaimedOnlyOnce() {
        val session = ImeSession(); session.beginEditor()
        val ticket = session.capture(cursor())!!
        assertTrue(session.isCurrent(ticket, cursor()))
        assertTrue(session.claim(ticket, cursor()))
        assertFalse(session.claim(ticket, cursor()))
    }
    @Test fun fieldCaretTextSelectionAndProtectionChangesRefuseWithoutConsumingNewTicket() {
        for (current in listOf(cursor(6), cursor(5, 8), cursor(secure = true), cursor().copy(packageName = "other"),
            cursor().copy(fieldId = 18), cursor().copy(before = "changed"), cursor().copy(after = "changed"))) {
            val session = ImeSession(); session.beginEditor(); val ticket = session.capture(cursor())!!
            assertFalse(session.claim(ticket, current))
        }
    }
    @Test fun editorRestartAndUserEditInvalidateAsyncResults() {
        val session = ImeSession(); session.beginEditor()
        val first = session.capture(cursor())!!; session.editorChanged()
        assertFalse(session.isCurrent(first, cursor()))
        val second = session.capture(cursor())!!; session.beginEditor()
        assertFalse(session.claim(second, cursor()))
        val third = session.capture(cursor())!!; session.finishEditor()
        assertFalse(session.claim(third, cursor()))
        assertFalse(session.sameEditor(third))
    }
    @Test fun missingUnknownSelectedOrProtectedCursorCannotStartVoiceInsertion() {
        val session = ImeSession()
        assertNull(session.capture(cursor()))
        session.beginEditor()
        assertNull(session.capture(null))
        assertNull(session.capture(cursor(-1)))
        assertNull(session.capture(cursor(5, 6)))
        assertNull(session.capture(cursor(secure = true)))
    }
    @Test fun fieldPolicyAllowsManualPasswordTypingButNoVoiceSwipeOrPersonalization() {
        val password = ImeFieldPolicy.forInput(0x81, 0)
        assertTrue(password.manualTyping)
        assertTrue(password.protected)
        assertFalse(password.voice); assertFalse(password.swipe); assertFalse(password.learn)
        for (type in listOf(0x91, 0xe1, 0x12)) assertTrue(ImeFieldPolicy.forInput(type, 0).protected)
    }
    @Test fun numericNullAndIncognitoAreNotOrdinaryLearningFields() {
        assertFalse(ImeFieldPolicy.forInput(0, 0).voice)
        assertFalse(ImeFieldPolicy.forInput(2, 0).swipe)
        val privateText = ImeFieldPolicy.forInput(1, 0x1000000)
        assertFalse(privateText.voice); assertFalse(privateText.learn)
        assertFalse(privateText.swipe) // Conservative: no gesture traces for no-learning fields.
        val normal = ImeFieldPolicy.forInput(1, 0)
        assertTrue(normal.voice); assertTrue(normal.swipe)
        assertFalse(normal.learn) // No persistent typing-context learning without opt-in.
    }
}

class ImeOwnershipRegressionTest {
    private fun cursor() = ImeCursor("fixture.editor", 17, 5, 5, "left ", "right", false)
    @Test fun cancelOrHideDuringCleanupInvalidatesLateInsertionAndInterruptsOwner() {
        val session = ImeSession(); session.beginEditor()
        val owner = ImeVoiceOwner(session); val ticket = session.capture(cursor())!!
        owner.ticket = ticket
        var interrupted = false
        owner.cancel { interrupted = true } // Same production owner path for Cancel/hide/finish.
        assertTrue(interrupted)
        assertNull(owner.ticket)
        assertFalse(session.claim(ticket, cursor())) // Delayed cleanup cannot dispatch.
        assertFalse(owner.cancel { error("Already detached owner must not interrupt someone else") })
    }
    @Test fun repeatLeaseCannotCrossSameFieldRestartConnectionChangeOrHide() {
        val session = ImeSession(); session.beginEditor(); val connection = Any()
        val lease = ImeRepeatLease(session, connection)
        assertTrue(lease.canRun(connection))
        assertFalse(lease.canRun(Any()))
        session.beginEditor() // Same text/connection does not imply same editor incarnation.
        assertFalse(lease.canRun(connection))
        val next = ImeRepeatLease(session, connection); next.close()
        assertFalse(next.canRun(connection))
    }
}

class ImeLayoutActionTest {
    @Test fun signedDecimalPhoneAndTimeCanRepresentTheirValidInputs() {
        fun alphabet(type: Int) = ImeKeyLayout.numericRows(type)!!.flatten().joinToString("")
        assertTrue(alphabet(2 or 0x1000).contains('-'))
        assertTrue(alphabet(2 or 0x1000 or 0x2000).contains('.'))
        assertFalse(alphabet(2).contains('.'))
        assertTrue(alphabet(3).contains('*') && alphabet(3).contains('#') && alphabet(3).contains('+'))
        assertTrue(alphabet(4 or 0x20).contains(':'))
        assertTrue(alphabet(4 or 0x10).contains('/'))
        assertNull(ImeKeyLayout.numericRows(1))
    }
    @Test fun actionLabelAndDispatchShareOneContract() {
        val send = ImeResolvedAction.resolve(4, null, 0)
        assertEquals("Send", send.label); assertEquals(4, send.actionId)
        val multiline = ImeResolvedAction.resolve(4 or 0x40000000, null, 0)
        assertEquals("Enter", multiline.label); assertNull(multiline.actionId)
        val custom = ImeResolvedAction.resolve(0, "Run fixture", 42)
        assertEquals("Run fixture", custom.label); assertEquals(42, custom.actionId)
        assertEquals("Enter", ImeResolvedAction.resolve(1, null, 0).label)
    }
}
