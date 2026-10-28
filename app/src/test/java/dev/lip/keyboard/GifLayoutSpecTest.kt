package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class GifLayoutSpecTest {
    @Test fun columnsUseActualPixelWidthAndRemainBounded(){
        for(density in listOf(1f,2f,2.625f,3f)){
            for((dp,expected) in listOf(1 to 1,127 to 1,128 to 1,255 to 1,256 to 2,384 to 3,512 to 4,914 to 4)){
                assertEquals(expected,GifLayoutSpec.columns((dp*density).toInt().coerceAtLeast(1),density))
            }
        }
        for(width in 1..2400)assertTrue(GifLayoutSpec.columns(width,2.625f) in 1..4)
    }

    @Test fun measuredFirstRowAndIntroExpandPanelWithoutExceedingActualCap(){
        assertEquals(626,GifLayoutSpec.panelHeight(435,864,126,150,350))
        assertEquals(700,GifLayoutSpec.panelHeight(700,1800,126,100,350))
        assertEquals(500,GifLayoutSpec.panelHeight(435,500,126,150,350))
        assertEquals(180,GifLayoutSpec.panelHeight(435,180,126,150,350))
        assertEquals(435,GifLayoutSpec.panelHeight(435,864,126,60,0))
    }

    @Test fun panelArithmeticDoesNotOverflow(){
        assertEquals(100,GifLayoutSpec.panelHeight(Int.MAX_VALUE,100,Int.MAX_VALUE,Int.MAX_VALUE,Int.MAX_VALUE))
        for(available in 1..900 step 7)for(intro in listOf(0,34,85,200))for(row in listOf(0,180,350)){
            val actual=GifLayoutSpec.panelHeight(435,available,126,intro,row)
            assertTrue(actual in 1..available)
            if(available>=maxOf(435,126+intro+row))assertEquals(maxOf(435,126+intro+row),actual)
        }
    }

    @Test fun invalidGeometryIsRejected(){
        for(density in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY))assertThrows(IllegalArgumentException::class.java){GifLayoutSpec.columns(411,density)}
        assertThrows(IllegalArgumentException::class.java){GifLayoutSpec.columns(0,1f)}
        for(values in listOf(listOf(0,100,48,0,0),listOf(100,0,48,0,0),listOf(100,100,0,0,0),listOf(100,100,48,-1,0),listOf(100,100,48,0,-1))){
            assertThrows(IllegalArgumentException::class.java){GifLayoutSpec.panelHeight(values[0],values[1],values[2],values[3],values[4])}
        }
    }
}
