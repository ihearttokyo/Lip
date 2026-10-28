package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class WordDeleteCaptureTest {
    private val window=WordDeleteWindow(0,"one two",7,7)
    @Test fun validInitialCaptureRetainsExactWindow(){assertEquals(window,WordDeleteCapture.read(0,{10},{true}){window})}
    @Test fun initialReadExceptionFailsClosed(){assertNull(WordDeleteCapture.read(0,{10},{true}){throw IllegalStateException()})}
    @Test fun initialEligibilityExceptionFailsClosed(){var reads=0;assertNull(WordDeleteCapture.read(0,{10},{throw IllegalStateException()}){reads++;window});assertEquals(0,reads)}
    @Test fun slowInitialReadCannotStartTransaction(){var time=10L;assertNull(WordDeleteCapture.read(0,{time},{true}){time+=251;window})}
    @Test fun elapsedContactIncludesTimeBeforeCapture(){var reads=0;assertNull(WordDeleteCapture.read(0,{30_001},{true}){reads++;window});assertEquals(0,reads)}
    @Test fun backwardsExecutionClockRefuses(){var time=10L;assertNull(WordDeleteCapture.read(0,{time},{true}){time=9;window})}
    @Test fun changedEligibilityAfterReadRefuses(){var eligible=true;assertNull(WordDeleteCapture.read(0,{10},{eligible}){eligible=false;window})}
}
