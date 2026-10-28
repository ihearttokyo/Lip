package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class ClipboardHistoryConsentTest{
    @Test fun failedCommitCacheCannotReenableReconstructedControllerOwner(){
        var state=ClipboardConsent(false,false);var succeeds=false;val owner=ClipboardConsentOwner()
        val commit:(ClipboardConsent)->Boolean={state=it;succeeds}
        val first=ClipboardHistoryConsent({state},commit,owner)
        assertFalse(first.enable());assertTrue(state.enabled);assertFalse(first.capturePermitted())
        val reconstructed=ClipboardHistoryConsent({state},commit,owner)
        assertTrue(reconstructed.persistenceFailed);assertFalse(reconstructed.capturePermitted())
        succeeds=true;assertTrue(reconstructed.retryFailed());assertTrue(reconstructed.capturePermitted())
    }
    @Test fun pendingEraseCannotBeOverwrittenByEnable(){
        var state=ClipboardConsent(false,true,"pending");var writes=0
        val consent=ClipboardHistoryConsent({state},{state=it;writes++;true},ClipboardConsentOwner())
        assertFalse(consent.enable());assertEquals(0,writes)
    }
    @Test fun inFlightWriteThenRevokeAndRestartRepairsBeforeResuming(){
        var state=ClipboardConsent(true,false);var payload:String?=null;var token:String?=null
        val owner=ClipboardConsentOwner();val consent=ClipboardHistoryConsent({state},{state=it;true},owner)
        val repository=ClipboardHistoryRepository({payload},{payload=it;token=consent.requestErase(false)},{payload=null})
        assertThrows(IllegalStateException::class.java){repository.update(1,{consent.capturePermitted()})}
        assertNotNull(payload);assertTrue(state.erasing);assertNotNull(token)
        val restarted=ClipboardHistoryConsent({state},{state=it;true},ClipboardConsentOwner())
        assertEquals(ClipboardEraseResult.APPLIED,restarted.finishErase(repository,token!!));assertNull(payload)
        assertFalse(state.enabled);assertFalse(state.erasing);assertFalse(restarted.capturePermitted())
    }
    @Test fun oldClearFinalizerCannotOverwriteNewerDisableOrEraseItsSuccessor(){
        var state=ClipboardConsent(true,false);var payload:String?="original";var erasures=0;val owner=ClipboardConsentOwner()
        val old=ClipboardHistoryConsent({state},{state=it;true},owner)
        val newer=ClipboardHistoryConsent({state},{state=it;true},owner)
        val repo=ClipboardHistoryRepository({payload},{payload=it},{payload=null;erasures++})
        val oldToken=old.requestErase(true)!!;val disable=newer.requestErase(false)!!
        assertEquals(ClipboardEraseResult.STALE,old.finishErase(repo,oldToken))
        assertEquals(0,erasures);assertEquals(disable,state.token);assertTrue(state.erasing)
        assertEquals(ClipboardEraseResult.APPLIED,newer.finishErase(repo,disable));assertFalse(state.enabled)
        assertTrue(newer.enable());payload="newly-consented-copy"
        assertEquals(ClipboardEraseResult.STALE,old.finishErase(repo,oldToken));assertEquals("newly-consented-copy",payload)
    }
    @Test fun eraseFailureKeepsDurableTombstoneAndOriginalData(){
        var state=ClipboardConsent(false,true,"token");val original=ClipboardHistory.encode(emptyList())
        val consent=ClipboardHistoryConsent({state},{state=it;true},ClipboardConsentOwner())
        val repository=ClipboardHistoryRepository({original},{},{throw IllegalStateException("Injected erasure refusal")})
        assertThrows(IllegalStateException::class.java){consent.finishErase(repository,"token")}
        assertTrue(state.erasing);assertFalse(consent.capturePermitted())
    }
    @Test fun completedEraseWithFailedFlagCommitCannotResumeAfterReconstruction(){
        var state=ClipboardConsent(true,true,"token");var payload:String?=ClipboardHistory.encode(emptyList());val owner=ClipboardConsentOwner()
        val consent=ClipboardHistoryConsent({state},{state=it;false},owner)
        val repository=ClipboardHistoryRepository({payload},{payload=it},{payload=null})
        assertEquals(ClipboardEraseResult.FAILED,consent.finishErase(repository,"token"));assertNull(payload)
        assertFalse(state.erasing) // Even this misleading cache must not authorize capture.
        val reconstructed=ClipboardHistoryConsent({state},{state=it;true},owner)
        assertFalse(reconstructed.capturePermitted());assertTrue(reconstructed.persistenceFailed)
        assertTrue(reconstructed.retryFailed());assertTrue(reconstructed.capturePermitted())
    }
}
