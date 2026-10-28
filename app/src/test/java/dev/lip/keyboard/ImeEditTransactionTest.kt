package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class ImeEditTransactionTest {
    private class Fixture(initial:String="") {
        val journal=ImeEditJournal()
        var time=0L
        val tx=ImeEditTransaction(journal){time}
        val text=StringBuilder(initial)
        var start=initial.length;var end=start
        var normalizeSelection=false;var observedStart=start;var observedEnd=end;var observedStamp=0L
        var normalizeRequestedSelection=false
        var eligible=true;var notify=true;var selectionAccepted=true;var applySelection=true
        var writeAccepted=true;var applyWrite=true;var transform:(String)->String={it}
        var reads=0;var writes=0;var selections=0;var maxWindow=0;var readDelay=0L
        var onRead:(()->Unit)?=null;var onSelect:(()->Unit)?=null;var onWrite:(()->Unit)?=null
        val port=object:ImeEditTransaction.Port {
            override fun eligible()=eligible
            override fun read(before:Int,after:Int):ImeEditWindow? {
                reads++;time+=readDelay;onRead?.invoke()
                val left=maxOf(0,minOf(start,end)-before);val right=minOf(text.length,maxOf(start,end)+after)
                maxWindow=maxOf(maxWindow,right-left)
                val window=ImeEditWindow(left,text.substring(left,right),if(normalizeSelection)minOf(start,end)else start,if(normalizeSelection)maxOf(start,end)else end,selectionStamp=observedStamp)
                return if(normalizeSelection)ImeEditSelection.observed(window,observedStart,observedEnd,true,observedStamp)else window
            }
            override fun selectionStamp()=observedStamp
            override fun select(start:Int,end:Int):Boolean {
                selections++;onSelect?.invoke()
                if(applySelection){
                    this@Fixture.start=if(normalizeRequestedSelection)minOf(start,end)else start
                    this@Fixture.end=if(normalizeRequestedSelection)maxOf(start,end)else end
                    if(notify){observedStart=this@Fixture.start;observedEnd=this@Fixture.end;observedStamp++;tx.selection(observedStart,observedEnd,this)}
                }
                return selectionAccepted
            }
            override fun replace(text:String):Boolean {
                writes++;onWrite?.invoke()
                if(applyWrite){val value=transform(text);val at=minOf(start,end);this@Fixture.text.replace(at,maxOf(start,end),value);start=at+value.length;end=start;if(notify){observedStart=start;observedEnd=end;observedStamp++;tx.selection(start,end,this)}}
                return writeAccepted
            }
        }
        init{tx.bind(Any())}
        fun insert(value:String,kind:ImeEditJournal.Kind=ImeEditJournal.Kind.TYPING)=tx.edit(port,value,kind){port.replace(value)}
        fun delete(count:Int)=tx.edit(port,"",ImeEditJournal.Kind.DELETE,removeBefore=count){start-=count;port.replace("")}
        fun undo()=tx.replay(port,ImeEditJournal.Direction.UNDO)
        fun redo()=tx.replay(port,ImeEditJournal.Direction.REDO)
    }
    @Test fun rapidTypingCoalescesWithoutQueuingOrDroppingAnyDispatch() {
        val f=Fixture()
        "hello world ".forEach{assertTrue(f.insert(it.toString()))}
        assertEquals("hello world ",f.text.toString());assertEquals(12,f.writes);assertEquals(2,f.journal.undoCount)
        assertTrue(f.undo());assertEquals("hello ",f.text.toString());assertTrue(f.undo());assertEquals("",f.text.toString())
        assertTrue(f.redo());assertTrue(f.redo());assertEquals("hello world ",f.text.toString())
        assertFalse(f.redo());assertEquals(16,f.writes)
    }
    @Test fun everyOwnedTextKindAndUnicodeSelectionRestoreExactText() {
        for(kind in ImeEditJournal.Kind.entries){
            val f=Fixture("prefix ORIGINAL suffix");f.start=7;f.end=15
            assertTrue(f.insert("👩🏽‍💻 café\n",kind));val updated=f.text.toString()
            assertTrue(f.undo());assertEquals("prefix ORIGINAL suffix",f.text.toString());assertEquals(7,f.start);assertEquals(15,f.end)
            assertTrue(f.redo());assertEquals(updated,f.text.toString())
        }
    }
    @Test fun graphemeAndWordDeletionCoalesceAndSelectedDeletionRestores() {
        val f=Fixture("go 🙂 now")
        assertTrue(f.delete(3));assertTrue(f.delete(1));assertEquals("go 🙂",f.text.toString())
        assertEquals(1,f.journal.undoCount);assertTrue(f.undo());assertEquals("go 🙂 now",f.text.toString())
        val selected=Fixture("left selected right");selected.start=5;selected.end=13
        assertTrue(selected.insert("",ImeEditJournal.Kind.DELETE));assertTrue(selected.undo());assertEquals("left selected right",selected.text.toString())
    }
    @Test fun newEditBranchesAndNeverReplaysDiscardedRedo() {
        val f=Fixture();f.insert("one",ImeEditJournal.Kind.SWIPE);f.insert("two",ImeEditJournal.Kind.DICTATION)
        assertTrue(f.undo());assertTrue(f.journal.canRedo);f.insert("branch",ImeEditJournal.Kind.CLIP)
        assertFalse(f.journal.canRedo);assertFalse(f.redo());assertEquals("onebranch",f.text.toString())
    }
    @Test fun noEntryCountCapAndReadsStayBoundedRatherThanWholeDocument() {
        val f=Fixture()
        repeat(5_000){assertTrue(f.insert("x",ImeEditJournal.Kind.EMOJI))}
        assertEquals(5_000,f.journal.undoCount);assertTrue(f.maxWindow<=33)
        repeat(5_000){assertTrue(f.undo())}
        assertEquals("",f.text.toString());assertEquals(5_000,f.journal.redoCount)
    }
    @Test fun missingPrivateOrSlowReaderNeverDropsManualKeyButNeverJournals() {
        val private=Fixture();private.eligible=false;assertTrue(private.insert("secret"));assertEquals(0,private.reads);assertFalse(private.journal.canUndo)
        val slow=Fixture();slow.readDelay=250;assertTrue(slow.insert("key"));assertEquals("key",slow.text.toString());assertFalse(slow.journal.canUndo)
        val oversized=Fixture();assertTrue(oversized.insert("a".repeat(ImeEditTransaction.MAX_EDIT+1)));assertEquals(1,oversized.writes);assertEquals(0,oversized.reads);assertFalse(oversized.journal.canUndo)
    }
    @Test fun ambiguousWriteAndHostTransformClearReplayAuthorityWithoutRetry() {
        val f=Fixture();f.insert("previous",ImeEditJournal.Kind.SWIPE);f.writeAccepted=false
        assertFalse(f.insert("maybe"));assertEquals("previousmaybe",f.text.toString());assertEquals(2,f.writes);assertFalse(f.undo());assertFalse(f.redo())
        val changed=Fixture();changed.transform={it.uppercase()};assertTrue(changed.insert("typed"));assertEquals("TYPED",changed.text.toString());assertFalse(changed.journal.canUndo)
        assertEquals(ImeEditTransaction.Notice.UNCONFIRMED,changed.tx.notice)
    }
    @Test fun inverseRequiresObservedSelectionNotBooleanAndConsumesAmbiguityOnce() {
        val f=Fixture();f.insert("hello",ImeEditJournal.Kind.SWIPE);f.applySelection=false
        assertFalse(f.undo());assertEquals(1,f.writes);assertFalse(f.undo());assertEquals(1,f.selections)
        val ambiguous=Fixture();ambiguous.insert("hello",ImeEditJournal.Kind.SWIPE);ambiguous.writeAccepted=false
        assertFalse(ambiguous.undo());assertEquals("",ambiguous.text.toString());assertFalse(ambiguous.redo());assertEquals(2,ambiguous.writes)
    }
    @Test fun staleCaretAbaExternalTextEditorAndUnknownCompositionRefuse() {
        val f=Fixture();f.insert("hello");f.tx.expireSelectionAcknowledgements();f.tx.selection(0,0);f.tx.selection(5,5)
        assertFalse(f.undo());assertEquals("hello",f.text.toString())
        val changed=Fixture();changed.insert("hello");changed.text.setCharAt(0,'H');assertFalse(changed.undo());assertEquals(1,changed.writes)
        val editor=Fixture();editor.insert("hello");editor.tx.bind(Any());assertFalse(editor.undo())
        val composing=Fixture();composing.insert("hello");composing.tx.composition(0,5);assertFalse(composing.undo())
    }
    @Test fun ownershipPublishedBeforeReentrantCallbacksAndContextDepartureStopsReplay() {
        val f=Fixture();assertTrue(f.insert("hello"));assertTrue(f.undo());assertTrue(f.redo()) // Synchronous callbacks in both select/replace.
        f.onSelect={f.eligible=false;f.tx.barrier()}
        assertFalse(f.undo());assertEquals(3,f.writes)
        val during=Fixture();during.onWrite={during.tx.bind(Any())};assertTrue(during.insert("manual"));assertFalse(during.journal.canUndo)
    }
    @Test fun coalescedDelayedOwnSelectionCallbacksAreNotExternalCaretMoves() {
        val f=Fixture();f.notify=false;f.insert("a");f.insert("b");f.insert("c")
        f.tx.selection(1,1,f.port);f.tx.selection(3,3,f.port)
        assertTrue(f.journal.canUndo);assertTrue(f.undo());assertEquals("",f.text.toString())
    }
    @Test fun resourceFailurePausesWithoutHiddenEvictionOrPersistentArchive() {
        val f=Fixture();f.insert("old");f.journal.resourceFailure()
        assertTrue(f.insert("new"));assertEquals("oldnew",f.text.toString());assertFalse(f.undo());assertEquals(ImeEditTransaction.Notice.RESOURCE,f.tx.notice)
        f.tx.bind(Any());assertFalse(f.journal.resourceUnavailable);assertFalse(f.journal.canUndo)
        assertFalse(f.journal.toString().contains("oldnew"));assertFalse(ImeEditJournal.Edit(0,"private","other","","",ImeEditJournal.Kind.CLIP).toString().contains("private"))
    }
    @Test fun inverseRestoresReversedSelectionAndReentrantReplayCannotClaimTwice() {
        val selected=Fixture("left selected right");selected.start=13;selected.end=5
        assertTrue(selected.insert("new",ImeEditJournal.Kind.REPLACEMENT));assertTrue(selected.undo())
        assertEquals("left selected right",selected.text.toString());assertEquals(13,selected.start);assertEquals(5,selected.end)
        assertTrue(selected.redo());assertEquals("left new right",selected.text.toString())
        val reentrant=Fixture();reentrant.insert("owned",ImeEditJournal.Kind.SWIPE)
        var nested:Boolean?=null
        reentrant.onRead={reentrant.onRead=null;nested=reentrant.undo()}
        assertTrue(reentrant.undo());assertEquals(false,nested);assertEquals(2,reentrant.writes)
        val manual=Fixture();manual.insert("owned",ImeEditJournal.Kind.SWIPE)
        manual.onRead={manual.onRead=null;manual.insert("new",ImeEditJournal.Kind.EMOJI)}
        assertFalse(manual.undo());assertEquals("ownednew",manual.text.toString());assertEquals(2,manual.writes)
    }

    @Test fun ownedWordSelectionPreservesEarlierJournalAndRestoresOriginalCaret() {
        val f=Fixture();f.insert("hello world ",ImeEditJournal.Kind.SWIPE)
        val old=f.start
        assertTrue(f.tx.expectSelection(6,old,ImeEditWindow(0,f.text.toString(),old,old)));f.port.select(6,old)
        assertTrue(f.tx.edit(f.port,"",ImeEditJournal.Kind.DELETE,originalSelection=old to old){f.port.replace("")})
        assertTrue(f.undo());assertEquals("hello world ",f.text.toString());assertEquals(old,f.start);assertEquals(old,f.end)
        assertTrue(f.undo());assertEquals("",f.text.toString())
    }
    @Test fun syntheticAllocationRefusalPausesAndManualKeysStillDispatchOnce() {
        val f=Fixture();f.onRead={f.onRead=null;throw OutOfMemoryError("synthetic allocation refusal")}
        assertTrue(f.insert("manual"));assertEquals(1,f.writes);assertTrue(f.journal.resourceUnavailable)
        val reads=f.reads;assertTrue(f.insert("more"));assertEquals(reads,f.reads);assertEquals(2,f.writes)
        assertEquals(ImeEditTransaction.Notice.RESOURCE,f.tx.notice)
    }

    @Test fun largeSelectedDeltaReadsOnlyRelevantSpanAndRestoresBothDirections() {
        val prefix="p".repeat(50_000);val removed="r".repeat(50_000)
        val f=Fixture(prefix+removed+" suffix");f.start=prefix.length;f.end=prefix.length+removed.length
        assertTrue(f.insert("new",ImeEditJournal.Kind.REPLACEMENT));assertTrue(f.undo())
        assertEquals(prefix+removed+" suffix",f.text.toString());assertEquals(50_000,f.start);assertEquals(100_000,f.end)
        assertTrue(f.redo());assertEquals(prefix+"new suffix",f.text.toString());assertTrue(f.maxWindow<=50_064)
    }

    @Test fun pendingCoordinateDebtCannotAuthorizeRealCaretAba() {
        val f=Fixture();f.notify=false;f.insert("a");f.insert("b")
        f.start=1;f.end=1;f.tx.selection(1,1,f.port)
        f.start=2;f.end=2;f.tx.selection(2,2,f.port)
        assertFalse(f.undo());assertEquals("ab",f.text.toString());assertEquals(2,f.writes)
        val legitimate=Fixture();legitimate.notify=false;legitimate.insert("a");legitimate.insert("b")
        legitimate.tx.selection(1,1,legitimate.port) // Late ACK, actual head is still caret2.
        legitimate.tx.selection(2,2,legitimate.port)
        assertTrue(legitimate.undo());assertEquals("",legitimate.text.toString())
    }

    @Test fun normalizedHostRangeNeedsObservedDirectionAndDeferredFrameworkAck() {
        val f=Fixture("LEFT 😀 RIGHT");f.start=7;f.end=5;f.observedStart=7;f.observedEnd=5;f.normalizeSelection=true
        assertTrue(f.insert("",ImeEditJournal.Kind.DELETE));assertEquals("LEFT  RIGHT",f.text.toString())
        f.notify=false
        assertFalse(f.undo()) // Text restored, direction is not yet confirmed by framework.
        assertEquals("LEFT 😀 RIGHT",f.text.toString());assertEquals(7,f.start);assertEquals(5,f.end)
        assertTrue(f.tx.busy);assertFalse(f.journal.canRedo)
        val writes=f.writes;val selections=f.selections
        f.observedStart=7;f.observedEnd=7;f.observedStamp++;f.tx.selection(7,7,f.port) // Delayed ACK of replacement, actual reverse range already applied.
        assertTrue(f.tx.busy);assertFalse(f.journal.canRedo)
        f.observedStart=7;f.observedEnd=5;f.observedStamp++;f.tx.selection(7,5,f.port)
        assertFalse(f.tx.busy);assertTrue(f.journal.canRedo);assertEquals(writes,f.writes);assertEquals(selections,f.selections)
        f.notify=true;assertTrue(f.redo());assertEquals("LEFT  RIGHT",f.text.toString())
    }
    @Test fun normalizedBoundsAloneNeverConfirmWrongDirectionAndTimeoutNeverRetries() {
        val raw=ImeEditWindow(0,"LEFT 😀 RIGHT",5,7)
        assertFalse(ImeEditSelection.observed(raw,7,5,false).directionVerified)
        assertFalse(ImeEditSelection.observed(raw,8,5,true).directionVerified)
        assertEquals(7,ImeEditSelection.observed(raw,7,5,true).start)
        val f=Fixture("LEFT 😀 RIGHT");f.start=7;f.end=5;f.observedStart=7;f.observedEnd=5;f.normalizeSelection=true
        f.insert("",ImeEditJournal.Kind.DELETE);f.notify=false;assertFalse(f.undo());val writes=f.writes
        f.observedStart=5;f.observedEnd=7;f.observedStamp++;f.tx.selection(5,7,f.port)
        assertFalse(f.tx.busy);assertFalse(f.journal.canRedo);assertEquals(writes,f.writes)
        val timeout=Fixture("LEFT 😀 RIGHT");timeout.start=7;timeout.end=5;timeout.observedStart=7;timeout.observedEnd=5;timeout.normalizeSelection=true
        timeout.insert("",ImeEditJournal.Kind.DELETE);timeout.notify=false;assertFalse(timeout.undo());val requests=timeout.selections
        timeout.time+=251;timeout.tx.expirePendingReplay();assertFalse(timeout.tx.busy);assertFalse(timeout.journal.canRedo)
        assertEquals(requests,timeout.selections);assertEquals(2,timeout.writes)
    }

    @Test fun redoIntermediateSpanAcceptsNormalizedBoundsWithStaleReverseMetadata() {
        val f=Fixture("LEFT 😀 RIGHT");f.start=7;f.end=5;f.observedStart=7;f.observedEnd=5;f.normalizeSelection=true
        assertTrue(f.insert("",ImeEditJournal.Kind.DELETE));assertTrue(f.undo())
        assertEquals("LEFT 😀 RIGHT",f.text.toString());assertEquals(7,f.start);assertEquals(5,f.end)
        f.notify=false // setSelection(5,7) applies, but framework still reports7,5.
        assertTrue(f.redo());assertEquals("LEFT  RIGHT",f.text.toString());assertEquals(5,f.start);assertEquals(5,f.end)
        assertEquals(3,f.writes);assertFalse(f.journal.canRedo);assertTrue(f.journal.canUndo)
    }

    @Test fun stalePreForwardReverseObservationNeverConfirmsWrongFinalDirection() {
        val f=Fixture("LEFT 😀 RIGHT");f.start=7;f.end=5;f.observedStart=7;f.observedEnd=5;f.normalizeSelection=true
        f.notify=false // Framework retains original7,5 throughout deletion and Undo.
        assertTrue(f.insert("",ImeEditJournal.Kind.DELETE))
        f.normalizeRequestedSelection=true // Actual host applies WRONG5,7, not requested7,5.
        assertFalse(f.undo());assertTrue(f.tx.busy);assertFalse(f.journal.canRedo)
        assertEquals(5,f.start);assertEquals(7,f.end)
        // The stale pre-forward7,5 observation still corresponds to fresh unordered5,7.
        f.time+=251;f.tx.expirePendingReplay()
        assertFalse(f.tx.busy);assertFalse(f.journal.canRedo);assertEquals(2,f.writes)
    }
    @Test fun slowFinalSelectCannotCompleteEvenIfDirectionCallbackEventuallyMatches() {
        val f=Fixture("LEFT 😀 RIGHT");f.start=7;f.end=5;f.observedStart=7;f.observedEnd=5;f.normalizeSelection=true
        assertTrue(f.insert("",ImeEditJournal.Kind.DELETE))
        f.onSelect={if(f.selections==2)f.time+=251} // First select is replacement span; second restores direction.
        assertFalse(f.undo());assertFalse(f.tx.busy);assertFalse(f.journal.canRedo)
        assertEquals("LEFT 😀 RIGHT",f.text.toString());assertEquals(2,f.writes);assertEquals(2,f.selections)
    }

}
