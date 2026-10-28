package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class HorizontalKeyDragTest {
    private fun tracker()=HorizontalKeyDrag(8f,14f,500).apply{down(100f,20f,1000)}
    @Test fun cursorRequiresKnownEditorMetadataButNotPrivateText(){
        for(type in listOf(1,2,3,4,0x81,0x91,0xe1))assertTrue(ImeFieldPolicy.manualCursor(type,0,0))
        assertFalse(ImeFieldPolicy.manualCursor(0,0,0));assertFalse(ImeFieldPolicy.manualCursor(1,-1,0));assertFalse(ImeFieldPolicy.manualCursor(1,0,-1))
    }
    @Test fun tapAndJitterDoNotConsumeSpace(){val t=tracker();assertFalse(t.move(103f,21f,1010,1).consume);assertFalse(t.up(103f,21f,1).consume)}
    @Test fun thresholdStartsWithoutInventingStep(){val t=tracker();assertEquals(HorizontalKeyDrag.Update(true,true,0),t.move(90f,20f,1020,1));assertEquals(-1,t.move(86f,20f,1030,1).steps);assertTrue(t.up(86f,20f,1).consume)}
    @Test fun reverseUsesAbsoluteDisplacement(){val t=tracker();assertEquals(-2,t.move(72f,20f,1020,1).steps);assertEquals(3,t.move(114f,20f,1040,1).steps);assertEquals(-1,t.move(100f,20f,1050,1).steps)}
    @Test fun canceledAndMultitouchNeverBecomeTap(){for(mode in 0..2){val t=tracker();when(mode){0->t.cancel();1->t.move(100f,35f,1020,1);2->t.move(86f,20f,1020,2)};assertTrue(t.up(100f,20f,1).consume)}}
    @Test fun longHoldDoesNotBecomeDrag(){val t=tracker();assertEquals(0,t.move(70f,20f,1500,1).steps);assertTrue(t.up(100f,20f,1).consume)}
    @Test fun invalidOrLateMotionCannotNavigate(){for(v in listOf(Float.NaN,Float.POSITIVE_INFINITY)){val t=tracker();assertEquals(0,t.move(v,20f,1010,1).steps);assertTrue(t.up(100f,20f,1).consume)};val t=tracker();assertEquals(0,t.move(70f,20f,900,1).steps)}
    @Test fun workIsBoundedAndReleaseEndsDispatch(){val t=tracker();var total=0;repeat(20){val n=t.move(-100000f,20f,1020+it.toLong(),1).steps;assertTrue(kotlin.math.abs(n)<=16);total+=n};assertEquals(-128,total);t.up(-100000f,20f,1);assertEquals(0,t.move(200f,20f,2000,1).steps)}
    @Test fun totalBudgetAndExpiredContactCannotKeepDispatching(){
        val t=tracker();var total=0
        for(i in 0 until 100){val update=t.move(if(i%16<8)-10000f else 10000f,20f,1020+i.toLong(),1);total+=kotlin.math.abs(update.steps)}
        assertTrue(total<=512);assertEquals(0,t.move(200f,20f,1200,1).steps);assertTrue(t.up(100f,20f,1).consume)
        val expired=tracker();expired.move(70f,20f,1020,1);assertEquals(0,expired.move(50f,20f,31001,1).steps)
    }
    @Test fun activeVerticalEscapeConsumesWithoutMoreMovement(){val t=tracker();t.move(72f,20f,1020,1);assertEquals(0,t.move(72f,100f,1040,1).steps);assertTrue(t.up(100f,20f,1).consume)}
}
