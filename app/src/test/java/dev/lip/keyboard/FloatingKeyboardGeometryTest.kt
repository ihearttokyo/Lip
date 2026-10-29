package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FloatingKeyboardGeometryTest {
    private val floating=FloatingKeyboardPreferences(mode=FloatingKeyboardMode.FLOATING)
    private val zero=FloatingKeyboardGeometry.Insets(0,0,0,0)
    private fun rect(l:Int,t:Int,r:Int,b:Int)=FloatingKeyboardGeometry.Rect(l,t,r,b)
    private fun limits(w:Int=320,h:Int=280,maxW:Int=Int.MAX_VALUE,maxH:Int=Int.MAX_VALUE)=FloatingKeyboardGeometry.Limits(w,h,maxW,maxH)
    private fun fit(value:FloatingKeyboardPreferences=floating,window:FloatingKeyboardGeometry.Rect=rect(0,0,1080,2000),
        insets:FloatingKeyboardGeometry.Insets=zero,min:FloatingKeyboardGeometry.Limits=limits())=
        FloatingKeyboardGeometry.resolve(value,window,insets,min)
    @Test fun defaultsDockAndMeasuredWindowLocalInsetsMoveBothAxes() {
        assertEquals(FloatingKeyboardGeometry.Fit.Docked("docked"),fit(FloatingKeyboardPreferences()))
        val window=rect(20,40,1020,1840);val insets=FloatingKeyboardGeometry.Insets(15,30,25,50)
        val start=fit(floating.copy(xBp=0,yBp=0,widthBp=5000,heightBp=4000),window,insets) as FloatingKeyboardGeometry.Fit.Floating
        assertEquals(rect(35,70,995,1790),start.safe)
        assertEquals(rect(35,70,515,758),start.rect)
        val end=fit(floating.copy(xBp=10000,yBp=10000,widthBp=5000,heightBp=4000),window,insets) as FloatingKeyboardGeometry.Fit.Floating
        assertEquals(995,end.rect.right);assertEquals(1790,end.rect.bottom)
        assertTrue(end.rect.left>start.rect.left);assertTrue(end.rect.top>start.rect.top)
        val normalized=FloatingKeyboardGeometry.normalize(end.rect,end.safe)!!
        assertEquals(10000,normalized.xBp);assertEquals(10000,normalized.yBp)
        assertEquals(end.rect,(fit(normalized,window,insets) as FloatingKeyboardGeometry.Fit.Floating).rect)
    }
    @Test fun intrinsicAndCapsClampTogetherWithoutChangingPreferences() {
        val value=floating.copy(xBp=10000,yBp=10000,widthBp=1,heightBp=10000)
        val before=value.copy()
        val result=fit(value,min=limits(400,350,650,700)) as FloatingKeyboardGeometry.Fit.Floating
        assertEquals(400,result.rect.width);assertEquals(700,result.rect.height)
        assertEquals(1080,result.rect.right);assertEquals(2000,result.rect.bottom);assertEquals(before,value)
        val full=fit(floating.copy(widthBp=10000,heightBp=10000),min=limits()) as FloatingKeyboardGeometry.Fit.Floating
        assertEquals(full.safe,full.rect)
    }
    @Test fun genuineNarrowRotatedAndMediaBudgetNoFitAreHonestDockNotOffscreen() {
        assertEquals(FloatingKeyboardGeometry.Fit.Docked("no_fit"),fit(window=rect(0,0,300,2000)))
        assertEquals(FloatingKeyboardGeometry.Fit.Docked("no_fit"),fit(window=rect(0,0,2000,240)))
        assertEquals(FloatingKeyboardGeometry.Fit.Docked("no_fit"),fit(min=limits(320,2200)))
        assertEquals(FloatingKeyboardGeometry.Fit.Docked("invalid_limits"),fit(min=limits(0,1)))
        assertEquals(FloatingKeyboardGeometry.Fit.Docked("invalid_limits"),fit(min=limits(320,280,319,1000)))
        val preferred=floating.copy(widthBp=8000,heightBp=6000)
        val small=fit(preferred,rect(0,0,360,600),min=limits(320,280)) as FloatingKeyboardGeometry.Fit.Floating
        assertEquals(320,small.rect.width)
        val restored=fit(preferred,rect(0,0,1000,1000),min=limits()) as FloatingKeyboardGeometry.Fit.Floating
        assertEquals(800,restored.rect.width);assertEquals(600,restored.rect.height)
    }
    @Test fun invalidOriginsInsetsBoundsAndOverflowRefuse() {
        val windows=listOf(rect(-1,0,500,1000),rect(0,-1,500,1000),rect(0,0,0,1000),rect(500,0,400,1000),rect(Int.MIN_VALUE,0,Int.MAX_VALUE,1000))
        for(window in windows)assertEquals(FloatingKeyboardGeometry.Fit.Docked("invalid_window"),fit(window=window))
        for(insets in listOf(FloatingKeyboardGeometry.Insets(-1,0,0,0),FloatingKeyboardGeometry.Insets(Int.MAX_VALUE,0,0,0),FloatingKeyboardGeometry.Insets(0,1000,0,1000)))
            assertEquals(FloatingKeyboardGeometry.Fit.Docked("invalid_window"),fit(insets=insets))
        val huge=fit(floating.copy(xBp=10000,yBp=10000,widthBp=1,heightBp=1),rect(0,0,Int.MAX_VALUE,Int.MAX_VALUE)) as FloatingKeyboardGeometry.Fit.Floating
        assertEquals(Int.MAX_VALUE,huge.rect.right);assertEquals(Int.MAX_VALUE,huge.rect.bottom)
        assertTrue(huge.rect.width>0);assertTrue(huge.rect.height>0)
    }
    @Test fun normalizationRejectsUnreadyOrOutsideDraftsAndQuantizationIsBounded() {
        val safe=rect(17,29,417,829)
        for(draft in listOf(rect(0,29,350,500),rect(17,0,350,500),rect(17,29,418,500),rect(17,29,350,830),rect(17,29,17,100),rect(Int.MIN_VALUE,29,Int.MAX_VALUE,100)))
            assertNull(FloatingKeyboardGeometry.normalize(draft,safe))
        assertNull(FloatingKeyboardGeometry.normalize(safe,rect(0,0,0,0)))
        assertNull(FloatingKeyboardGeometry.normalize(rect(-2,0,1,1),rect(-2,0,1,1)))
        for(x in listOf(0,1234,5000,9999,10000))for(y in listOf(0,3210,10000))for(w in listOf(1,3000,7000,10000)){
            val fit=fit(floating.copy(xBp=x,yBp=y,widthBp=w,heightBp=6000),rect(17,29,417,829),min=limits(1,1)) as FloatingKeyboardGeometry.Fit.Floating
            val normalized=FloatingKeyboardGeometry.normalize(fit.rect,fit.safe)!!
            val again=fit(normalized,rect(17,29,417,829),min=limits(1,1)) as FloatingKeyboardGeometry.Fit.Floating
            assertTrue(abs(fit.rect.left-again.rect.left)<=1);assertTrue(abs(fit.rect.top-again.rect.top)<=1)
            assertTrue(abs(fit.rect.width-again.rect.width)<=1);assertTrue(abs(fit.rect.height-again.rect.height)<=1)
        }
    }
    @Test fun boundedGridAlwaysFitsAndIntrinsicBudgetsAreCallerDefinedNotLetterAssumptions() {
        for(width in listOf(320,400,800,2000))for(height in listOf(280,600,1200))for(x in listOf(0,5000,10000))for(y in listOf(0,5000,10000)){
            val result=fit(floating.copy(xBp=x,yBp=y),rect(11,17,11+width,17+height),min=limits()) as FloatingKeyboardGeometry.Fit.Floating
            assertTrue(result.rect.left>=result.safe.left);assertTrue(result.rect.top>=result.safe.top)
            assertTrue(result.rect.right<=result.safe.right);assertTrue(result.rect.bottom<=result.safe.bottom)
            assertTrue(result.rect.width>=320);assertTrue(result.rect.height>=280)
        }
        assertTrue(fit(window=rect(0,0,350,700),min=limits(200,500)) is FloatingKeyboardGeometry.Fit.Floating)
        assertTrue(fit(window=rect(0,0,350,700),min=limits(360,500)) is FloatingKeyboardGeometry.Fit.Docked)
    }
}
