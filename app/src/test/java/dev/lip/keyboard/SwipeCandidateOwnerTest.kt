package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class SwipeCandidateOwnerTest {
    private val base=ImeCursor("test.fixture",3,5,5,"LEFT ","RIGHT",false)
    private fun after(text:String)=base.copy(start=5+text.length,end=5+text.length,before=(base.before+text).takeLast(32))
    @Test fun claimedContinuationRefusesObservedAbaWithoutReclaiming() {
        val session=ImeSession();session.beginEditor();val ticket=session.capture(base)!!
        assertFalse(session.claimedContextCurrent(ticket,base));assertTrue(session.claim(ticket,base))
        assertTrue(session.claimedContextCurrent(ticket,base));assertFalse(session.claim(ticket,base))
        session.editorChanged();assertFalse(session.claimedContextCurrent(ticket,base))
    }
    @Test fun exactOwnedSpanIsConfirmedAndClaimedOnce() {
        val session=ImeSession();session.beginEditor();val owner=SwipeCandidateOwner(session);val conn=Any()
        val state=owner.stage(base,"hello ",listOf("hello ","help "),conn)!!
        assertFalse(state.ready);assertTrue(owner.ownsSelection(11,11));assertFalse(owner.ownsSelection(11,11))
        session.editorChanged();assertTrue(owner.confirm(state,after("hello "),"hello ",conn))
        assertEquals(5,state.start);assertEquals(11,state.end)
        assertEquals("help ",owner.claim(state,1,after("hello "),"hello ",conn))
        assertNull(owner.claim(state,1,after("hello "),"hello ",conn))
    }
    @Test fun typedSnapshotConfirmationDoesNotInventOrEraseOwnSelectionAck(){
        val session=ImeSession();session.beginEditor();val owner=SwipeCandidateOwner(session);val conn=Any()
        val state=owner.stage(base,"hello ",listOf("hello ","help "),conn,CandidateOrigin.TYPED)!!
        assertTrue(owner.confirm(state,after("hello "),"hello ",conn))
        assertTrue(owner.ownsSelection(11,11));assertFalse(owner.ownsSelection(11,11))
        session.editorChanged();assertNull(owner.claim(state,1,after("hello "),"hello ",conn))
        val refreshed=owner.stage(base,"hello ",listOf("hello ","help "),conn,CandidateOrigin.TYPED,false)!!
        assertFalse(owner.ownsSelection(11,11))
        assertTrue(owner.confirm(refreshed,after("hello "),"hello ",conn));assertFalse(owner.ownsSelection(11,11))
    }
    @Test fun deliberateSpellingPermissionDoesNotChangeDuringConfirmationOrAck(){
        val session=ImeSession();session.beginEditor();val owner=SwipeCandidateOwner(session);val conn=Any()
        val state=owner.stage(base,"hello ",listOf("hello ","help "),conn,CandidateOrigin.TYPED,true,false)!!
        assertFalse(state.automaticAllowed);assertTrue(owner.confirm(state,after("hello "),"hello ",conn));assertFalse(state.automaticAllowed)
        assertTrue(owner.ownsSelection(11,11));assertFalse(state.automaticAllowed)
        assertEquals("help ",owner.claim(state,1,after("hello "),"hello ",conn))
    }
    @Test fun cursorAbaConnectionAndEpochInvalidateClaims() {
        for(mode in 0..3) {
            val session=ImeSession();session.beginEditor();val owner=SwipeCandidateOwner(session);val conn=Any()
            val state=owner.stage(base,"hello ",listOf("hello ","help "),conn)!!
            assertTrue(owner.confirm(state,after("hello "),"hello ",conn))
            when(mode){0->session.editorChanged();1->session.beginEditor();2->owner.clear()}
            assertNull(owner.claim(state,1,after("hello "),"hello ",if(mode==3)Any() else conn))
        }
    }
    @Test fun partialSuffixMatchCannotQualifyLongOwnedWord() {
        val session=ImeSession();session.beginEditor();val owner=SwipeCandidateOwner(session);val conn=Any()
        val long="a".repeat(40)+" ";val state=owner.stage(base,long,listOf(long,"help "),conn)!!
        assertFalse(owner.confirm(state,after(long),long.takeLast(32),conn))
        assertNull(owner.current)
    }
    @Test fun foreignOrChangedTextAndStaleButtonsCannotReplace() {
        val session=ImeSession();session.beginEditor();val owner=SwipeCandidateOwner(session);val conn=Any()
        val old=owner.stage(base,"hello ",listOf("hello ","help "),conn)!!
        val fresh=owner.stage(base,"world ",listOf("world "),conn)!!
        assertNull(owner.claim(old,0,after("hello "),"hello ",conn));assertSame(fresh,owner.current)
        assertFalse(owner.confirm(fresh,after("world "),"OTHER ",conn));assertNull(owner.current)
    }
}
