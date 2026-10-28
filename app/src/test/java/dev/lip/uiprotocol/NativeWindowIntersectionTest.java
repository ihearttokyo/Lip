package dev.lip.uiprotocol;

import org.junit.Test;
import static org.junit.Assert.*;
import dev.lip.uiprotocol.NativeWindowIntersection.Bounds;
import dev.lip.uiprotocol.NativeWindowIntersection.Input;

/** Pure proof-policy controls, not Android window selection, clipping or physical-input evidence. */
public final class NativeWindowIntersectionTest {
    private Bounds b(int l,int t,int r,int bottom){return new Bounds(l,t,r,bottom);}
    private Input valid(int x,int y){
        Bounds local=b(8,42,140,75), screen=b(x+8,y+42,x+140,y+75),window=b(x,y,x+400,y+240);
        return new Input(local,local,screen,window,window,7,7,7,7,1,400,240);
    }
    private Input bounds(Input i,Bounds nativeLocal,Bounds axLocal,Bounds screen,Bounds window,Bounds root){
        return new Input(nativeLocal,axLocal,screen,window,root,i.nativeWindowId,i.axNodeWindowId,
            i.axWindowId,i.axRootWindowId,i.matchingImeWindowCount,i.nativeDecorWidth,i.nativeDecorHeight);
    }
    private Input ids(Input i,int nativeId,int node,int window,int root,int count){
        return new Input(i.nativeVisibleInWindow,i.axNodeInWindow,i.axNodeInScreen,i.axWindowInScreen,
            i.axRootInScreen,nativeId,node,window,root,count,i.nativeDecorWidth,i.nativeDecorHeight);
    }
    @Test public void acceptsDifferentOriginsWithoutFittingOneObservedOffset(){
        for(int[] origin:new int[][]{{0,0},{0,122},{51,777},{-400,-240},{1,1}}){
            Input i=valid(origin[0],origin[1]);assertEquals(i.axNodeInScreen,NativeWindowIntersection.project(i));
        }
    }
    @Test public void acceptsNonzeroClippedLocalRectangleNotWholeViewBounds(){
        Input i=valid(33,122);assertEquals(b(41,164,173,197),NativeWindowIntersection.project(i));
        // Zero local origin and exact full decor bounds are also legitimate, positive rectangles.
        Bounds local=b(0,0,400,240),window=i.axWindowInScreen;
        assertEquals(window,NativeWindowIntersection.project(bounds(i,local,local,window,window,window)));
    }
    @Test public void acceptsZeroWindowIdButRejectsUnknownOrMismatchedIdentities(){
        Input i=valid(0,122);assertNotNull(NativeWindowIntersection.project(ids(i,0,0,0,0,1)));
        for(int n=0;n<4;n++){
            int[] values={7,7,7,7};values[n]=-1;assertNull(NativeWindowIntersection.project(ids(i,values[0],values[1],values[2],values[3],1)));
            values[n]=8;assertNull(NativeWindowIntersection.project(ids(i,values[0],values[1],values[2],values[3],1)));
        }
        for(int count:new int[]{-1,0,2,Integer.MAX_VALUE})assertNull(NativeWindowIntersection.project(ids(i,7,7,7,7,count)));
    }
    @Test public void requiresWindowAndRootToHaveExactlySameScreenBounds(){
        Input i=valid(0,122);
        assertNull(NativeWindowIntersection.project(bounds(i,i.nativeVisibleInWindow,i.axNodeInWindow,i.axNodeInScreen,i.axWindowInScreen,b(1,122,401,362))));
        assertNull(NativeWindowIntersection.project(bounds(i,i.nativeVisibleInWindow,i.axNodeInWindow,i.axNodeInScreen,b(0,123,400,363),i.axRootInScreen)));
    }
    @Test public void requiresBothDecorDimensionsWithoutUsingMetricsAsAnOrigin(){
        Input i=valid(0,122);
        for(int[] size:new int[][]{{399,240},{400,239},{0,240},{400,0},{-1,240}})
            assertNull(NativeWindowIntersection.project(new Input(i.nativeVisibleInWindow,i.axNodeInWindow,i.axNodeInScreen,
                i.axWindowInScreen,i.axRootInScreen,7,7,7,7,1,size[0],size[1])));
    }
    @Test public void rejectsNativeVersusAxLocalDisagreementEvenWhenSizesMatch(){
        Input i=valid(0,122);
        for(Bounds local:new Bounds[]{b(9,42,141,75),b(8,43,140,76),b(8,42,139,75),b(8,42,140,74)}){
            assertNull(NativeWindowIntersection.project(bounds(i,local,i.axNodeInWindow,i.axNodeInScreen,i.axWindowInScreen,i.axRootInScreen)));
            assertNull(NativeWindowIntersection.project(bounds(i,i.nativeVisibleInWindow,local,i.axNodeInScreen,i.axWindowInScreen,i.axRootInScreen)));
        }
    }
    @Test public void rejectsScreenOriginMismatchScaleAndSkewNotJustSizeDifference(){
        Input i=valid(51,777);
        for(Bounds screen:new Bounds[]{b(60,819,192,852),b(59,820,191,853),b(59,819,192,852),
                b(67,861,331,927),b(60,819,191,853)})
            assertNull(NativeWindowIntersection.project(bounds(i,i.nativeVisibleInWindow,i.axNodeInWindow,screen,i.axWindowInScreen,i.axRootInScreen)));
    }
    @Test public void rejectsUnclippedOrOutOfWindowLocalBounds(){
        Input i=valid(0,122);
        for(Bounds local:new Bounds[]{b(-1,42,140,75),b(8,-1,140,75),b(8,42,401,75),b(8,42,140,241)})
            assertNull(NativeWindowIntersection.project(bounds(i,local,local,i.axNodeInScreen,i.axWindowInScreen,i.axRootInScreen)));
    }
    @Test public void rejectsEveryMissingEmptyOrInvertedRectangle(){
        Input i=valid(0,122);assertNull(NativeWindowIntersection.project(null));
        for(Bounds invalid:new Bounds[]{null,b(1,1,1,2),b(1,1,2,1),b(2,1,1,2),b(1,2,2,1)}){
            assertNull(NativeWindowIntersection.project(bounds(i,invalid,i.axNodeInWindow,i.axNodeInScreen,i.axWindowInScreen,i.axRootInScreen)));
            assertNull(NativeWindowIntersection.project(bounds(i,i.nativeVisibleInWindow,invalid,i.axNodeInScreen,i.axWindowInScreen,i.axRootInScreen)));
            assertNull(NativeWindowIntersection.project(bounds(i,i.nativeVisibleInWindow,i.axNodeInWindow,invalid,i.axWindowInScreen,i.axRootInScreen)));
            assertNull(NativeWindowIntersection.project(bounds(i,i.nativeVisibleInWindow,i.axNodeInWindow,i.axNodeInScreen,invalid,i.axRootInScreen)));
            assertNull(NativeWindowIntersection.project(bounds(i,i.nativeVisibleInWindow,i.axNodeInWindow,i.axNodeInScreen,i.axWindowInScreen,invalid)));
        }
    }
    @Test public void rejectsArithmeticOverflowRatherThanWrappingCoordinates(){
        Input i=valid(0,122);
        Bounds huge=b(Integer.MIN_VALUE,0,Integer.MAX_VALUE,240);
        assertNull(NativeWindowIntersection.project(bounds(i,i.nativeVisibleInWindow,i.axNodeInWindow,i.axNodeInScreen,huge,huge)));
        Bounds wrapped=b(Integer.MAX_VALUE-100,0,Integer.MIN_VALUE+299,240);
        assertNull(NativeWindowIntersection.project(bounds(i,i.nativeVisibleInWindow,i.axNodeInWindow,i.axNodeInScreen,wrapped,wrapped)));
        Bounds largeLocal=b(8,42,Integer.MAX_VALUE,75);
        assertNull(NativeWindowIntersection.project(bounds(i,largeLocal,largeLocal,i.axNodeInScreen,i.axWindowInScreen,i.axRootInScreen)));
    }
    @Test public void boundaryOriginsRemainExactWhenSumsFit(){
        Input i=valid(Integer.MAX_VALUE-400,Integer.MIN_VALUE);
        assertEquals(i.axNodeInScreen,NativeWindowIntersection.project(i));
    }
    @Test public void returnsFreshImmutableBoundsAndPreservesAllInputs() throws Exception {
        Input i=valid(51,777);Bounds before=b(8,42,140,75), expected=b(59,819,191,852);
        Bounds result=NativeWindowIntersection.project(i);
        assertNotSame(i.nativeVisibleInWindow,result);assertNotSame(i.axNodeInScreen,result);
        assertEquals(expected,result);assertEquals(before,i.nativeVisibleInWindow);assertEquals(before,i.axNodeInWindow);
        for(String field:new String[]{"left","top","right","bottom"})assertTrue(java.lang.reflect.Modifier.isFinal(Bounds.class.getField(field).getModifiers()));
        assertEquals(expected,NativeWindowIntersection.project(i));
    }
}
