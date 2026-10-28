package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class WordDeleteTransactionTest {
    private class Host:WordDeleteTransaction.Port {
        var time=10L
        var readDelay=0L
        var throwRead=false;var throwSelect=false;var throwDelete=false;var throwValid=false
        fun at(now:Long,action:()->Unit){time=now;action()}
        var window=WordDeleteWindow(0,"one two three",13,13)
        var eligible=true;var acceptSelect=true;var acceptDelete=true;var applySelect=true
        var selects=0;var deletes=0;var selected:Pair<Int,Int>?=null
        var onSelect:(()->Unit)?=null;var onDelete:(()->Unit)?=null
        override fun valid():Boolean {if(throwValid)throw IllegalStateException();return eligible}
        override fun read():WordDeleteWindow {if(throwRead)throw IllegalStateException();time+=readDelay;return window}
        override fun select(start:Int,end:Int):Boolean{selects++;if(throwSelect)throw IllegalStateException();selected=start to end;if(applySelect)window=window.copy(start=start,end=end);onSelect?.invoke();return acceptSelect}
        override fun deleteSelection():Boolean{deletes++;if(throwDelete)throw IllegalStateException();onDelete?.invoke();return acceptDelete}
        fun tx()=WordDeleteTransaction(window,listOf(8,4,0),this,{time})
    }
    @Test fun selectionIsOnlyPreviewUntilReleased(){val h=Host();val t=h.tx();h.at(20){t.move(-1)};assertEquals(1,h.selects);assertEquals(0,h.deletes);assertEquals(WordDeleteTransaction.State.WAITING,t.state);h.at(30){t.advance()};assertEquals(0,h.deletes);h.at(40){t.release()};assertEquals(1,h.deletes);assertEquals(WordDeleteTransaction.State.DISPATCHED,t.state);h.at(50){t.release()};h.at(60){t.advance()};assertEquals(1,h.deletes)}
    @Test fun releaseWaitsForActualAckNotBoolean(){val h=Host();h.applySelect=false;val t=h.tx();h.at(20){t.move(-1)};h.at(30){t.release()};assertEquals(0,h.deletes);h.window=h.window.copy(start=8);h.at(40){t.advance()};assertEquals(1,h.deletes)}
    @Test fun timeoutNeverRetriesOrDeletes(){val h=Host();h.applySelect=false;val t=h.tx();h.at(20){t.move(-1)};h.at(30){t.release()};h.at(270){t.advance()};assertEquals(WordDeleteTransaction.State.CANCELED,t.state);h.window=h.window.copy(start=8);h.at(280){t.advance()};assertEquals(0,h.deletes);assertEquals(1,h.selects)}
    @Test fun reverseMotionCoalescesAndZeroReleasePreservesText(){val h=Host();val t=h.tx();h.at(20){t.move(-2)};h.at(21){t.move(1)};h.at(30){t.advance()};assertEquals(8,h.selected!!.first);h.at(40){t.advance()};h.at(50){t.move(1)};h.at(60){t.advance()};h.at(70){t.release()};assertEquals(0,h.deletes);assertEquals(13,h.window.start)}
    @Test fun refusalIsTerminalEvenIfActuallyApplied(){val h=Host();h.acceptSelect=false;val t=h.tx();h.at(20){t.move(-1)};h.at(30){t.release()};h.at(40){t.advance()};assertEquals(WordDeleteTransaction.State.REFUSED,t.state);assertEquals(1,h.selects);assertEquals(0,h.deletes)}
    @Test fun commitFailureCannotRetry(){val h=Host();h.acceptDelete=false;val t=h.tx();h.at(20){t.move(-1)};h.at(30){t.release()};h.at(40){t.release()};h.at(50){t.advance()};assertEquals(1,h.deletes);assertEquals(WordDeleteTransaction.State.REFUSED,t.state)}
    @Test fun reentrantCancellationCannotBeOverwritten(){val h=Host();val t=h.tx();h.onSelect={t.cancel()};h.at(20){t.move(-1)};h.at(30){t.release()};assertEquals(WordDeleteTransaction.State.CANCELED,t.state);assertEquals(0,h.deletes)}
    @Test fun unrelatedSelectionRevokesEvenIfReturned(){val h=Host();val t=h.tx();h.at(20){t.move(-1)};t.selectionChanged(4,13);t.selectionChanged(8,13);h.at(30){t.release()};assertEquals(0,h.deletes)}
    @Test fun changedTextSameCaretRefuses(){val h=Host();val t=h.tx();h.at(20){t.move(-1)};h.window=h.window.copy(text="ONE two three");h.at(30){t.release()};assertEquals(0,h.deletes);assertEquals(WordDeleteTransaction.State.CANCELED,t.state)}
    @Test fun epochConnectionOrPolicyLossBeforeCommitRefuses(){val h=Host();val t=h.tx();h.at(20){t.move(-1)};h.at(30){t.advance()};h.eligible=false;h.at(40){t.release()};assertEquals(0,h.deletes)}
    @Test fun malformedOrIncompleteSnapshotRefuses(){for(bad in listOf(WordDeleteWindow(-1,"one two three",8,13),WordDeleteWindow(4,"two three",8,13),WordDeleteWindow(0,"one two three",8,12),WordDeleteWindow(0,"x".repeat(193),8,13))){val h=Host();val t=h.tx();h.at(20){t.move(-1)};h.window=bad;h.at(30){t.release()};assertEquals(0,h.deletes)}}
    @Test fun contactExpiryAndBackwardsClockRefuse(){for(time in listOf(9L,30_011L)){val h=Host();val t=h.tx();h.at(20){t.move(-1)};h.at(time){t.release()};assertEquals(0,h.deletes);assertEquals(WordDeleteTransaction.State.CANCELED,t.state)}}
    @Test fun adversarialDeltaOutsideGeometryBudgetRefuses(){val h=Host();val t=h.tx();h.at(20){t.move(Int.MIN_VALUE)};h.at(30){t.release()};assertEquals(0,h.deletes);assertEquals(WordDeleteTransaction.State.CANCELED,t.state)}
    @Test fun extendedReadMayContainOriginalButNotRewriteIt(){val h=Host();h.window=WordDeleteWindow(2,"one two three",15,15);val t=WordDeleteTransaction(h.window,listOf(10,6,2),h,{h.time});h.at(20){t.move(-1)};h.window=WordDeleteWindow(0,"XXone two threeZZ",10,15);h.at(30){t.release()};assertEquals(1,h.deletes)}
    @Test fun lateAckEvenIfAppliedIsNotRescued(){val h=Host();val t=h.tx();h.at(20){t.move(-1)};h.at(271){t.release()};assertEquals(0,h.deletes);assertEquals(WordDeleteTransaction.State.CANCELED,t.state)}
    @Test fun selectReentrantReleaseWaitsForReturnValue(){val h=Host();val t=h.tx();h.onSelect={h.at(21){t.release()}};h.acceptSelect=false;h.at(20){t.move(-1)};h.at(30){t.advance()};assertEquals(0,h.deletes);assertEquals(WordDeleteTransaction.State.REFUSED,t.state)}
    @Test fun crossingOriginAndReturningDownNeverDeletes(){val h=Host();val t=h.tx();h.at(20){t.move(-1)};h.at(30){t.advance()};h.at(40){t.move(2)};h.at(50){t.advance()};h.at(60){t.move(-1)};h.at(70){t.release()};assertEquals(0,t.words);assertEquals(0,h.deletes);assertEquals(13,h.window.start)}
    @Test fun upperBoundOvershootRetainsAbsoluteDepth(){val h=Host();val t=h.tx();h.at(20){t.move(-6)};h.at(30){t.advance()};h.at(40){t.move(2)};assertEquals(3,t.words);h.at(50){t.release()};assertEquals(1,h.deletes)}
    @Test fun blockingAckReadCannotBypassDeadline(){val h=Host();val t=h.tx();h.at(20){t.move(-1)};h.readDelay=251;h.at(30){t.release()};assertEquals(0,h.deletes);assertEquals(WordDeleteTransaction.State.CANCELED,t.state)}
    @Test fun blockingFinalReadCannotCommitStaleSelection(){val h=Host();val t=h.tx();h.at(20){t.move(-1)};h.at(30){t.advance()};h.readDelay=251;h.at(40){t.release()};assertEquals(0,h.deletes);assertEquals(WordDeleteTransaction.State.CANCELED,t.state)}
    @Test fun readAndValidityExceptionsCancelWithoutMutation(){for(mode in 0..1){val h=Host();val t=h.tx();if(mode==0)h.throwRead=true else h.throwValid=true;h.at(20){t.move(-1)};h.at(30){t.release()};assertEquals(0,h.selects);assertEquals(0,h.deletes);assertEquals(WordDeleteTransaction.State.CANCELED,t.state)}}
    @Test fun selectionExceptionIsOneAmbiguousAttempt(){val h=Host();val t=h.tx();h.throwSelect=true;h.at(20){t.move(-1)};h.at(30){t.release()};assertEquals(1,h.selects);assertEquals(0,h.deletes);assertEquals(WordDeleteTransaction.State.REFUSED,t.state)}
    @Test fun deletionExceptionNeverRetries(){val h=Host();val t=h.tx();h.throwDelete=true;h.at(20){t.move(-1)};h.at(30){t.release()};h.at(40){t.advance()};assertEquals(1,h.deletes);assertEquals(WordDeleteTransaction.State.REFUSED,t.state)}
}
